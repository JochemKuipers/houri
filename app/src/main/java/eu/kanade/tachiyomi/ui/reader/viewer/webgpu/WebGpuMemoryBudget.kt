// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import exh.yakuyomi.DeviceMemory
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// The arithmetic these two feed lives in WebGpuMemoryMath.kt (no Android import, asserted by
// MemoryBudgetHarness); what stays here is the reading of the system and the published snapshot.
// MB is repeated rather than shared because the math file is compiled on its own by the harness
// and must not import anything - these two are file-private to this one.

/** Bytes in one mebibyte, as every figure in `describe()` is expressed. */
private const val MB = 1024L * 1024L

/** Used only when `getMemoryInfo` throws or returns nothing; see WebGpuMemoryMath for why 4GB. */
private const val DEFAULT_TOTAL_MEM_BYTES = 4L * 1024L * 1024L * 1024L

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
 * The arithmetic itself - the size class, the pressure reading, the byte and pixel ceilings - lives
 * in `WebGpuMemoryMath.kt`, which has no Android import and is asserted by `MemoryBudgetHarness`.
 * What stays here is the two things Android owns: reading the system, and publishing the snapshot.
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
     * decision is deliberate) but its whole memory story shrinks: see [deviceTierFor]'s use of
     * `DeviceMemory.is64Bit` and the address-space argument there.
     */
    val is64Bit: Boolean = DeviceMemory.is64Bit()

    /** The device's size class, resolved once. */
    val deviceTier: DeviceTier = deviceTierFor(totalMemBytes, isLowRam, is64Bit)

    /**
     * What the panel can actually show, as a pixel area, read once: the larger display side
     * times the smaller, so orientation drops out. A page holds no more viewable detail than
     * the screen it is shown on - the rest is memory spent on texels nothing can resolve, which
     * on an e-ink panel (about a megapixel) or a small tablet is most of a high-resolution scan.
     * [resolvePixelCeiling] multiplies in zoom room: twice the linear scale on both axes is
     * the point where more source resolution stops being visible.
     */
    val displayPixels: Long = runCatching {
        val dm = context.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels).toLong()
        val h = maxOf(1, minOf(dm.widthPixels, dm.heightPixels)).toLong()
        w * h
    }.getOrDefault(0L)

    /**
     * The static decoded-pixel ceiling: the device tier's figure, capped by what the display can
     * resolve with zoom headroom. Both [Budget] factories scale this by live pressure.
     */
    val maxDecodedPixelsBase: Long = resolvePixelCeiling(deviceTier.maxDecodedPixels, displayPixels)

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
    fun canAffordWorkingSet(bytes: Long): Boolean =
        fitsWorkingSet(bytes, current.decodedPagesBytes)

    /**
     * Whether decoded pages should be dropped from outside `onTrimMemory`.
     *
     * The tier alone is the old behaviour, kept because a device that is merely small still needs
     * its residency trimmed on a real signal. What is new is the live half.
     */
    fun shouldShrinkDecodedPages(): Boolean =
        current.pressure.ordinal >= MemoryPressure.TIGHT.ordinal || isLowRam || deviceTier == DeviceTier.TINY

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
        val pressure: MemoryPressure,
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
         * is ~600MB of GPU textures - the shape that presents as
         * `VK_ERROR_OUT_OF_DEVICE_MEMORY` device loss two pages into a high-resolution comic.
         * The ceiling tracks the budget's own philosophy: device tier decides the default,
         * live pressure moves it, and a floor keeps the cap from starving the display itself.
         * Quality therefore varies across the session exactly when memory does - the same
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
                pressure = if (tier <= DeviceTier.SMALL) MemoryPressure.TIGHT else MemoryPressure.ROOMY,
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
                val headroom = memoryHeadroom(info.availMem, info.threshold)
                val rt = Runtime.getRuntime()
                val heapUsed = rt.totalMemory() - rt.freeMemory()
                val pressure = memoryPressureOf(
                    systemLowMemory = info.lowMemory,
                    headroomBytes = headroom,
                    heapUsedBytes = heapUsed,
                    javaHeapCeilingBytes = javaHeapCeilingBytes,
                    isLowRam = isLowRam,
                )

                // Both ceilings scale by live pressure, the pixel one more gently: see the
                // two scale functions in WebGpuMemoryMath for why they differ.
                val pixelCeilingScaled =
                    (tier.maxDecodedPixels * pixelScaleFor(pressure)).toLong()
                        .coerceAtLeast(scaledDecodedPixelsFloor())

                return Budget(
                    pressure = pressure,
                    headroomBytes = headroom,
                    decodedPagesBytes = scaledDecodedPagesBytes(tier, pressure),
                    tileCacheBytes = scaledTileCacheBytes(tier, pressure),
                    maxPageBytes = scaledMaxPageBytes(tier, pressure),
                    jxlPageBytes = scaledJxlPageBytes(tier, pressure),
                    maxDecodedPixels = pixelCeilingScaled,
                )
            }
        }
    }
}

internal fun WebGpuViewer.logMemoryBudget(reason: String) {
    logcat(LogPriority.INFO) { "MemBudget [$reason] ${memoryBudget.describe()}" }
}
// KMK <--