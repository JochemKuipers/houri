// KMK -->
package tachiyomi.core.common.util.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.getSystemService

// The app-wide live memory reading: one sampler, one published snapshot, cheap volatile reads.
//
// This is the same two-half shape the WebGPU reader arrived at independently, promoted to a place
// every subsystem can reach:
//
//   - [DeviceMemoryClass] + [DeviceMemoryMath] are pure arithmetic (tier, pressure, ceilings) and
//     are asserted offline by `DeviceMemoryHarness`;
//   - this object is the only thing that talks to Android, and it publishes an immutable [Snapshot].
//
// The sample/read split is not a detail. `ActivityManager.getMemoryInfo` is a binder call, and the
// readers are hot paths - Coil's decoder runs on every image - so nothing here reads without also
// saying what the read is for:
//
//   - `sample(context)` reads the system and republishes. The image-loader factory
//     (`App.newImageLoader`, invoked by Coil when the first image is requested) calls it once to
//     prime the snapshot; that is the only call outside a decode.
//   - `sampleIfStale(context)` is the decoder's entry point: it answers from the published reading
//     when that is younger than [STALE_AFTER_MS] and re-reads only when it is not, so a burst of
//     decodes costs at most one binder call a second and an idle app costs none.
//
// There is deliberately no background poller and no "read without sampling" accessor. A process-wide
// poller would wake the app every interval whether or not anything needed the answer, and a free
// read would invite hot paths to guess from a reading nobody remembers taking. The snapshot keeps
// the last answer between calls; who refreshes it, and when, is always the caller's decision.

/**
 * The app-wide memory reading.
 *
 * Deliberately an `object` rather than a DI singleton: it is reachable from `App.onCreate` (before
 * any graph exists), from Coil's decoder factories, and from `core:common` utilities that take no
 * injected dependencies. Its state is a single volatile snapshot, so concurrent readers are free.
 */
object AppMemoryMonitor {

    /**
     * How long a snapshot stays fresh without a re-sample.
     *
     * One second: shorter and a burst of decodes re-reads the binder for the same answer, longer and
     * a device that went critical while backgrounded is acted on late. Every caller that makes a
     * decision worth more than a binder call goes through [sampleIfStale].
     */
    const val STALE_AFTER_MS = 1_000L

    /**
     * Whether the process runs a 64-bit ABI.
     *
     * A 32-bit process shares ~3GB of address space across the Java heap, the native decode buffers
     * and the GPU's own allocations, which is why [deviceTierFor] caps it.
     */
    val is64Bit: Boolean = Build.SUPPORTED_ABIS.any { it.contains("64") }

    /** The RAM-gate bypass easter egg, shared with `DeviceUtil` so one switch governs everything. */
    private const val BYPASS_PREFS = "ram_gate_bypass"
    private const val BYPASS_KEY = "disabled"

    /**
     * Whether the user permanently disabled the RAM gates via the About screen easter egg.
     *
     * Read live rather than cached, because the switch is flipped from a settings screen and the next
     * check must honour it without a process restart. Both `DeviceUtil` and the yakuyomi gates route
     * through here so there is one answer to "is the gate disabled".
     */
    fun isRamGateDisabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(BYPASS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(BYPASS_KEY, false)
    }.getOrDefault(false)

