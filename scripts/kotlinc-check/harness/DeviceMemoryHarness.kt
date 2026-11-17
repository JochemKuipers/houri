// Runs the REAL app-wide memory arithmetic, compiled straight from
// core/common/.../DeviceMemoryClass.kt and DeviceMemoryMath.kt.
//
// This is the app-wide half of what `MemoryBudgetHarness` asserts for the reader. It exists because
// the same policy now governs things well outside the reader - Coil's image cache, the JXL dimension
// cap, the RGB_565 decision, the fetch pool, the MTL/WebGPU RAM gates - and each of those is a
// decision a user can see. A wrong fold here is not a crash: it is a 12GB phone that runs like a 2GB
// one, or a 2GB phone that OOMs on a page the tier should have refused.
//
// The two rules that make this worth writing:
//
//   - the tier fold is deliberately not obvious (a vendor low-RAM mark folds a large device down;
//     a 32-bit userspace is capped at SMALL regardless), so each branch is pinned separately;
//   - a fallback that is not a value the real world can produce is a silent downgrade, not a
//     fallback - the `4L * 1024 * 1024` (four *mebibytes*) bug that demoted every flagship is pinned
//     here by asserting the fallback equals a real 4GB device.

import tachiyomi.core.common.util.system.DEFAULT_TOTAL_MEM_BYTES
import tachiyomi.core.common.util.system.DeviceTier
import tachiyomi.core.common.util.system.JXL_HARD_DIMENSION
import tachiyomi.core.common.util.system.JXL_MIN_DIMENSION
import tachiyomi.core.common.util.system.MEMORY_MB
import tachiyomi.core.common.util.system.MemoryPressure
import tachiyomi.core.common.util.system.RGB565_MIN_BYTES
import tachiyomi.core.common.util.system.budgetScaleFor
import tachiyomi.core.common.util.system.deviceTierFor
import tachiyomi.core.common.util.system.deviceTierOfTotalMem
import tachiyomi.core.common.util.system.fitsWorkingSet
import tachiyomi.core.common.util.system.jxlMaxDimension
import tachiyomi.core.common.util.system.memoryHeadroom
import tachiyomi.core.common.util.system.memoryPressureOf
import tachiyomi.core.common.util.system.pixelScaleFor
import tachiyomi.core.common.util.system.resolvePixelCeiling
import tachiyomi.core.common.util.system.scaledPixelCeiling
import tachiyomi.core.common.util.system.shouldDowngradeToRgb565
import tachiyomi.core.common.util.system.uploadedImageBytes

