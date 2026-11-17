// KMK -->
package tachiyomi.core.common.util.system

// The device's memory *size class*, which is the one answer every subsystem shares.
//
// This file deliberately owns only the vocabulary and the fold. The arithmetic that turns a class
// plus a live reading into byte ceilings lives beside it in `DeviceMemoryMath.kt`, and the part that
// needs Android - reading `ActivityManager` and publishing a snapshot - lives in `AppMemoryMonitor`.
//
// Why this is not still inside the reader: the WebGPU viewer was the first subsystem to need a
// *live* answer instead of `totalMem < 3GB`, and it built the right one. But Coil's image cache,
// the JXL dimension cap, the RGB_565 decision, the MTL/WebGPU RAM gates and the decode-worker count
// all asked the same question and each answered it with its own copy of the same static boolean. A
// device therefore behaved differently depending on which screen it was on. One class, one set of
// thresholds, one live reading - and every consumer reads the snapshot rather than asking again.
//
// No Android import in this file or `DeviceMemoryMath.kt`: `DeviceMemoryHarness` compiles both on a
// plain JVM and asserts the fold, because each line of it is a decision a user can see.

/** Bytes in one mebibyte, as every figure in these two files is expressed. */
internal const val MEMORY_MB = 1024L * 1024L

/**
 * Used only when `getMemoryInfo` throws or returns nothing.
 *
 * A mid-range device rather than the smallest one: a failed read must not silently demote a
 * flagship to TINY (fewer resident pages, a smaller pixel ceiling, one decode worker) for the rest
 * of the session. The value this replaced read `4L * 1024L * 1024L` - four *mebibytes*, which is
 * not a device that exists, so every fallback landed on the smallest tier.
 */
internal const val DEFAULT_TOTAL_MEM_BYTES = 4L * 1024 * MEMORY_MB

/**
 * Size class of the device.
 *
 * KMK --> Each entry carries the *reader's* per-tier ceilings, because an enum's entries are its
 * declaration and a tier that owns its own budgets cannot be extended from another module. This enum
 * used to live in the reader with exactly these numbers; it moved here so the whole app shares one
 * answer to "what is this device", and the ceilings came with it because they cannot live anywhere
 * else.
 *
 * The declaration order is load-bearing: [deviceTierFor] and every call site compare with `<=`, so
 * TINY must be the smallest and XLARGE the largest.
 */
enum class DeviceTier(
    /** Resident decoded-page budget, before pressure scaling. */
    val maxDecodedPagesBytes: Long,
    /** Tile-cache budget, before pressure scaling. */
    val maxTileCacheBytes: Long,
    /** Largest single page admitted, before pressure scaling. */
    val maxPageBytesBytes: Long,
    /** Strictest JXL ceiling, before pressure scaling. */
    val maxJxlPageBytes: Long,
    /** Largest decoded area uploaded at full resolution, before pressure scaling. */
    val maxDecodedPixels: Long,
) {
    /**
     * Below 2GB. A webtoon segment is ~47MB of texture; these cannot hold many. The pixel ceiling
     * keeps a high-resolution comic's page (a 4000x6000 scan is 24MP / 128MB of GPU textures before
     * its mip chain) from being a one-page device loss.
     */
    TINY(96L * MEMORY_MB, 16L * MEMORY_MB, 24L * MEMORY_MB, 12L * MEMORY_MB, 6L * 1_000_000),

    /** 2-3GB - the old `isLowRamDevice` boundary. */
    SMALL(160L * MEMORY_MB, 24L * MEMORY_MB, 40L * MEMORY_MB, 16L * MEMORY_MB, 10L * 1_000_000),

    /** 3-6GB - the common modern phone. */
    MEDIUM(288L * MEMORY_MB, 48L * MEMORY_MB, 64L * MEMORY_MB, 24L * MEMORY_MB, 16L * 1_000_000),

    /** 6-12GB. */
    LARGE(448L * MEMORY_MB, 64L * MEMORY_MB, 80L * MEMORY_MB, 32L * MEMORY_MB, 24L * 1_000_000),

    /** Above 12GB. 32MP covers anything a display can resolve with zoom headroom to spare. */
    XLARGE(640L * MEMORY_MB, 64L * MEMORY_MB, 96L * MEMORY_MB, 40L * MEMORY_MB, 32L * 1_000_000),
}

/**
 * The device as it was built, folded into a size class.
 *
 * Three inputs, because the size alone is not the constraint:
 *
 * - the RAM count, which is the coarse bucket;
 * - the vendor's own low-RAM mark, which is a statement about the whole device and worse news than
 *   the number suggests - a 4GB phone the OEM marked low-RAM is folded down to MEDIUM;
 * - the ABI, because a 32-bit userspace shares ~3GB across the Java heap, the native decode
 *   buffers and the GPU's own allocations on a unified-memory device. That is the spike that loses
 *   the device (field report: armeabi-v7a losing its Vulkan device two pages into a comic), so
 *   32-bit is capped at SMALL - which also pins decode parallelism to one worker.
 */
fun deviceTierFor(totalMemBytes: Long, isLowRam: Boolean, is64Bit: Boolean): DeviceTier {
    val tier = when {
        totalMemBytes < 2L * 1024 * MEMORY_MB -> DeviceTier.TINY
        totalMemBytes < 3L * 1024 * MEMORY_MB -> DeviceTier.SMALL
        totalMemBytes < 6L * 1024 * MEMORY_MB -> DeviceTier.MEDIUM
        totalMemBytes < 12L * 1024 * MEMORY_MB -> DeviceTier.LARGE
        else -> DeviceTier.XLARGE
    }
    // A vendor low-RAM mark on an otherwise large device is still a low-RAM device.
    val lowRamTier = if (isLowRam && tier > DeviceTier.MEDIUM) DeviceTier.MEDIUM else tier
    return if (!is64Bit && lowRamTier > DeviceTier.SMALL) DeviceTier.SMALL else lowRamTier
}

/** Total RAM as a size class, for callers that have no `MemoryInfo` yet. */
fun deviceTierOfTotalMem(totalMemBytes: Long): DeviceTier =
    deviceTierFor(if (totalMemBytes > 0L) totalMemBytes else DEFAULT_TOTAL_MEM_BYTES, false, true)
