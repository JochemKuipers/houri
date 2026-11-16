// Runs the REAL memory arithmetic (compiled straight from app/.../WebGpuMemoryMath.kt).
//
// This is the file that replaced `isLowRamDevice`, and the fold is deliberately not obvious:
//
//   - the RAM count picks a coarse bucket;
//   - the vendor's low-RAM mark folds a large-but-marked device down to MEDIUM;
//   - a 32-bit userspace is capped at SMALL regardless, because its whole ~3GB address space is
//     shared between the Java heap, the native decode buffers and the GPU's own allocations.
//
// Each of those is a decision a reader can see: how many pages stay resident, how large a decoded
// page may be before it is downsampled, and whether a small device runs one decode worker or two.
// Getting one wrong is not a crash - it is a reader that quietly OOMs two pages into a comic, or a
// 12GB phone that runs like a 2GB one.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DeviceTier
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.MemoryPressure
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.budgetScaleFor
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.deviceTierFor
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.deviceTierOfTotalMem
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.fitsWorkingSet
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.memoryHeadroom
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.memoryPressureOf
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.pixelScaleFor
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.resolvePixelCeiling
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.scaledDecodedPagesBytes
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.scaledDecodedPixels
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.scaledJxlPageBytes
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.scaledMaxPageBytes
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.scaledTileCacheBytes
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.uploadedImageBytes

private const val MB = 1024L * 1024L
private const val GB = 1024L * MB

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

