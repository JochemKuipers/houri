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

// KMK --> `DEFAULT_TOTAL_MEM_BYTES`, `HEAP_TIGHT` and `HEAP_CRITICAL` moved to
// `core:common`'s DeviceMemoryClass.kt / DeviceMemoryMath.kt with the rest of that arithmetic. They
// were the three constants only the pressure/fold code read, and leaving them here would be a second
// copy of a value that must not drift.

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

internal typealias MemoryPressure = tachiyomi.core.common.util.system.MemoryPressure

// KMK --> The fold and the pressure/scaling arithmetic moved to
// `tachiyomi.core.common.util.system.DeviceMemoryClass` / `.DeviceMemoryMath`, because every consumer
// outside the reader needs the same answers. What remains in this file is the reader's own per-tier
// ceilings (above) and the pixel/measurement helpers below.
//
// These are aliases, not copies: two implementations of one rule is how they drift. `MemoryBudgetHarness`
// still compiles *this* file and asserts through the aliases, which is the point - the reader is
// proven against the same arithmetic the rest of the app runs.
internal typealias DeviceTier = tachiyomi.core.common.util.system.DeviceTier

/** The fold, aliased so the reader's call sites read unchanged. */
internal fun deviceTierFor(totalMemBytes: Long, isLowRam: Boolean, is64Bit: Boolean): DeviceTier =
    tachiyomi.core.common.util.system.deviceTierFor(totalMemBytes, isLowRam, is64Bit)

/** Total RAM as a size class, for the one caller that has no `MemoryInfo` yet. */
internal fun deviceTierOfTotalMem(totalMemBytes: Long): DeviceTier =
    tachiyomi.core.common.util.system.deviceTierOfTotalMem(totalMemBytes)

/**
 * How much room is left, measured as headroom rather than `availMem`.
 *
 * KMK --> Aliased for the same reason as [deviceTierFor]: this rule is shared with every other
 * consumer of the snapshot, and a second implementation here is how the reader would come to
 * disagree with the cache sizes computed from it.
 */
internal fun memoryHeadroom(availMem: Long, threshold: Long): Long =
    tachiyomi.core.common.util.system.memoryHeadroom(availMem, threshold)

/**
 * The pressure reading, strongest signal first.
 *
 * KMK --> Aliased; see [memoryHeadroom]. The system's own `lowMemory` verdict wins, then the Java
 * heap against the runtime ceiling, then headroom thresholds.
 */
internal fun memoryPressureOf(
    systemLowMemory: Boolean,
    headroomBytes: Long,
    heapUsedBytes: Long,
    javaHeapCeilingBytes: Long,
    isLowRam: Boolean,
): MemoryPressure = tachiyomi.core.common.util.system.memoryPressureOf(
    systemLowMemory = systemLowMemory,
    headroomBytes = headroomBytes,
    heapUsedBytes = heapUsedBytes,
    javaHeapCeilingBytes = javaHeapCeilingBytes,
    isLowRam = isLowRam,
)

/** How much of the device's ceiling survives [pressure] for the *byte* budgets. */
internal fun budgetScaleFor(pressure: MemoryPressure): Float =
    tachiyomi.core.common.util.system.budgetScaleFor(pressure)

/** How much of the tier's pixel ceiling survives [pressure]. */
internal fun pixelScaleFor(pressure: MemoryPressure): Float =
    tachiyomi.core.common.util.system.pixelScaleFor(pressure)

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