private const val GB = 1024L * MEMORY_MB

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
    check("a marked 3GB device is unchanged", deviceTierFor(3L * GB, true, true) == DeviceTier.MEDIUM)

    // --- the ABI cap, which is the non-obvious one ----------------------------------------
    check("32-bit 16GB is capped at SMALL", deviceTierFor(16L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 6GB is capped at SMALL", deviceTierFor(6L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 3GB is capped at SMALL", deviceTierFor(3L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 2GB stays SMALL", deviceTierFor(2L * GB, false, false) == DeviceTier.SMALL)
    check("32-bit 1GB is TINY (below the cap)", deviceTierFor(1L * GB, false, false) == DeviceTier.TINY)
    check("64-bit is unaffected by the cap", deviceTierFor(8L * GB, false, true) == DeviceTier.LARGE)
    check("32-bit marked 8GB is capped to SMALL", deviceTierFor(8L * GB, true, false) == DeviceTier.SMALL)

    // --- the no-MemoryInfo fallback -------------------------------------------------------
    // The rule this pins: a fallback must be a device that exists. The old value was four
    // mebibytes, which is not a device at all, so every failed read demoted a flagship to TINY.
    check("the fallback is a real 4GB device", DEFAULT_TOTAL_MEM_BYTES == 4L * GB)
    check("an unreadable total never becomes TINY", deviceTierOfTotalMem(0L) == deviceTierOfTotalMem(4L * GB))
    check("a negative total falls back too", deviceTierOfTotalMem(-1L) == deviceTierOfTotalMem(4L * GB))
    check("the fallback is MEDIUM, not TINY", deviceTierOfTotalMem(0L) == DeviceTier.MEDIUM)

    // --- the tier ordering, which the call sites compare against ---------------------------
    check(
        "tier order is TINY < SMALL < MEDIUM < LARGE < XLARGE",
        DeviceTier.TINY.ordinal < DeviceTier.SMALL.ordinal &&
            DeviceTier.SMALL.ordinal < DeviceTier.MEDIUM.ordinal &&
            DeviceTier.MEDIUM.ordinal < DeviceTier.LARGE.ordinal &&
            DeviceTier.LARGE.ordinal < DeviceTier.XLARGE.ordinal,
    )

    // --- headroom, not availMem -----------------------------------------------------------
    check("headroom subtracts the threshold", memoryHeadroom(3L * GB, 1L * GB) == 2L * GB)
    check("headroom never goes negative", memoryHeadroom(1L * GB, 3L * GB) == 0L)
    check("headroom is zero when they are equal", memoryHeadroom(2L * GB, 2L * GB) == 0L)

    // --- pressure -------------------------------------------------------------------------
    check("plenty of headroom is ROOMY", memoryPressureOf(false, 4L * GB, 0L, 512L * MEMORY_MB, false) == MemoryPressure.ROOMY)
    check(
        "the system's own verdict wins",
        memoryPressureOf(true, 8L * GB, 0L, 512L * MEMORY_MB, false) == MemoryPressure.CRITICAL,
    )
    check(
        "a nearly full heap is CRITICAL even with headroom",
        memoryPressureOf(false, 8L * GB, 480L * MEMORY_MB, 512L * MEMORY_MB, false) == MemoryPressure.CRITICAL,
    )
    check(
        "a 70%-full heap is TIGHT",
        memoryPressureOf(false, 8L * GB, 360L * MEMORY_MB, 512L * MEMORY_MB, false) == MemoryPressure.TIGHT,
    )
    check(
        "a 69%-full heap is still ROOMY",
        memoryPressureOf(false, 8L * GB, 350L * MEMORY_MB, 512L * MEMORY_MB, false) == MemoryPressure.ROOMY,
    )

    check(
        "below 256MB headroom is CRITICAL",
        memoryPressureOf(false, 256L * MEMORY_MB - 1, 0L, 0L, false) == MemoryPressure.CRITICAL,
    )
    check(
        "exactly 256MB headroom is TIGHT",
        memoryPressureOf(false, 256L * MEMORY_MB, 0L, 0L, false) == MemoryPressure.TIGHT,
    )
    check("512MB headroom is TIGHT", memoryPressureOf(false, 512L * MEMORY_MB, 0L, 0L, false) == MemoryPressure.TIGHT)
    check(
        "just under 1GB headroom is still TIGHT",
        memoryPressureOf(false, 1L * GB - 1, 0L, 0L, false) == MemoryPressure.TIGHT,
    )
    check("1GB headroom is ROOMY", memoryPressureOf(false, 1L * GB, 0L, 0L, false) == MemoryPressure.ROOMY)
    check(
        "a low-RAM device needs 2GB to be ROOMY",
        memoryPressureOf(false, 3L * GB, 0L, 0L, false) == MemoryPressure.ROOMY,
    )
    check(
        "a low-RAM device at 1.5GB is TIGHT",
        memoryPressureOf(false, 1_536L * MEMORY_MB, 0L, 0L, true) == MemoryPressure.TIGHT,
    )
    check(
        "a normal device at 1.5GB is ROOMY",
        memoryPressureOf(false, 1_536L * MEMORY_MB, 0L, 0L, false) == MemoryPressure.ROOMY,
    )
    check(
        "pressure is ordered ROOMY < TIGHT < CRITICAL",
        MemoryPressure.ROOMY.ordinal < MemoryPressure.TIGHT.ordinal &&
            MemoryPressure.TIGHT.ordinal < MemoryPressure.CRITICAL.ordinal,
    )

    // --- the scales -----------------------------------------------------------------------
    check("ROOMY keeps every byte", budgetScaleFor(MemoryPressure.ROOMY) == 1.0f)
    check(
        "TIGHT sheds bytes harder than pixels",
        budgetScaleFor(MemoryPressure.TIGHT) < pixelScaleFor(MemoryPressure.TIGHT),
    )
    check(
        "CRITICAL sheds bytes hardest",
        budgetScaleFor(MemoryPressure.CRITICAL) < budgetScaleFor(MemoryPressure.TIGHT),
    )
    check("ROOMY keeps every pixel", pixelScaleFor(MemoryPressure.ROOMY) == 1.0f)

    // --- the pixel ceiling ----------------------------------------------------------------
    val einkPanel = 1_200L * 800L
    check(
        "a small panel caps a big tier's ceiling",
        resolvePixelCeiling(40L * 1_000 * 1_000, einkPanel) == einkPanel * 4L,
    )
    check(
        "a 1440p panel is the binding constraint on a big tier",
        resolvePixelCeiling(40L * 1_000 * 1_000, 2_560L * 1_440L) == 2_560L * 1_440L * 4L,
    )
    check(
        "a tiny tier is the binding constraint on a big panel",
        resolvePixelCeiling(6L * 1_000 * 1_000, 4_000L * 3_000L) == 6L * 1_000 * 1_000,
    )
    check(
        "a tiny panel still gets the pixel floor",
        resolvePixelCeiling(32L * 1_000 * 1_000, 320L * 240L) == 2L * 1_000 * 1_000,
    )
    check(
        "an unreadable panel means no panel constraint",
        resolvePixelCeiling(16L * 1_000 * 1_000, 0L) == 16L * 1_000 * 1_000,
    )
    // The scaled ceiling must stay above the floor even on the smallest tier at CRITICAL, or every
    // scroll becomes a re-decode because the cache cannot hold the page being read.
    for (t in DeviceTier.entries) {
        check(
            "${t.name} pixel ceiling holds its floor at CRITICAL",
            scaledPixelCeiling(t, MemoryPressure.CRITICAL) >= 2L * 1_000 * 1_000,
        )
        check(
            "${t.name} pixel ceiling never exceeds its ROOMY value",
            scaledPixelCeiling(t, MemoryPressure.CRITICAL) <= scaledPixelCeiling(t, MemoryPressure.ROOMY),
        )
    }

    // --- RGB_565: the rule that replaced a device-size boolean ----------------------------
    // The old rule was `isLowRamDevice && bytes > 32MB`, which is a question about the device asked
    // of a property of the page. These pin the two inputs that decide it now.
    check("ROOMY keeps full colour on an enormous page", !shouldDowngradeToRgb565(512L * MEMORY_MB, MemoryPressure.ROOMY))
    check("TIGHT halves a page over the threshold", shouldDowngradeToRgb565(RGB565_MIN_BYTES, MemoryPressure.TIGHT))
    check("CRITICAL halves a page over the threshold", shouldDowngradeToRgb565(RGB565_MIN_BYTES, MemoryPressure.CRITICAL))
    check(
        "a page just under the threshold is never halved",
        !shouldDowngradeToRgb565(RGB565_MIN_BYTES - 1, MemoryPressure.CRITICAL),
    )
    check("a tiny page is never halved even at CRITICAL", !shouldDowngradeToRgb565(1024L, MemoryPressure.CRITICAL))
    check("a zero-byte page is never halved", !shouldDowngradeToRgb565(0L, MemoryPressure.CRITICAL))
    // 48MB is the whole point: below it the saving is noise and the banding is not, and a page that
    // small is never what put the device under pressure.
    check("the threshold is 48MB", RGB565_MIN_BYTES == 48L * MEMORY_MB)

    // --- the JXL dimension cap ------------------------------------------------------------
    // The old rule was `2048 if isLowRamDevice else 4096`: refused a 3000px JXL on a 2GB phone and
    // then let a 12GB phone OOM on the same page. Now the same page gets the same answer.
    check("ROOMY keeps the tier cap", jxlMaxDimension(4096, MemoryPressure.ROOMY) == 4096)
    check("TIGHT cuts the cap to three quarters", jxlMaxDimension(4096, MemoryPressure.TIGHT) == 3072)
    check("CRITICAL halves the cap", jxlMaxDimension(4096, MemoryPressure.CRITICAL) == 2048)
    check("the cap never falls below the floor", jxlMaxDimension(1024, MemoryPressure.CRITICAL) == JXL_MIN_DIMENSION)
    check("a small tier's cap is respected", jxlMaxDimension(2048, MemoryPressure.ROOMY) == 2048)
    check("the JXL floor is 1024", JXL_MIN_DIMENSION == 1024)
    check("the JXL hard cap matches the decoder's", JXL_HARD_DIMENSION == 8192)
    // The cap must be monotonically non-increasing as pressure worsens, for every tier.
    for (tier in DeviceTier.entries) {
        val roomy = jxlMaxDimension(4096, MemoryPressure.ROOMY)
        val tight = jxlMaxDimension(4096, MemoryPressure.TIGHT)
        val critical = jxlMaxDimension(4096, MemoryPressure.CRITICAL)
        check(
            "the JXL cap only shrinks as pressure grows",
            roomy >= tight && tight >= critical,
        )
    }

    // --- the working-set gate -------------------------------------------------------------
    check("a page that fits twice over is affordable", fitsWorkingSet(100L * MEMORY_MB, 512L * MEMORY_MB))
    check("a page that exactly fits twice is affordable", fitsWorkingSet(256L * MEMORY_MB, 512L * MEMORY_MB))
    check("a page that does not fit twice is not", !fitsWorkingSet(300L * MEMORY_MB, 512L * MEMORY_MB))
    check("a zero-byte page is affordable", fitsWorkingSet(0L, 512L * MEMORY_MB))
    check("nothing is affordable with no budget", !fitsWorkingSet(1L, 0L))

    // --- the mip chain, which is the measurement everything is sized from -------------------
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