fun main() {
    // --- the size class, by RAM -----------------------------------------------------------
    check("1GB is TINY", deviceTierFor(1L * GB, false, true) == DeviceTier.TINY)
    check("2GB minus a byte is TINY", deviceTierFor(2L * GB - 1, false, true) == DeviceTier.TINY)
    check("2GB is SMALL", deviceTierFor(2L * GB, false, true) == DeviceTier.SMALL)
    check("3GB is MEDIUM", deviceTierFor(3L * GB, false, true) == DeviceTier.MEDIUM)
    check("6GB is LARGE", deviceTierFor(6L * GB, false, true) == DeviceTier.LARGE)
    check("12GB is XLARGE", deviceTierFor(12L * GB, false, true) == DeviceTier.XLARGE)
    check("24GB is still XLARGE", deviceTierFor(24L * GB, false, true) == DeviceTier.XLARGE)

    // --- the vendor low-RAM mark folds a big device down, but only past MEDIUM --------------
    check("a marked 1GB device stays TINY", deviceTierFor(1L * GB, true, true) == DeviceTier.TINY)
    check("a marked 4GB device is folded to MEDIUM", deviceTierFor(4L * GB, true, true) == DeviceTier.MEDIUM)
    check("a marked 8GB device is folded to MEDIUM", deviceTierFor(8L * GB, true, true) == DeviceTier.MEDIUM)
    check("a marked 16GB device is folded to MEDIUM", deviceTierFor(16L * GB, true, true) == DeviceTier.MEDIUM)
    // Folding only happens above MEDIUM: marking a 3GB device changes nothing, because it was
    // already there.
    check("a marked 3GB device is unchanged", deviceTierFor(3L * GB, true, true) == DeviceTier.MEDIUM)

    // --- the ABI cap, which is the non-obvious one ----------------------------------------
    check("32-bit 16GB is capped at SMALL", deviceTierFor(16L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 6GB is capped at SMALL", deviceTierFor(6L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 3GB is capped at SMALL", deviceTierFor(3L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 2GB stays SMALL", deviceTierFor(2L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 1GB is TINY (below the cap)", deviceTierFor(1L * GB, false, false) == DeviceTier.TINY)
    check("64-bit is unaffected by the cap", deviceTierFor(8L * GB, false, true) == DeviceTier.LARGE)
    // The ABI cap and the low-RAM fold compose; neither undoes the other.
    check("32-bit marked 8GB is capped to SMALL", deviceTierFor(8L * GB, true, false) == DeviceTier.SMALL)

    // --- the no-MemoryInfo fallback -------------------------------------------------------
    check("an unreadable total never becomes TINY", deviceTierOfTotalMem(0L) == deviceTierOfTotalMem(4L * GB))
    check("a negative total falls back too", deviceTierOfTotalMem(-1L) == deviceTierOfTotalMem(4L * GB))

    // --- headroom, not availMem -----------------------------------------------------------
    check("headroom subtracts the threshold", memoryHeadroom(3L * GB, 1L * GB) == 2L * GB)
    check("headroom never goes negative", memoryHeadroom(1L * GB, 3L * GB) == 0L)
    check("headroom is zero when they are equal", memoryHeadroom(2L * GB, 2L * GB) == 0L)

    // --- pressure ---------------------------------------------------------------------------
    val roomy = memoryPressureOf(false, 4L * GB, 0L, 512L * MB, false)
    check("plenty of headroom is ROOMY", roomy == MemoryPressure.ROOMY)

    check("the system's own verdict wins", memoryPressureOf(true, 8L * GB, 0L, 512L * MB, false) == MemoryPressure.CRITICAL)
    // The heap is measured against the runtime ceiling, not the device's RAM - a 512MB heap on a
    // 12GB device is still a full heap.
    check(
        "a nearly full heap is CRITICAL even with headroom",
        memoryPressureOf(false, 8L * GB, 480L * MB, 512L * MB, false) == MemoryPressure.CRITICAL,
    )
    check(
        "a 70%-full heap is TIGHT",
        memoryPressureOf(false, 8L * GB, 360L * MB, 512L * MB, false) == MemoryPressure.TIGHT,
    )
    check(
        "a 69%-full heap is still ROOMY",
        memoryPressureOf(false, 8L * GB, 350L * MB, 512L * MB, false) == MemoryPressure.ROOMY,
    )

    // Headroom thresholds, in the absence of a heap or system signal.
    check("below 256MB headroom is CRITICAL", memoryPressureOf(false, 256L * MB - 1, 0L, 0L, false) == MemoryPressure.CRITICAL)
    check("exactly 256MB headroom is TIGHT", memoryPressureOf(false, 256L * MB, 0L, 0L, false) == MemoryPressure.TIGHT)
    check("512MB headroom is TIGHT", memoryPressureOf(false, 512L * MB, 0L, 0L, false) == MemoryPressure.TIGHT)
    check("just under 1GB headroom is still TIGHT", memoryPressureOf(false, 1L * GB - 1, 0L, 0L, false) == MemoryPressure.TIGHT)
    check("1GB headroom is ROOMY", memoryPressureOf(false, 1L * GB, 0L, 0L, false) == MemoryPressure.ROOMY)
    // A low-RAM device is held to a stricter bar at the top end: 1.5GB would be ROOMY for a normal
    // device but is TIGHT for one the vendor marked.
    check("a low-RAM device needs 2GB to be ROOMY", memoryPressureOf(false, 3L * GB, 0L, 0L, false) == MemoryPressure.ROOMY)
    check("a low-RAM device at 1.5GB is TIGHT", memoryPressureOf(false, 1_536L * MB, 0L, 0L, true) == MemoryPressure.TIGHT)
    check("a normal device at 1.5GB is ROOMY", memoryPressureOf(false, 1_536L * MB, 0L, 0L, false) == MemoryPressure.ROOMY)

    // The ordering is the recovery test, so it has to be monotonic.
    check(
        "pressure is ordered ROOMY < TIGHT < CRITICAL",
        MemoryPressure.ROOMY.ordinal < MemoryPressure.TIGHT.ordinal &&
            MemoryPressure.TIGHT.ordinal < MemoryPressure.CRITICAL.ordinal,
    )

    // --- the scales ---------------------------------------------------------------------------
    check("ROOMY keeps every byte", budgetScaleFor(MemoryPressure.ROOMY) == 1.0f)
    check("TIGHT sheds bytes harder than pixels", budgetScaleFor(MemoryPressure.TIGHT) < pixelScaleFor(MemoryPressure.TIGHT))
    check("CRITICAL sheds bytes hardest", budgetScaleFor(MemoryPressure.CRITICAL) < budgetScaleFor(MemoryPressure.TIGHT))
    check("ROOMY keeps every pixel", pixelScaleFor(MemoryPressure.ROOMY) == 1.0f)

    // --- the pixel ceiling -------------------------------------------------------------------
    val einkPanel = 1_200L * 800L
    check(
        "a small panel caps the tier's ceiling",
        resolvePixelCeiling(DeviceTier.XLARGE.maxDecodedPixels, einkPanel) == einkPanel * 4L,
    )
    check(
        "a 1440p panel is the binding constraint on a big tier",
        resolvePixelCeiling(DeviceTier.XLARGE.maxDecodedPixels, 2_560L * 1_440L) == 2_560L * 1_440L * 4L,
    )
    check(
        "a tiny tier is the binding constraint on a big panel",
        resolvePixelCeiling(DeviceTier.TINY.maxDecodedPixels, 4_000L * 3_000L) == DeviceTier.TINY.maxDecodedPixels,
    )
    // The floor keeps the cap from starving the display itself: 320x240 is below 2MP, and the
    // answer has to be the floor, not the panel.
    check("a tiny panel still gets the pixel floor", resolvePixelCeiling(32L * 1_000 * 1_000, 320L * 240L) == 2L * 1_000 * 1_000)
    check("an unreadable panel means no panel constraint", resolvePixelCeiling(DeviceTier.MEDIUM.maxDecodedPixels, 0L) == DeviceTier.MEDIUM.maxDecodedPixels)

    // --- the byte ceilings, scaled and floored ------------------------------------------------
    val tier = DeviceTier.MEDIUM
    check("ROOMY keeps the tier's decoded budget", scaledDecodedPagesBytes(tier, MemoryPressure.ROOMY) == tier.maxDecodedPagesBytes)
    check("TIGHT shrinks the decoded budget", scaledDecodedPagesBytes(tier, MemoryPressure.TIGHT) < tier.maxDecodedPagesBytes)
    check(
        "CRITICAL shrinks the decoded budget further",
        scaledDecodedPagesBytes(tier, MemoryPressure.CRITICAL) < scaledDecodedPagesBytes(tier, MemoryPressure.TIGHT),
    )
    check("the tile budget scales too", scaledTileCacheBytes(tier, MemoryPressure.CRITICAL) < tier.maxTileCacheBytes)
    check("the page ceiling scales too", scaledMaxPageBytes(tier, MemoryPressure.CRITICAL) < tier.maxPageBytesBytes)
    check("the JXL ceiling scales too", scaledJxlPageBytes(tier, MemoryPressure.CRITICAL) < tier.maxJxlPageBytes)

    // The floors: below these, holding fewer pages stops helping. Even the smallest tier at
    // CRITICAL must stay above its floor, or every scroll becomes a re-decode.
    check(
        "the decoded floor holds at CRITICAL on TINY",
        scaledDecodedPagesBytes(DeviceTier.TINY, MemoryPressure.CRITICAL) >= 64L * MB,
    )
    check(
        "the tile floor holds at CRITICAL on TINY",
        scaledTileCacheBytes(DeviceTier.TINY, MemoryPressure.CRITICAL) >= 16L * MB,
    )
    check(
        "the page floor holds at CRITICAL on TINY",
        scaledMaxPageBytes(DeviceTier.TINY, MemoryPressure.CRITICAL) >= 24L * MB,
    )
    check(
        "the JXL floor holds at CRITICAL on TINY",
        scaledJxlPageBytes(DeviceTier.TINY, MemoryPressure.CRITICAL) >= 8L * MB,
    )
    check(
        "the pixel floor holds at CRITICAL on TINY",
        scaledDecodedPixels(DeviceTier.TINY, MemoryPressure.CRITICAL) >= 2L * 1_000 * 1_000,
    )

    // The JXL ceiling is below the page ceiling at every tier and every pressure - JXL costs
    // markedly more per byte than JPEG, and the two must not converge.
    for (t in DeviceTier.entries) {
        for (p in MemoryPressure.entries) {
            check(
                "${t.name} at $p keeps the JXL ceiling below the page ceiling",
                scaledJxlPageBytes(t, p) <= scaledMaxPageBytes(t, p),
            )
        }
    }

    // --- the working-set gate ----------------------------------------------------------------
    check("a page that fits twice over is affordable", fitsWorkingSet(100L * MB, 512L * MB))
    check("a page that exactly fits twice is affordable", fitsWorkingSet(256L * MB, 512L * MB))
    check("a page that does not fit twice is not", !fitsWorkingSet(300L * MB, 512L * MB))
    check("a zero-byte page is affordable", fitsWorkingSet(0L, 512L * MB))
    check("nothing is affordable with no budget", !fitsWorkingSet(1L, 0L))

    // --- the mip chain, which is the measurement everything is sized from -----------------------
    // The chain is 4/3 of the base; measuring the base alone undercounts by a third, which is what
    // let a cache "fit" and then overflow.
    check("a 1000x1000 RGBA page is 4MB base", uploadedImageBytes(1000, 1000) == 4_000_000L + 4_000_000L / 3L)
    check("the measurement carries the mip chain", uploadedImageBytes(1000, 1000) > 4_000_000L)
    check("the chain is exactly a third more", uploadedImageBytes(1000, 1000) - 4_000_000L == 1_333_333L)
    check("an 800x61000 strip measures its true cost", uploadedImageBytes(800, 61_000) == 800L * 61_000L * 4L * 4L / 3L)
    check("a zero-width page measures zero", uploadedImageBytes(0, 1000) == 0L)
    check("a zero-height page measures zero", uploadedImageBytes(1000, 0) == 0L)
    check("a negative dimension measures zero", uploadedImageBytes(-5, -5) == 0L)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}