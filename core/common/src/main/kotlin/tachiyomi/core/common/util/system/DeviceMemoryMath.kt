// KMK -->
package tachiyomi.core.common.util.system

// The arithmetic that turns a device's memory *class* into byte ceilings and budgets.
//
// No Android import, on purpose: `DeviceMemoryHarness` compiles this file on a plain JVM and asserts
// every figure here, because each of them is a decision a user can see - how many pages stay
// resident, how large a decoded image may be before it is downsampled, how many fetches run at once.
//
// What lives elsewhere: the class vocabulary and the fold (`DeviceMemoryClass.kt`), and the reading of
// `ActivityManager` plus the published snapshot (`AppMemoryMonitor.kt`). Anything that needs Android
// cannot be asserted offline, which is why the split is drawn exactly there.

// --- pressure -------------------------------------------------------------------------------

/**
 * How much room the device has *right now*.
 *
 * Ordered from most to least room, and compared with `<=`, so "at least as good as ROOMY" is the
 * recovery test and the ordinal order is part of the contract.
 */
enum class MemoryPressure { ROOMY, TIGHT, CRITICAL }

/**
 * How much room is left, measured as headroom rather than `availMem`.
 *
 * `availMem` counts swap, so a device that has been swapping for an hour reports plenty available
 * and then gets killed. `MemoryInfo.threshold` is where the system starts reclaiming, so
 * `availMem - threshold` is the working set we are genuinely safe claiming. That difference is what
 * makes this react to a memory *spike* instead of to a device's spec sheet.
 */
fun memoryHeadroom(availMem: Long, threshold: Long): Long = maxOf(0L, availMem - threshold)

/** Java-heap fractions at which the runtime, not the system, is the constraint. */
private const val HEAP_TIGHT = 0.70
private const val HEAP_CRITICAL = 0.88

/**
 * The pressure reading, strongest signal first.
 *
 * The system's own `lowMemory` verdict wins outright. Then the Java heap, because the decode buffers
 * live there and its exhaustion precedes the system noticing - measured against the runtime's
 * ceiling rather than the device's RAM. Only then the headroom thresholds, where a low-RAM device is
 * held to a stricter bar than a large one.
 */
fun memoryPressureOf(
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

    if (headroomBytes < headroomFor(DeviceTier.TINY)) return MemoryPressure.CRITICAL
    if (headroomBytes < headroomFor(DeviceTier.MEDIUM)) return MemoryPressure.TIGHT
    if (isLowRam && headroomBytes < headroomFor(DeviceTier.LARGE)) return MemoryPressure.TIGHT
    return MemoryPressure.ROOMY
}

/** Headroom below which each tier starts to feel the squeeze. */
private fun headroomFor(tier: DeviceTier): Long = when (tier) {
    DeviceTier.TINY -> 256L * MEMORY_MB
    DeviceTier.SMALL -> 512L * MEMORY_MB
    DeviceTier.MEDIUM -> 1_024L * MEMORY_MB
    DeviceTier.LARGE -> 2_048L * MEMORY_MB
    DeviceTier.XLARGE -> 3_072L * MEMORY_MB
}

// --- the tier's own ceilings ----------------------------------------------------------------

/**
 * What each tier may hold, and how large a single image it will accept.
 *
 * `decodedBytes` is the resident decoded-page budget; `imageCacheBytes` is the heap Coil may use;
 * `maxImageBytes` is the largest single image admitted at all; `jxlBytes` is the stricter JXL
 * ceiling; `maxPixels` caps the decoded area; `fetchParallelism` is the image-loader pool.
 */
internal data class TierCeilings(
    val decodedBytes: Long,
    val imageCacheBytes: Long,
    val maxImageBytes: Long,
    val jxlBytes: Long,
    val maxPixels: Long,
    val fetchParallelism: Int,
    /**
     * The tier's JXL *dimension* cap, which is not derivable from [maxPixels].
     *
     * A pixel area and a linear dimension are different quantities: 6MP could be 2000x3000 or
     * 2449x2449, and the decoder's sampler works in whole-number halvings of the *larger side*. So
     * the cap that governs a JXL decode has to be a dimension the tier states directly, and this is
     * it. Keeping it beside the byte ceilings is what stops it drifting away from them.
     */
    val jxlDimension: Int,
)

/**
 * The per-tier figures.
 *
 * The progression is deliberately steep at the bottom: a 2GB device cannot hold eight webtoon
 * segments (~47MB of texture each), and the pixel ceiling is what keeps a 24MP scan from being a
 * one-page device loss there. XLARGE's 32MP covers anything a display can resolve with zoom
 * headroom to spare.
 */
