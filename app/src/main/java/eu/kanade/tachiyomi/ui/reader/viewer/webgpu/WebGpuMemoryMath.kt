// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// The device's memory arithmetic: which size class it is, how much room is left right now, and how
// the two combine into the byte and pixel ceilings the reader actually holds.
//
// Split out of `WebGpuMemoryBudget.kt` because every answer here is a decision the reader can see
// (how many pages stay resident, how large a decoded page may be before it is downsampled) and none
// of them need Android: the class keeps the `ActivityManager` reads and the published snapshot, and
// calls in here. `MemoryBudgetHarness` compiles this file on a plain JVM and asserts the fold that
// replaced `isLowRamDevice` - the subtle part being that a 32-bit userspace is capped at SMALL
// even on a large device, because its whole address space is shared with the GPU's allocations.

/** Bytes in one mebibyte, as every figure in this file is expressed. */
private const val MB = 1024L * 1024L

/**
 * Used only when `getMemoryInfo` throws or returns nothing.
 *
 * A mid-range device rather than the smallest one: a failed read must not silently demote a
 * flagship to the TINY tier (fewer resident pages, a smaller pixel ceiling, one decode worker) for
 * the rest of the session. The old value here read `4L * 1024L * 1024L` - four *mebibytes*, which
 * is not a device that exists, so every fallback landed on TINY; `MemoryBudgetHarness` pins it.
 */
private const val DEFAULT_TOTAL_MEM_BYTES = 4L * 1024L * 1024L * 1024L

/** Java-heap fractions at which the runtime, not the system, is the constraint. */
private const val HEAP_TIGHT = 0.70
private const val HEAP_CRITICAL = 0.88

/**
 * Floors. Below these, holding fewer pages stops helping and starts hurting: the cache cannot hold
 * the visible page plus a neighbour, so every scroll becomes a re-decode.
 */
private const val MIN_DECODED_PAGES_BYTES = 64L * MB
private const val MIN_TILE_CACHE_BYTES = 16L * MB
private const val MIN_MAX_PAGE_BYTES = 24L * MB
private const val MIN_JXL_PAGE_BYTES = 8L * MB

/**
 * Pixel floor for the decoded-page ceiling: the 1440p-phone display itself is ~4.6MP, so
 * anything lower would downsample the page below what the screen shows even zoomed out.
 * 2MP covers a 720p screen at up to ~2x zoom and nothing smaller survives here.
 *
 * Named without the `MIN_` prefix the byte floors use because it is also the floor the class
 * clamps a *panel-derived* ceiling to, not only the tier path's own minimum.
 */
internal const val DECODED_PIXELS_FLOOR = 2L * 1000 * 1000

/** 2x linear zoom headroom on each axis = 4x the display's pixel area. */
private const val DISPLAY_AREA_HEADROOM = 4L

/**
 * Size class of the device.
 *
 * A coarse bucket is the point - the live signal is what actually moves the numbers, and
 * finer tiers here would imply a precision the static half cannot deliver.
 *
 * The declaration order is load-bearing: `deviceTierFor` and the call sites compare with `<=`, so
 * TINY must be the smallest and XLARGE the largest.
 */
internal enum class DeviceTier(
    val maxDecodedPagesBytes: Long,
    val maxTileCacheBytes: Long,
    val maxPageBytesBytes: Long,
    val maxJxlPageBytes: Long,
    val maxDecodedPixels: Long,
) {
    /**
     * Below 2GB. A webtoon segment is ~47MB of texture; these cannot hold many. The pixel
     * ceiling keeps a high-resolution comic's page (a 4000x6000 scan is 24MP / 128MB of
     * GPU textures before its mip chain) from being a one-page device loss.
     */
    TINY(96L * MB, 16L * MB, 24L * MB, 12L * MB, 6L * 1000 * 1000),

    /** 2-3GB. The old `isLowRamDevice` boundary. */
    SMALL(160L * MB, 24L * MB, 40L * MB, 16L * MB, 10L * 1000 * 1000),

    /** 3-6GB - the common modern phone. */
    MEDIUM(288L * MB, 48L * MB, 64L * MB, 24L * MB, 16L * 1000 * 1000),

    /** 6-12GB. */
    LARGE(448L * MB, 64L * MB, 80L * MB, 32L * MB, 24L * 1000 * 1000),

    /** Above 12GB. 32MP covers anything a display can resolve with zoom headroom to spare. */
    XLARGE(640L * MB, 64L * MB, 96L * MB, 40L * MB, 32L * 1000 * 1000),
}

