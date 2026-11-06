// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import exh.yakuyomi.DeviceMemory
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * File-level, not a companion constant on two different classes.
 *
 * Both `Budget` and `DeviceTier` need this number and both are nested inside this class, so two
 * separate `companion object { const val MB }` declarations would make every reference ambiguous
 * about which one it resolves to.
 */
private const val MB = 1024L * 1024L

/** Used only when `getMemoryInfo` throws or returns nothing - never seen, but must not be zero. */
private const val DEFAULT_TOTAL_MEM_BYTES = 4L * 1024L * 1024L

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
 */
private const val MIN_DECODED_PIXELS = 2L * 1000 * 1000

/**
 * How much memory the reader may hold, derived from the device *and* from what is free right now.
 *
 * Replaces the old `isLowRamDevice` boolean, which was `totalMem < 3GB` and so had two answers for
 * every question. It could not tell a 12GB phone with 400MB free from a 12GB phone with 8GB free,
 * which is the distinction that actually decides whether a page decodes or throws - and it could
 * not tell a 4GB phone having a good day from a 4GB phone one split-segment decode away from an OOM.
 *
 * Two halves, deliberately separated:
 *
 * - [Budget] is immutable and precomputed. Every reader of it is on a hot path - `cacheSize` is
 *   read by the render walk and `preloadAhead` by the decode thread - and `ActivityManager
 *   .getMemoryInfo` is a binder call. So the snapshot is published by [sample] and the getters
 *   only ever read a volatile field.
 * - [sample] is the only place that talks to the system, and it is called on a timer by
 *   `WebGpuMemoryMonitor`, not from a getter.
 *
 * **Headroom, not `availMem`.** `availMem` counts swap, so a device that has been swapping for an
 * hour reports plenty available and then gets killed. `MemoryInfo.threshold` is where the system
 * starts reclaiming, so `availMem - threshold` is the working set we are genuinely safe claiming.
 * That difference is what makes this react to a memory spike instead of to a device's spec sheet.
 */
internal class WebGpuMemoryBudget(context: Context) {

    private val activityManager = context.getSystemService<ActivityManager>()

    /** The device as it was built. Never changes, so resolved once. */
    val totalMemBytes: Long = readOnce { it.totalMem }
        ?: DEFAULT_TOTAL_MEM_BYTES

    /**
     * The app's own Java heap ceiling, in bytes - the one number the runtime enforces on us.
     *
     * Read straight from `Runtime` rather than `getMemoryClass()`, which reports the same thing in
     * MB and rounds against us at the small end.
     */
    private val javaHeapCeilingBytes: Long = Runtime.getRuntime().maxMemory()

    /**
     * Whether the platform classes this device as a low-RAM one.
     *
     * Deliberately kept *alongside* the size tiers rather than folded into them: it is a vendor
     * statement about the whole device, and a 4GB phone the OEM marked low-RAM is worse news than
     * its RAM count suggests.
     */
    val isLowRam: Boolean = runCatching {
        activityManager?.isLowRamDevice == true
    }.getOrDefault(false)

    /**
     * Whether the process runs a 64-bit ABI. A 32-bit device keeps the WebGPU viewer (that
     * decision is deliberate) but its whole memory story shrinks: see [DeviceMemory.is64Bit]'s
     * use in [deviceTierFor] and the address-space argument there.
     */
    val is64Bit: Boolean = DeviceMemory.is64Bit()

    /** The device's size class, resolved once. */
    val deviceTier: DeviceTier = deviceTierFor(totalMemBytes, isLowRam, is64Bit)