internal fun ceilingsFor(tier: DeviceTier): TierCeilings = when (tier) {
    DeviceTier.TINY -> TierCeilings(
        decodedBytes = 96L * MEMORY_MB,
        imageCacheBytes = 32L * MEMORY_MB,
        maxImageBytes = 24L * MEMORY_MB,
        jxlBytes = 12L * MEMORY_MB,
        maxPixels = 6L * 1_000 * 1_000,
        jxlDimension = 2048,
        fetchParallelism = 3,
    )

    DeviceTier.SMALL -> TierCeilings(
        decodedBytes = 160L * MEMORY_MB,
        imageCacheBytes = 48L * MEMORY_MB,
        maxImageBytes = 40L * MEMORY_MB,
        jxlBytes = 16L * MEMORY_MB,
        maxPixels = 10L * 1_000 * 1_000,
        jxlDimension = 3072,
        fetchParallelism = 3,
    )

    DeviceTier.MEDIUM -> TierCeilings(
        decodedBytes = 288L * MEMORY_MB,
        imageCacheBytes = 72L * MEMORY_MB,
        maxImageBytes = 64L * MEMORY_MB,
        jxlBytes = 24L * MEMORY_MB,
        maxPixels = 16L * 1_000 * 1_000,
        jxlDimension = 4096,
        fetchParallelism = 4,
    )

    DeviceTier.LARGE -> TierCeilings(
        decodedBytes = 448L * MEMORY_MB,
        imageCacheBytes = 96L * MEMORY_MB,
        maxImageBytes = 80L * MEMORY_MB,
        jxlBytes = 32L * MEMORY_MB,
        maxPixels = 24L * 1_000 * 1_000,
        jxlDimension = 6144,
        fetchParallelism = 6,
    )

    DeviceTier.XLARGE -> TierCeilings(
        decodedBytes = 640L * MEMORY_MB,
        imageCacheBytes = 128L * MEMORY_MB,
        maxImageBytes = 96L * MEMORY_MB,
        jxlBytes = 40L * MEMORY_MB,
        maxPixels = 32L * 1_000 * 1_000,
        jxlDimension = 8192,
        fetchParallelism = 6,
    )
}

// --- floors ---------------------------------------------------------------------------------

/**
 * Floors. Below these, holding fewer pages stops helping and starts hurting: the cache cannot hold
 * the visible page plus a neighbour, so every scroll becomes a re-decode.
 */
private const val MIN_DECODED_BYTES = 64L * MEMORY_MB
private const val MIN_IMAGE_CACHE_BYTES = 16L * MEMORY_MB
private const val MIN_MAX_IMAGE_BYTES = 24L * MEMORY_MB
private const val MIN_JXL_BYTES = 8L * MEMORY_MB

/**
 * Pixel floor for the decoded-page ceiling: the 1440p-phone display itself is ~4.6MP, so anything
 * lower would downsample the page below what the screen shows even zoomed out. 2MP covers a 720p
 * screen at up to ~2x zoom and nothing smaller survives here.
 */
const val DECODED_PIXELS_FLOOR = 2L * 1_000 * 1_000

// --- scaling --------------------------------------------------------------------------------

/**
 * How much of the tier's ceiling survives [pressure] for the *byte* budgets.
 *
 * Halving at CRITICAL is aggressive on purpose: these budgets decide how many pages stay resident,
 * and at CRITICAL the honest answer is "the visible page and whatever else fits".
 */