/**
 * How much room the device has *right now*.
 *
 * Ordered from most to least room, and compared with `<=`, so "at least as good as ROOMY" is the
 * recovery test and the ordinal order is part of the contract.
 */
internal enum class MemoryPressure { ROOMY, TIGHT, CRITICAL }

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
 *   32-bit is capped at SMALL - which also pins decode parallelism to one worker, since
 *   `decodeParallelism` keys on the tier.
 */
internal fun deviceTierFor(totalMemBytes: Long, isLowRam: Boolean, is64Bit: Boolean): DeviceTier {
    val tier = when {
        totalMemBytes < 2L * 1024 * MB -> DeviceTier.TINY
        totalMemBytes < 3L * 1024 * MB -> DeviceTier.SMALL
        totalMemBytes < 6L * 1024 * MB -> DeviceTier.MEDIUM
        totalMemBytes < 12L * 1024 * MB -> DeviceTier.LARGE
        else -> DeviceTier.XLARGE
    }
    // A vendor low-RAM mark on an otherwise large device is still a low-RAM device.
    val lowRamTier = if (isLowRam && tier > DeviceTier.MEDIUM) DeviceTier.MEDIUM else tier
    return if (!is64Bit && lowRamTier > DeviceTier.SMALL) DeviceTier.SMALL else lowRamTier
}

/** Total RAM as a size class, for the one caller that has no `MemoryInfo` yet. */
internal fun deviceTierOfTotalMem(totalMemBytes: Long): DeviceTier =
    deviceTierFor(if (totalMemBytes > 0L) totalMemBytes else DEFAULT_TOTAL_MEM_BYTES, false, true)

/**
 * The pixel ceiling: the tier's figure, capped by what the panel can resolve with zoom headroom.
 *
 * A page holds no more viewable detail than the screen it is shown on - the rest is memory spent
 * on texels nothing can resolve, which on an e-ink panel (about a megapixel) or a small tablet is
 * most of a high-resolution scan. A non-positive [displayPixels] (the metrics read failed) means
 * "no panel constraint", so the tier's own ceiling stands.
 */
internal fun resolvePixelCeiling(tierCeiling: Long, displayPixels: Long): Long =
    if (displayPixels > 0L) {
        minOf(tierCeiling, displayPixels * DISPLAY_AREA_HEADROOM).coerceAtLeast(DECODED_PIXELS_FLOOR)
    } else {
        tierCeiling
    }

/**
 * How much room is left, measured as headroom rather than `availMem`.
 *
 * `availMem` counts swap, so a device that has been swapping for an hour reports plenty available
 * and then gets killed. `MemoryInfo.threshold` is where the system starts reclaiming, so
 * `availMem - threshold` is the working set we are genuinely safe claiming. That difference is what
 * makes this react to a memory *spike* instead of to a device's spec sheet.
 */
internal fun memoryHeadroom(availMem: Long, threshold: Long): Long = maxOf(0L, availMem - threshold)

/**
 * The pressure reading, strongest signal first.
 *
 * The system's own `lowMemory` verdict wins outright. Then the Java heap, because the decode
 * buffers live there and its exhaustion precedes the system noticing - measured against the
 * runtime's ceiling rather than the device's RAM. Only then the headroom thresholds, where a
 * low-RAM device is held to a stricter bar than a large one.
 */
internal fun memoryPressureOf(
    systemLowMemory: Boolean,
    headroomBytes: Long,
    heapUsedBytes: Long,
    javaHeapCeilingBytes: Long,
    isLowRam: Boolean,
): MemoryPressure {
    if (systemLowMemory) return MemoryPressure.CRITICAL

    if (javaHeapCeilingBytes > 0 && heapUsedBytes > javaHeapCeilingBytes * HEAP_CRITICAL) {
        return MemoryPressure.CRITICAL
    }
    if (javaHeapCeilingBytes > 0 && heapUsedBytes > javaHeapCeilingBytes * HEAP_TIGHT) {
        return MemoryPressure.TIGHT
    }

    if (headroomBytes < tierHeadroom(DeviceTier.TINY)) return MemoryPressure.CRITICAL
    if (headroomBytes < tierHeadroom(DeviceTier.MEDIUM)) return MemoryPressure.TIGHT
    if (isLowRam && headroomBytes < tierHeadroom(DeviceTier.LARGE)) return MemoryPressure.TIGHT
    return MemoryPressure.ROOMY
}