    /** Sets [isRamGateDisabled]. Written by the About screen; read by every gate. */
    fun setRamGateDisabled(context: Context, disabled: Boolean) {
        runCatching {
            context.getSharedPreferences(BYPASS_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(BYPASS_KEY, disabled).apply()
        }
    }

    /**
     * Whether the platform classes this device a low-RAM one.
     *
     * Deliberately kept *alongside* the size tiers rather than folded into them: it is a vendor
     * statement about the whole device, and a 4GB phone the OEM marked low-RAM is worse news than
     * its RAM count suggests.
     */
    fun isLowRamDevice(context: Context): Boolean = runCatching {
        context.getSystemService<ActivityManager>()?.isLowRamDevice == true
    }.getOrDefault(false)

    // --- the static half, resolved once -----------------------------------------------------

    /**
     * The device as it was built. Never changes, so resolved once per process.
     *
     * Both fields are read from the system on first use and then held: `totalMem` is a constant, and
     * `is64Bit` comes from the ABI list. The only reason this is not a compile-time constant is
     * `isLowRamDevice`, which is a system property lookup.
     */
    internal data class StaticInfo(
        val totalMemBytes: Long,
        val isLowRam: Boolean,
        val is64Bit: Boolean,
    ) {
        val tier: DeviceTier get() = deviceTierFor(totalMemBytes, isLowRam, is64Bit)
    }

    @Volatile
    private var staticInfo: StaticInfo? = null

    /** The device's size class. Resolved once; safe to call from any thread. */
    fun deviceTier(context: Context): DeviceTier = static(context).tier

    /** Total physical RAM, or 0 when the system service is unavailable. */
    fun totalRamBytes(context: Context): Long = static(context).totalMemBytes

    private fun static(context: Context): StaticInfo {
        staticInfo?.let { return it }
        return synchronized(this) {
            staticInfo ?: run {
                val info = runCatching {
                    val am = context.getSystemService<ActivityManager>()
                    val memoryInfo = ActivityManager.MemoryInfo()
                    val read = am != null && runCatching { am.getMemoryInfo(memoryInfo) }.isSuccess
                    // Checked rather than inferred from runCatching: `am?.getMemoryInfo(info)` on a
                    // null receiver throws nothing, so isSuccess would be true over an untouched,
                    // all-zero MemoryInfo - and totalMem=0 reads as TINY.
                    if (read && memoryInfo.totalMem > 0L) memoryInfo.totalMem else 0L
                }.getOrDefault(0L)
                StaticInfo(
                    // A failed read must not silently demote a flagship: the fallback is a
                    // mid-range device, not the smallest one (see DEFAULT_TOTAL_MEM_BYTES).
                    totalMemBytes = if (info > 0L) info else DEFAULT_TOTAL_MEM_BYTES,
                    isLowRam = isLowRamDevice(context),
                    is64Bit = is64Bit,
                ).also { staticInfo = it }
            }
        }
    }

    // --- the live half ----------------------------------------------------------------------

    /**
     * An immutable reading of the device *right now*, plus everything derived from it.
     *
     * Immutable and precomputed because every reader is on a hot path and the derivation is not
     * free. Publishing a whole snapshot rather than individual fields is what keeps the tier, the
     * pressure and the ceilings consistent with one another - a cache sized from one tick's pressure
     * and a worker count from the next is how a device gets a budget it cannot satisfy.
     */
    data class Snapshot(
        val pressure: MemoryPressure,
        /** Claimable working set: what the system says is free *above* where it starts killing. */
        val headroomBytes: Long,
        val tier: DeviceTier,
        val totalMemBytes: Long,
        val isLowRam: Boolean,
        /** The panel's pixel area, or 0 when it could not be read. */
        val displayPixels: Long,
        /** Resident decoded-page budget, in bytes. */
        val decodedBytes: Long,
        /** Heap Coil may use for its memory cache, in bytes. */
        val imageCacheBytes: Long,
        /** Largest single image admitted, in bytes. */
        val maxImageBytes: Long,
        /** Strictest JXL ceiling, in bytes. */
        val jxlBytes: Long,
        /** Largest decoded area uploaded at full resolution, in pixels. */
        val maxPixels: Long,
        /** How many fetches the image loader may run at once. */
        val fetchParallelism: Int,
        /**
         * The largest linear dimension a JXL may decode to, given [pressure].
         *
         * A dimension rather than a byte count because the decoder samples by whole-number halvings
         * of the larger side, so the cap that governs a JXL decode is inherently linear.
         */
        val jxlMaxDimension: Int,
    ) {
        fun describe(): String =
            "pressure=$pressure tier=$tier headroom=${headroomBytes / MEMORY_MB}MB " +
                "decoded=${decodedBytes / MEMORY_MB}MB imageCache=${imageCacheBytes / MEMORY_MB}MB " +
                "maxImage=${maxImageBytes / MEMORY_MB}MB px<=${maxPixels / 1_000_000L}MP " +
                "jxl<=${jxlMaxDimension}px fetch=$fetchParallelism"
    }

    @Volatile
    private var current: Snapshot? = null

    @Volatile
    private var sampledAtMs = 0L

    /**
     * The last reading if it is younger than [STALE_AFTER_MS], otherwise a fresh one.
     *
     * KMK --> This is the decoders' entry point, and its shape is the answer to "why not just read
     * the snapshot?": an earlier draft had a `snapshot(context)` that never sampled, which left the
     * published reading permanently at whatever `App.onCreate` took - the pressure-responsive
     * RGB_565 and JXL decisions in the decoders would have read a startup reading for the whole
     * session, dead code wearing a live API's clothes. It also briefly had the opposite, sampling
     * when stale on *every* decode, which is a binder call per image with no bound.
     *
     * The bound here is what makes the live answer affordable: at most one system read a second no
     * matter how many images are in flight, zero when nothing decodes. The reading is therefore
     * fresh *at the moment of the decision* - strictly better than a poller, which would hold a
     * reading up to a whole interval old and pay for the wakeups besides.
     */
    fun sampleIfStale(context: Context): Snapshot {
        current?.let { if (SystemClock.elapsedRealtime() - sampledAtMs < STALE_AFTER_MS) return it }
        return sample(context)
    }

    /**
     * Reads the system and republishes. The only method that does I/O.
     *
     * A failed `getMemoryInfo` holds the last good snapshot rather than shrinking the budget to
     * nothing: a reading that failed tells us nothing, and demoting the app to TINY for the rest of
     * the session is worse than one stale tick.
     */
    fun sample(context: Context): Snapshot {
        val info = ActivityManager.MemoryInfo()
        val read = runCatching {
            val am = context.getSystemService<ActivityManager>()
            am != null && runCatching { am.getMemoryInfo(info) }.isSuccess
        }.getOrDefault(false)

        val device = static(context)

        if (!read || info.availMem <= 0L) {
            // No usable reading. Keep the previous snapshot if there is one, so a transient failure
            // does not change any cache's size; build the first one from the device tier alone.
            val previous = current
            if (previous != null) return previous
            val ceilings = scaledCeilings(device.tier, MemoryPressure.ROOMY)
            return Snapshot(
                pressure = MemoryPressure.ROOMY,
                headroomBytes = 0L,
                tier = device.tier,
                totalMemBytes = device.totalMemBytes,
                isLowRam = device.isLowRam,
                displayPixels = displayPixels(context),
                decodedBytes = ceilings.decodedBytes,
                imageCacheBytes = ceilings.imageCacheBytes,
                maxImageBytes = ceilings.maxImageBytes,
                jxlBytes = ceilings.jxlBytes,
                maxPixels = resolvePixelCeiling(ceilings.maxPixels, displayPixels(context)),
                fetchParallelism = ceilings.fetchParallelism,
                jxlMaxDimension = jxlMaxDimension(ceilings.jxlDimension, MemoryPressure.ROOMY),
            ).also {
                current = it
                sampledAtMs = SystemClock.elapsedRealtime()
            }
        }

        val headroom = memoryHeadroom(info.availMem, info.threshold)
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        val pressure = memoryPressureOf(
            systemLowMemory = info.lowMemory,
            headroomBytes = headroom,
            heapUsedBytes = heapUsed,
            javaHeapCeilingBytes = runtime.maxMemory(),
            isLowRam = device.isLowRam,
        )

        val ceilings = scaledCeilings(device.tier, pressure)
        val pixels = displayPixels(context)
        val next = Snapshot(
            pressure = pressure,
            headroomBytes = headroom,
            tier = device.tier,
            totalMemBytes = device.totalMemBytes,
            isLowRam = device.isLowRam,
            displayPixels = pixels,
            decodedBytes = ceilings.decodedBytes,
            imageCacheBytes = ceilings.imageCacheBytes,
            maxImageBytes = ceilings.maxImageBytes,
            jxlBytes = ceilings.jxlBytes,
            maxPixels = resolvePixelCeiling(ceilings.maxPixels, pixels),
            fetchParallelism = ceilings.fetchParallelism,
            jxlMaxDimension = jxlMaxDimension(ceilings.jxlDimension, pressure),
        )
        current = next
        sampledAtMs = SystemClock.elapsedRealtime()
        return next
    }

    /**
     * The panel's pixel area, or 0 when it cannot be read.
     *
     * The larger display side times the smaller, so orientation drops out. A page holds no more
     * viewable detail than the screen it is shown on; this is what makes an e-ink panel (~1MP) cap at
     * ~4MP instead of uploading 600MB of textures nothing can resolve.
     */
    private fun displayPixels(context: Context): Long = runCatching {
        val dm = context.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels).toLong()
        val h = maxOf(1, minOf(dm.widthPixels, dm.heightPixels)).toLong()
        w * h
    }.getOrDefault(0L)

    // --- the gates, now expressed in one place ----------------------------------------------

    /**
     * Whether a device has enough RAM for the on-device AI translation pipeline.
     *
     * Expressed as a *tier* rather than a byte count, so it means the same thing everywhere: the
     * three native models plus large page bitmaps need at least the common modern phone's class, and
     * a 32-bit device is excluded by the tier's ABI cap rather than by a second rule that could
     * disagree with it.
     */
    fun isMtlSupported(context: Context): Boolean =
        isRamGateDisabled(context) || deviceTier(context) >= DeviceTier.MEDIUM

    /** Whether a device has enough RAM for the WebGPU (high-quality) renderer. */
    fun isWebGpuSupported(context: Context): Boolean =
        isRamGateDisabled(context) || deviceTier(context) >= DeviceTier.MEDIUM

    /**
     * Resets the cached static half.
     *
     * Only needed by tests and by the RAM-gate bypass: the bypass changes what `isMtlSupported`
     * answers, but not the tier, so this exists for a test that wants to re-read a different device.
     */
    internal fun resetCache() {
        synchronized(this) {
            staticInfo = null
            current = null
            sampledAtMs = 0L
        }
    }
}
// KMK <--