fun budgetScaleFor(pressure: MemoryPressure): Float = when (pressure) {
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
fun pixelScaleFor(pressure: MemoryPressure): Float = when (pressure) {
    MemoryPressure.ROOMY -> 1.0f
    MemoryPressure.TIGHT -> 0.75f
    MemoryPressure.CRITICAL -> 0.5f
}

/** One scaled byte budget with its floor, so the ceilings cannot drift apart. */
private fun scaledBudget(base: Long, scale: Float, floor: Long): Long =
    (base * scale).toLong().coerceAtLeast(floor)

/** The live byte budget: the tier's figures scaled by [pressure], floored. */
internal fun scaledCeilings(tier: DeviceTier, pressure: MemoryPressure): TierCeilings {
    val base = ceilingsFor(tier)
    val byteScale = budgetScaleFor(pressure)
    return base.copy(
        decodedBytes = scaledBudget(base.decodedBytes, byteScale, MIN_DECODED_BYTES),
        imageCacheBytes = scaledBudget(base.imageCacheBytes, byteScale, MIN_IMAGE_CACHE_BYTES),
        maxImageBytes = scaledBudget(base.maxImageBytes, byteScale, MIN_MAX_IMAGE_BYTES),
        jxlBytes = scaledBudget(base.jxlBytes, byteScale, MIN_JXL_BYTES),
        // Fetch parallelism is a *count*, not bytes: it drops with pressure but never below two,
        // because one in-flight fetch serialises the library's scroll behind a single request.
        fetchParallelism = when (pressure) {
            MemoryPressure.ROOMY -> base.fetchParallelism
            MemoryPressure.TIGHT -> (base.fetchParallelism * 0.75f).toInt().coerceAtLeast(2)
            MemoryPressure.CRITICAL -> 2
        },
    )
}

/** The pixel ceiling for [tier] at [pressure], before the panel constraint is applied. */
fun scaledPixelCeiling(tier: DeviceTier, pressure: MemoryPressure): Long =
    scaledBudget(ceilingsFor(tier).maxPixels, pixelScaleFor(pressure), DECODED_PIXELS_FLOOR)

// --- the panel constraint -------------------------------------------------------------------

/** 2x linear zoom headroom on each axis = 4x the display's pixel area. */
private const val DISPLAY_AREA_HEADROOM = 4L

/**
 * The pixel ceiling: the tier's figure, capped by what the panel can resolve with zoom headroom.
 *
 * A page holds no more viewable detail than the screen it is shown on - the rest is memory spent on
 * texels nothing can resolve, which on an e-ink panel (about a megapixel) or a small tablet is most
 * of a high-resolution scan. A non-positive [displayPixels] (the metrics read failed) means "no
 * panel constraint", so the tier's own ceiling stands.
 */
fun resolvePixelCeiling(tierCeiling: Long, displayPixels: Long): Long =
    if (displayPixels > 0L) {
        minOf(tierCeiling, displayPixels * DISPLAY_AREA_HEADROOM).coerceAtLeast(DECODED_PIXELS_FLOOR)
    } else {
        tierCeiling
    }

// --- per-image policy -----------------------------------------------------------------------

/**
 * Whether a page of [bytes] should be copied to RGB_565.
 *
 * RGB_565 halves the bytes of RGBA_8888 and is the right trade for exactly one kind of page: large,
 * and photographic or painted rather than flat colour. It is the *wrong* trade for a page with smooth
 * gradients (banding) or sharp line art on white (colour fringing on the antialiased edge), and both
 * of those describe most manga - which is why a small page is never worth it.
 *
 * The rule this replaces was `isLowRamDevice && bytes > 32MB`: a device-size question asked of a page
 * property, which is the same category error the reader already fixed for `maxPageBytes`. Two inputs
 * decide it instead - the live pressure, and the page's own size.
 */
fun shouldDowngradeToRgb565(bytes: Long, pressure: MemoryPressure): Boolean {
    // A ROOMY device keeps full colour whatever the page costs: the whole point of the budget is
    // that the trade is made only when memory is actually short.
    if (pressure == MemoryPressure.ROOMY) return false
    return bytes >= RGB565_MIN_BYTES
}

/**
 * Smallest page worth halving.
 *
 * Below this the saving is noise and the banding is not - and a page this small is not what puts a
 * device under pressure in the first place.
 */
const val RGB565_MIN_BYTES = 48L * 1024 * 1024

/**
 * The largest dimension a JXL may decode to, given the tier's [tierCap] and the live [pressure].
 *
 * JXL costs markedly more to decode per byte than JPEG, and its decoded bitmap is where a phone dies.
 * The cap is a *dimension* rather than a byte count because the decoder samples by power-of-two
 * factors, not by area.
 *
 * The value this replaces was `2048 if isLowRamDevice else 4096`: a device-size answer that refused a
 * 3000px JXL on a 2GB phone and then let a 12GB phone OOM on the same page. Here the same page gets
 * the same answer on both, because the tier and the pressure are the same in both cases.
 */
fun jxlMaxDimension(tierCap: Int, pressure: MemoryPressure): Int {
    val scaled = when (pressure) {
        MemoryPressure.ROOMY -> tierCap
        MemoryPressure.TIGHT -> (tierCap * 0.75f).toInt()
        MemoryPressure.CRITICAL -> (tierCap * 0.5f).toInt()
    }
    // A round floor: the sampler halves until it is under the cap, so a cap that is not a round
    // number just makes the last halving land further below than it needs to.
    return scaled.coerceAtLeast(JXL_MIN_DIMENSION)
}

/** Never downsample a JXL below this, whatever the pressure - below it the page is a thumbnail. */
const val JXL_MIN_DIMENSION = 1024

/** Hard ceiling applied to every JXL regardless of tier, matching the decoder's own limit. */
const val JXL_HARD_DIMENSION = 8192

// --- measurement ----------------------------------------------------------------------------

/**
 * What a [width] x [height] RGBA image occupies once its mip chain exists.
 *
 * The chain sums 1/4 + 1/16 + ... of the base, which is 1/3 more, so the total is 4/3 of the base.
 * Measuring the base alone undercounts by a third on every page - small enough to look like rounding,
 * large enough that a cache sized on it overflows.
 */
fun uploadedImageBytes(width: Int, height: Int): Long {
    if (width <= 0 || height <= 0) return 0L
    val base = width.toLong() * height.toLong() * 4L
    return base + base / 3L
}

/**
 * Whether a page of [bytes] plus an equal transient working set still fits [decodedBytes].
 *
 * Gates work that holds the page's bytes while comparing against something else - the dual-page
 * height match. Asking "is this device low RAM" was the wrong question: it is answerable before the
 * page exists, while this is not.
 */
fun fitsWorkingSet(bytes: Long, decodedBytes: Long): Boolean = bytes * 2L <= decodedBytes
// KMK <--