private fun tierHeadroom(tier: DeviceTier): Long = when (tier) {
    DeviceTier.TINY -> 256L * MB
    DeviceTier.SMALL -> 512L * MB
    DeviceTier.MEDIUM -> 1_024L * MB
    DeviceTier.LARGE -> 2_048L * MB
    DeviceTier.XLARGE -> 3_072L * MB
}

/**
 * How much of the device's ceiling survives [pressure] for the *byte* budgets.
 *
 * Halving at CRITICAL is aggressive on purpose: those budgets decide how many pages stay resident,
 * and at CRITICAL the honest answer is "the visible page and whatever else fits".
 */
internal fun budgetScaleFor(pressure: MemoryPressure): Float = when (pressure) {
    MemoryPressure.ROOMY -> 1.0f
    MemoryPressure.TIGHT -> 0.55f
    MemoryPressure.CRITICAL -> 0.25f
}

/**
 * How much of the tier's pixel ceiling survives [pressure].
 *
 * Gentler than the byte scale on purpose: a lower pixel ceiling is a visible quality change, not
 * just fewer cached pages, so pressure has to halve it only when memory is genuinely critical -
 * not on every ordinary tight day.
 */
internal fun pixelScaleFor(pressure: MemoryPressure): Float = when (pressure) {
    MemoryPressure.ROOMY -> 1.0f
    MemoryPressure.TIGHT -> 0.75f
    MemoryPressure.CRITICAL -> 0.5f
}

/** One scaled byte budget with its floor, so the four ceilings cannot drift apart. */
internal fun scaledBudget(base: Long, scale: Float, floor: Long): Long =
    (base * scale).toLong().coerceAtLeast(floor)

internal fun scaledDecodedPagesBytes(tier: DeviceTier, pressure: MemoryPressure): Long =
    scaledBudget(tier.maxDecodedPagesBytes, budgetScaleFor(pressure), MIN_DECODED_PAGES_BYTES)

internal fun scaledTileCacheBytes(tier: DeviceTier, pressure: MemoryPressure): Long =
    scaledBudget(tier.maxTileCacheBytes, budgetScaleFor(pressure), MIN_TILE_CACHE_BYTES)

internal fun scaledMaxPageBytes(tier: DeviceTier, pressure: MemoryPressure): Long =
    scaledBudget(tier.maxPageBytesBytes, budgetScaleFor(pressure), MIN_MAX_PAGE_BYTES)

internal fun scaledJxlPageBytes(tier: DeviceTier, pressure: MemoryPressure): Long =
    scaledBudget(tier.maxJxlPageBytes, budgetScaleFor(pressure), MIN_JXL_PAGE_BYTES)

internal fun scaledDecodedPixels(tier: DeviceTier, pressure: MemoryPressure): Long =
    scaledBudget(tier.maxDecodedPixels, pixelScaleFor(pressure), DECODED_PIXELS_FLOOR)

/**
 * The pixel floor itself, for the one caller that scales a ceiling it computed rather than the
 * tier's (`Budget.from`, which starts from the panel-capped ceiling).
 */
internal fun scaledDecodedPixelsFloor(): Long = DECODED_PIXELS_FLOOR

/**
 * Whether a page of [bytes] plus an equal transient working set still fits [decodedPagesBytes].
 *
 * Gates work that holds the page's bytes while comparing against something else - the dual-page
 * height match. Asking "is this device low RAM" was the wrong question: it is answerable before the
 * page exists, while this is not.
 *
 * Named without the `canAfford` prefix the class's own method uses, because a top-level function and
 * a member of the same name collide inside the class: the member wins and the call resolves to
 * itself. (That was a real compile error here, caught by the offline check rather than by a build.)
 */
internal fun fitsWorkingSet(bytes: Long, decodedPagesBytes: Long): Boolean =
    bytes * 2L <= decodedPagesBytes

/**
 * What a `width` x `height` RGBA image occupies on the GPU once its mip chain exists.
 *
 * The chain sums 1/4 + 1/16 + ... of the base, which is 1/3 more, so the total is 4/3 of
 * the base. Measuring the base alone undercounts by a third on every page - small enough to look
 * like rounding, large enough that a cache sized on it overflows.
 */
internal fun uploadedImageBytes(width: Int, height: Int): Long {
    if (width <= 0 || height <= 0) return 0L
    val base = width.toLong() * height.toLong() * 4L
    return base + base / 3L
}
// KMK <--