    /**
     * What the panel can actually show, as a pixel area, read once: the larger display side
     * times the smaller, so orientation drops out. A page holds no more viewable detail than
     * the screen it is shown on - the rest is memory spent on texels nothing can resolve, which
     * on an e-ink panel (about a megapixel) or a small tablet is most of a high-resolution scan.
     * [DISPLAY_AREA_HEADROOM] multiplies in zoom room: twice the linear scale on both axes is
     * the point where more source resolution stops being visible.
     */
    val displayPixels: Long = runCatching {
        val dm = context.resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels).toLong()
        val h = max(1, min(dm.widthPixels, dm.heightPixels)).toLong()
        w * h
    }.getOrDefault(0L)

    /** 2x linear zoom headroom on each axis = 4x the display's pixel area. */
    private val displayAreaHeadroom = 4L

    /**
     * The static decoded-pixel ceiling: the device tier's figure, capped by what the display can
     * resolve with zoom headroom. Both [Budget] factories scale this by live pressure.
     */
    val maxDecodedPixelsBase: Long =
        if (displayPixels > 0L) {
            minOf(deviceTier.maxDecodedPixels, displayPixels * displayAreaHeadroom)
                .coerceAtLeast(MIN_DECODED_PIXELS)
        } else {
            deviceTier.maxDecodedPixels
        }

    /**
     * Published snapshot. Written only by [sample]; every getter is a volatile read.
     *
     * Starts at the device's own ceiling so the very first frames - before the monitor's first
     * tick - already size themselves for this device rather than for an assumed one.
     */
    @Volatile
    private var current: Budget = Budget.deviceCeiling(deviceTier, maxDecodedPixelsBase)

    /** Re-reads the system and republishes. The only method that does I/O. */
    fun sample(): Budget {
        val am = activityManager
        val info = ActivityManager.MemoryInfo()
        // Checked rather than inferred from runCatching: `am?.getMemoryInfo(info)` on a null
        // receiver throws nothing, so isSuccess would be true over an untouched, all-zero
        // MemoryInfo - and availMem=0 with threshold=0 reads as headroom 0, i.e. CRITICAL forever.
        val read = am != null && runCatching { am.getMemoryInfo(info) }.isSuccess
        val tier = deviceTier
        val next = if (read) {
            Budget.from(info, tier, javaHeapCeilingBytes, isLowRam, maxDecodedPixelsBase)
        } else {
            // A failed read must not shrink the budget to nothing, nor leave it at yesterday's
            // pressure forever. Hold the device ceiling until the next successful sample.
            Budget.deviceCeiling(tier, maxDecodedPixelsBase)
        }
        current = next
        return next
    }

    /** The live snapshot. Cheap; safe from any thread. */
    fun budget(): Budget = current

    /** How much of the device's RAM the reader may hold decoded, right now. */
    fun decodedPagesBytes(): Long = current.decodedPagesBytes

    /** Largest single page accepted before the decoder is asked to refuse it outright. */
    fun maxPageBytes(): Long = current.maxPageBytes

    /**
     * The JXL ceiling, which is well below [maxPageBytes] because JXL decoding costs markedly more
     * per byte than the formats above it - the same page that is comfortable as JPEG is an OOM as
     * JXL. Used to be a flat 16MB applied only on "low RAM" devices.
     */
    fun jxlPageBytes(): Long = current.jxlPageBytes

    /**
     * Largest decoded pixel area uploaded at full resolution right now; a page above it is
     * downsampled at decode. See [Budget.maxDecodedPixels] for why this is a measurement the
     * budget owns rather than a constant.
     */
    fun maxDecodedPixels(): Long = current.maxDecodedPixels

    /**
     * Whether a page of [bytes] plus an equal transient working set still fits the decoded budget.
     *
     * Gates work that holds the page's bytes while comparing against something else - the
     * dual-page height match. Asking "is this device low RAM" was the wrong question: it is answer-
     * able before the page exists, while this is not.
     */
    fun canAffordWorkingSet(bytes: Long): Boolean = bytes * 2L <= current.decodedPagesBytes

    /**
     * Whether decoded pages should be dropped from outside `onTrimMemory`.
     *
     * The tier alone is the old behaviour, kept because a device that is merely small still needs
     * its residency trimmed on a real signal. What is new is the live half.
     */
    fun shouldShrinkDecodedPages(): Boolean =
        current.pressure.ordinal >= Pressure.TIGHT.ordinal || isLowRam || deviceTier == DeviceTier.TINY

    fun describe(): String = current.describe()

    private fun readOnce(get: (ActivityManager.MemoryInfo) -> Long): Long? {
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            activityManager?.getMemoryInfo(info)
            if (info.totalMem <= 0L) null else get(info)
        }.getOrNull()
    }

    /** Immutable, precomputed, and the only thing hot paths ever touch. */
    internal data class Budget(
        val pressure: Pressure,
        /** Claimable working set: what the system says is free *above* where it starts killing. */
        val headroomBytes: Long,
        val decodedPagesBytes: Long,
        val tileCacheBytes: Long,
        val maxPageBytes: Long,
        val jxlPageBytes: Long,
        /**
         * Largest decoded pixel area the device uploads at full resolution, in pixels.
         *
         * A page above this is downsampled at decode (WebGpuDecodePipeline) rather than
         * uploaded whole: the mip chain multiplies the cost by 4/3, so an uncapped 64MP page
         * is ~600MB of GPU textures — the shape that presents as
         * `VK_ERROR_OUT_OF_DEVICE_MEMORY` device loss two pages into a high-resolution comic.
         * The ceiling tracks the budget's own philosophy: device tier decides the default,
         * live pressure moves it, and a floor keeps the cap from starving the display itself.
         * Quality therefore varies across the session exactly when memory does — the same
         * trade the byte ceilings already make.
         */
        val maxDecodedPixels: Long,
    ) {
        fun describe(): String =
            "pressure=$pressure headroom=${headroomBytes / MB}MB " +
                "pages=${decodedPagesBytes / MB}MB tiles=${tileCacheBytes / MB}MB " +
                "maxPage=${maxPageBytes / MB}MB px<=${maxDecodedPixels / 1_000_000L}MP"

        companion object {
            /**
             * What a device gets before any live signal exists.
             *
             * The tier's own ceiling, unchanged: a reader that starts conservative has to be told
             * it may grow, and the monitor's first sample lands within a few seconds anyway.
             */
            fun deviceCeiling(tier: DeviceTier, pixelCeiling: Long): Budget = Budget(
                pressure = if (tier <= DeviceTier.SMALL) Pressure.TIGHT else Pressure.ROOMY,
                headroomBytes = 0L,
                decodedPagesBytes = tier.maxDecodedPagesBytes,
                tileCacheBytes = tier.maxTileCacheBytes,
                maxPageBytes = tier.maxPageBytesBytes,
                jxlPageBytes = tier.maxJxlPageBytes,
                maxDecodedPixels = pixelCeiling,
            )

            fun from(
                info: ActivityManager.MemoryInfo,
                tier: DeviceTier,
                javaHeapCeilingBytes: Long,
                isLowRam: Boolean,
                pixelCeiling: Long,
            ): Budget {
                val headroom = max(0L, info.availMem - info.threshold)
                val pressure = pressureOf(info, headroom, javaHeapCeilingBytes, isLowRam)

                // Scale the device's ceiling by how much room is actually left. A device at its
                // ceiling with a full headroom keeps the full ceiling; the same device one split
                // segment from an OOM gets a fraction of it.
                val scale = when (pressure) {
                    Pressure.ROOMY -> 1.0f
                    Pressure.TIGHT -> 0.55f
                    Pressure.CRITICAL -> 0.25f
                }
                // Gentler than the byte scale on purpose: a lower pixel ceiling is a visible
                // quality change, not just fewer cached pages, so pressure has to halve it
                // only when memory is genuinely critical - not on every ordinary tight day.
                val pixelScale = when (pressure) {
                    Pressure.ROOMY -> 1.0f
                    Pressure.TIGHT -> 0.75f
                    Pressure.CRITICAL -> 0.5f
                }
                return Budget(
                    pressure = pressure,
                    headroomBytes = headroom,
                    decodedPagesBytes = (tier.maxDecodedPagesBytes * scale).toLong()
                        .coerceAtLeast(MIN_DECODED_PAGES_BYTES),
                    tileCacheBytes = (tier.maxTileCacheBytes * scale).toLong()
                        .coerceAtLeast(MIN_TILE_CACHE_BYTES),
                    maxPageBytes = (tier.maxPageBytesBytes * scale).toLong()
                        .coerceAtLeast(MIN_MAX_PAGE_BYTES),
                    jxlPageBytes = (tier.maxJxlPageBytes * scale).toLong()
                        .coerceAtLeast(MIN_JXL_PAGE_BYTES),
                    maxDecodedPixels = (tier.maxDecodedPixels * pixelScale).toLong()
                        .coerceAtLeast(MIN_DECODED_PIXELS),
                )
            }

            private fun pressureOf(
                info: ActivityManager.MemoryInfo,
                headroomBytes: Long,
                javaHeapCeilingBytes: Long,
                isLowRam: Boolean,
            ): Pressure {
                // The system's own verdict, and the strongest signal there is.
                if (info.lowMemory) return Pressure.CRITICAL

                // Our Java heap is where the decode buffers live, so its exhaustion precedes the
                // system noticing. Measured against the runtime's ceiling, not the device's RAM.
                val rt = Runtime.getRuntime()
                val heapUsed = rt.totalMemory() - rt.freeMemory()
                if (javaHeapCeilingBytes > 0 && heapUsed > javaHeapCeilingBytes * HEAP_CRITICAL) {
                    return Pressure.CRITICAL
                }
                if (javaHeapCeilingBytes > 0 && heapUsed > javaHeapCeilingBytes * HEAP_TIGHT) {
                    return Pressure.TIGHT
                }

                if (headroomBytes < tierHeadroom(DeviceTier.TINY)) return Pressure.CRITICAL
                if (headroomBytes < tierHeadroom(DeviceTier.MEDIUM)) return Pressure.TIGHT
                if (isLowRam && headroomBytes < tierHeadroom(DeviceTier.LARGE)) return Pressure.TIGHT
                return Pressure.ROOMY
            }

            private fun tierHeadroom(tier: DeviceTier) = when (tier) {
                DeviceTier.TINY -> 256L * MB
                DeviceTier.SMALL -> 512L * MB
                DeviceTier.MEDIUM -> 1_024L * MB
                DeviceTier.LARGE -> 2_048L * MB
                DeviceTier.XLARGE -> 3_072L * MB
            }
        }
    }

    /**
     * Size class of the device.
     *
     * A coarse bucket is the point - the live signal is what actually moves the numbers, and
     * finer tiers here would imply a precision the static half cannot deliver.
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

    internal enum class Pressure { ROOMY, TIGHT, CRITICAL }

    private companion object {
        fun deviceTierFor(totalMemBytes: Long, isLowRam: Boolean, is64Bit: Boolean): DeviceTier {
            val tier = when {
                totalMemBytes < 2L * 1024 * MB -> DeviceTier.TINY
                totalMemBytes < 3L * 1024 * MB -> DeviceTier.SMALL
                totalMemBytes < 6L * 1024 * MB -> DeviceTier.MEDIUM
                totalMemBytes < 12L * 1024 * MB -> DeviceTier.LARGE
                else -> DeviceTier.XLARGE
            }
            // A vendor low-RAM mark on an otherwise large device is still a low-RAM device.
            val lowRamTier = if (isLowRam && tier > DeviceTier.MEDIUM) DeviceTier.MEDIUM else tier
            // So is a 32-bit userspace, for a different reason: the whole address space - Java
            // heap, native decode buffers, and the GPU's own allocations on a unified-memory
            // device - shares ~3GB, so the byte budgets a 64-bit MEDIUM/LARGE device comfortably
            // holds are the spike that loses the device there. The field report behind this was
            // armeabi-v7a losing its Vulkan device two pages into a comic. 32-bit is capped at
            // SMALL, which also pins decode parallelism to one worker (decodeParallelism keys
            // on the tier), halving the transient decode spike.
            return if (!is64Bit && lowRamTier > DeviceTier.SMALL) DeviceTier.SMALL else lowRamTier
        }
    }
}

/**
 * What a `width` x `height` RGBA image occupies on the GPU once its mip chain exists.
 *
 * The chain sums `1/4 + 1/16 + ...` of the base, which is `1/3` more, so the total is `4/3` of
 * the base. Measuring the base alone undercounts by a third on every page - small enough to look
 * like rounding, large enough that a cache sized on it overflows.
 */
internal fun uploadedImageBytes(width: Int, height: Int): Long {
    if (width <= 0 || height <= 0) return 0L
    val base = width.toLong() * height.toLong() * 4L
    return base + base / 3L
}

internal fun WebGpuViewer.logMemoryBudget(reason: String) {
    logcat(LogPriority.INFO) { "MemBudget [$reason] ${memoryBudget.describe()}" }
}
// KMK <--
