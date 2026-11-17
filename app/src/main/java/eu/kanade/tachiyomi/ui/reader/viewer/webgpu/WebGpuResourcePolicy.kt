// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// The policy that turns a pressure reading into an *action*, as opposed to a size.
//
// `WebGpuMemoryMath.kt` answers "how much may the reader hold". This file answers the four questions
// every other subsystem in the app now asks of the same live snapshot:
//
//   - has the pressure really changed, or is this a spike that will pass before a cache rebuild
//     would even finish? (`isMeaningfulPressureChange`)
//   - how often is it worth re-reading the system, given how much room is left? (`sampleIntervalMs`)
//   - given what else this session is doing, is a higher display refresh rate earning its battery?
//     (`shouldRequestMaxRefreshRate`)
//   - is this page cheap enough in RGB_565 that the quality loss is worth the bytes?
//     (`shouldDowngradeToRgb565`)
//
// Every one is a decision a user can see - a cache that rebuilt on a passing spike is a visible
// flash, a display pinned at 120Hz is an hour of battery, and RGB_565 on a page that did not need
// it is banding on a gradient. None of them need Android, which is what lets `MemoryBudgetHarness`
// assert them rather than a copy of them.
//
// KMK --> These three imports are load-bearing, not tidiness. This file sits in the reader's own
// package, which declares its own `internal` `DeviceTier` and `MemoryPressure`; without an explicit
// import every reference here would bind to those instead of the shared ones in `core:common`, and
// the reader and the rest of the app would silently disagree about what a tier or a pressure *is*
// while appearing to share this policy. An import is the only thing that makes the seam explicit.
import tachiyomi.core.common.util.system.DeviceTier
import tachiyomi.core.common.util.system.MemoryPressure

/**
 * Whether a pressure transition is worth acting on, or is a spike that will have passed before the
 * rebuild it would trigger even finished.
 *
 * This is hysteresis, and it is a *pair* of rules rather than one threshold because the two
 * directions cost different things. Growing is a visible flash of un-tiled page and a full tile
 * rebuild; shrinking is cheap and is what prevents the OOM. So:
 *
 * - **Escalation is immediate.** Any move to worse pressure is acted on. A device that is about to
 *   lose its GPU device does not benefit from waiting a sample to be sure.
 * - **Recovery needs the reading to have been stable for [RECOVERY_STABLE_MS].** The caller gates
 *   this on [isPressureImproving] *and* its own cooldown; this function supplies the stability half,
 *   because the cooldown is about how long ago the last shrink was and says nothing about whether
 *   the device is merely hovering on the boundary.
 *
 * Without this, a device sitting exactly at a tier's headroom threshold rebuilds its whole page
 * cache several times a minute - which is the oscillation `WebGpuMemoryMonitor`'s cooldown was added
 * to stop, arrived at from the other direction.
 */
internal fun isMeaningfulPressureChange(
    previous: MemoryPressure,
    next: MemoryPressure,
    nextUnchangedForMs: Long,
): Boolean {
    if (next.ordinal > previous.ordinal) return true
    if (next.ordinal < previous.ordinal) return nextUnchangedForMs >= RECOVERY_STABLE_MS
    return false
}

/** Whether [next] is genuinely better than [previous], which is what recovery requires. */
internal fun isPressureImproving(previous: MemoryPressure, next: MemoryPressure): Boolean =
    next.ordinal < previous.ordinal

/**
 * How long a candidate pressure must hold before a *recovery* is allowed to act on it.
 *
 * Longer than one sample by design: a device that climbs back to ROOMY for a single 2s tick and
 * then falls again has told us nothing about whether it can hold the larger caches.
 */
internal const val RECOVERY_STABLE_MS = 8_000L

/**
 * How often the system should be re-read, in milliseconds.
 *
 * Tighter pressure means sampling harder, because that is when the answer changes and when acting
 * on a stale reading costs a device loss. But the cadence is bounded at both ends: a device sitting
 * at CRITICAL cannot be helped by reading faster than [CRITICAL_INTERVAL_MS] (the binder call costs
 * more than the information is worth), and a ROOMY device has no reason to be woken at all more
 * often than [ROOMY_INTERVAL_MS] for a value that changes on the scale of seconds.
 *
 * The reader's monitor used to hardcode 4000/2000; the app-wide sampler reuses the same curve so
 * one reading serves both and neither invents a second answer.
 */
internal fun sampleIntervalMs(pressure: MemoryPressure): Long = when (pressure) {
    MemoryPressure.ROOMY -> ROOMY_SAMPLE_INTERVAL_MS
    MemoryPressure.TIGHT -> TIGHT_SAMPLE_INTERVAL_MS
    MemoryPressure.CRITICAL -> CRITICAL_SAMPLE_INTERVAL_MS
}

/** See [sampleIntervalMs]. Four seconds is often enough for a device with room to spare. */
internal const val ROOMY_SAMPLE_INTERVAL_MS = 4_000L

/** See [sampleIntervalMs]. Two seconds: tight memory changes fast enough to be worth the wakeups. */
internal const val TIGHT_SAMPLE_INTERVAL_MS = 2_000L

/** See [sampleIntervalMs]. One second, and never faster - the read itself stops being free here. */
internal const val CRITICAL_SAMPLE_INTERVAL_MS = 1_000L

/**
 * How long a device may sit at [MemoryPressure.CRITICAL] before the sampler stops asking.
 *
 * Sustained CRITICAL means the system is already reclaiming and `onTrimMemory` will fire on its
 * own; polling a dying device every second is the wrong way to find that out. This is a backoff
 * window, not a stop: the sampler doubles out to [MAX_CRITICAL_BACKOFF_MS] and then holds, so a
 * device that genuinely recovers is noticed within one window rather than at the next chapter.
 */
internal fun criticalBackoffMs(continuousCriticalMs: Long): Long =
    (CRITICAL_SAMPLE_INTERVAL_MS * 2).coerceAtMost(
        CRITICAL_SAMPLE_INTERVAL_MS + continuousCriticalMs / 2,
    ).coerceAtMost(MAX_CRITICAL_BACKOFF_MS)

/** Ceiling for [criticalBackoffMs]. */
internal const val MAX_CRITICAL_BACKOFF_MS = 16_000L

/**
 * Whether the reader should pin the display to its highest refresh rate.
 *
 * `RequestMaxRefreshRate` sets `preferredDisplayModeId`, which is the mechanism by which an app
 * *stops* an LTPO panel dropping to 60Hz. It is right while something is moving and wrong the rest
 * of the time, and "the rest of the time" is most of a reading session: a page being looked at is
 * static, and a panel held at 120Hz for it burns battery to draw the same pixels twice as often.
 *
 * So the answer is true only when the session is actually presenting motion:
 *
 * - **[scrolling]** - a fling or a drag. The continuous viewer emits this for the duration of a
 *   gesture, which is exactly the window where dropped frames are visible.
 * - **[animating]** - a page transition is in flight. Also motion, also brief.
 *
 * Everything else - reading a settled page, a decode in progress with no scroll, a menu open -
 * answers false and the panel is free to drop. The reader still *feels* smooth because the moment a
 * gesture starts, the mode is re-requested; the cost of the switch is one display mode change,
 * against an hour of 120Hz on a static page.
 */
internal fun shouldRequestMaxRefreshRate(scrolling: Boolean, animating: Boolean): Boolean =
    scrolling || animating

/**
 * Whether a page of [bytes] decoded at [width] x [height] should be copied to RGB_565.
 *
 * RGB_565 halves the bytes of RGBA_8888 and is the right trade for exactly one kind of page: large,
 * and photographic or painted rather than flat colour. It is the *wrong* trade for a page with
 * smooth gradients (banding) or sharp line art on white (colour fringing on the antialiased edge),
 * and both of those describe most manga.
 *
 * The rule this replaces was `isLowRamDevice && bytes > 32MB` - a device-size question asked of a
 * page property, which is the same category error the reader already fixed for `maxPageBytes`. Two
 * inputs decide it instead:
 *
 * - **The live budget.** A device with room should keep full colour on any page; only pressure makes
 *   the halving worth its quality cost.
 * - **The page's own size.** A 2MB page's halving saves 2MB and is never worth visible banding, so
 *   there is an absolute floor below which the answer is always no.
 */
internal fun shouldDowngradeToRgb565(
    bytes: Long,
    width: Int,
    height: Int,
    pressure: MemoryPressure,
): Boolean {
    if (width <= 0 || height <= 0) return false
    if (bytes < RGB565_MIN_BYTES) return false
    // A ROOMY device keeps full colour whatever the page costs: the whole point of the budget is
    // that the trade is made only when memory is actually short.
    if (pressure == MemoryPressure.ROOMY) return false
    return true
}

/**
 * Smallest page that is worth halving.
 *
 * Below this the saving is noise and the banding is not - and a page this small is not what puts a
 * device under pressure in the first place.
 */
internal const val RGB565_MIN_BYTES = 48L * 1024 * 1024

/**
 * Whether a JXL page of [width] x [height] must be downsampled before it reaches the decoder,
 * given the display's own [maxDimension] budget.
 *
 * JXL costs markedly more to decode per byte than JPEG, and its decoded bitmap is where a phone
 * dies. The cap that governs it is the smaller of two honest constraints - what the panel can show
 * and what the device can hold - and it is a *dimension* rather than a byte count because the
 * decoder samples by power-of-two factors, not by area.
 *
 * The value this replaces was `2048 if isLowRamDevice else 4096`: a device-size answer that refused
 * a 3000px JXL on a 2GB phone and then let a 12GB phone OOM on the same page. Here the same page
 * gets the same answer on both, because the panel is the binding constraint in both cases.
 */
internal fun jxlMaxDimension(tierCap: Int, pressure: MemoryPressure): Int {
    val scaled = when (pressure) {
        MemoryPressure.ROOMY -> tierCap
        MemoryPressure.TIGHT -> (tierCap * 0.75f).toInt()
        MemoryPressure.CRITICAL -> (tierCap * 0.5f).toInt()
    }
    // A power-of-two-friendly floor: the sampler halves until it is under the cap, so a cap that is
    // not a round number just makes the last halving land further below than it needs to.
    return scaled.coerceAtLeast(JXL_MIN_DIMENSION)
}

/** Never downsample a JXL below this, whatever the pressure - below it the page is the thumbnail. */
internal const val JXL_MIN_DIMENSION = 1024

/**
 * The largest dimension a JXL may decode to, as a power-of-two-friendly sample factor applies to it.
 *
 * Exposed separately because the decoder needs the *factor*, not the cap: `lowRamSampleSize`
 * already computes one by halving, and both callers have to agree on the same arithmetic or a page
 * will be sized for a cap it was not sampled against.
 */
internal fun jxlDimensionCapFor(tierCap: Int, pressure: MemoryPressure, hardCap: Int): Int =
    minOf(jxlMaxDimension(tierCap, pressure), hardCap)

/** Hard ceiling applied to every JXL regardless of tier, matching the decoder's own limit. */
internal const val JXL_HARD_DIMENSION = 8192

/**
 * How many parallel fetches the image loader should allow.
 *
 * KMK --> There is deliberately no `coilMemoryCachePercent` here. An earlier draft had one, and it
 * was wrong twice over: Coil's `maxSizePercent` is a fraction of the app's *heap*, so the same
 * fraction means different things on different devices, and `App.newImageLoader` already has the
 * honest answer — the tier's `imageCacheBytes` from `DeviceMemoryMath`, converted to a heap share at
 * the one call site that knows the heap ceiling. A second function expressing the same decision in
 * different units is how the two would come to disagree.
 *
 * The fetch pool is where the largest transient buffers live - each in-flight request can hold a
 * page-sized `PixelCopy` - so the parallelism is a *memory* decision that happens to also be a
 * throughput one. It is bounded at the top because beyond six the pool is I/O-bound on the device's
 * own storage rather than on the network, and extra workers only add concurrent buffers.
 */
internal fun coilFetchParallelism(tier: DeviceTier, pressure: MemoryPressure): Int {
    val base = when (tier) {
        DeviceTier.TINY, DeviceTier.SMALL -> 3
        DeviceTier.MEDIUM -> 4
        DeviceTier.LARGE, DeviceTier.XLARGE -> 6
    }
    return when (pressure) {
        MemoryPressure.ROOMY -> base
        MemoryPressure.TIGHT -> (base * 0.75f).toInt().coerceAtLeast(2)
        MemoryPressure.CRITICAL -> 2
    }
}

/**
 * How many decode workers the reader's decode pool should run.
 *
 * KMK --> One worker on a small device is not a performance choice: two concurrent decodes double
 * the transient bitmap spike that the byte budget exists to prevent, so a TINY/SMALL device running
 * two is the shape that loses the GPU device two pages into a comic. The second worker is granted
 * only when the tier can hold it *and* live pressure is not already short.
 *
 * The room test uses the reader's *own* scaled ceilings (this file's neighbours in
 * `WebGpuMemoryMath.kt`), not the shared app-wide `imageCacheBytes` figure. They are different
 * quantities: the shared one sizes Coil's LRU, while `scaledDecodedPagesBytes` is what a decode
 * worker actually draws from. Deriving workers from the cache size gave a LARGE device three workers
 * where the reader has always run two, which is the kind of silent budget inflation this function
 * exists to avoid.
 */
internal fun decodeParallelismFor(tier: DeviceTier, pressure: MemoryPressure): Int {
    if (tier <= DeviceTier.SMALL) return 1
    // Room for a second worker means the resident budget still holds two pages' worth after this
    // one is subtracted - the same `fitsWorkingSet` test the height-match gate uses, so the two
    // cannot disagree about what "enough room" means.
    val roomForTwo = fitsWorkingSet(DECODE_WORKER_TRANSIENT_BYTES, scaledDecodedPagesBytes(tier, pressure))
    return if (pressure == MemoryPressure.ROOMY && roomForTwo) DECODE_WORKERS else 1
}

/** The reader's worker ceiling. Two, and never more: see [decodeParallelismFor]. */
internal const val DECODE_WORKERS = 2

/**
 * A decode worker's transient working set, used only as a *unit* for the room test.
 *
 * One webtoon segment is roughly 47MB of texture, so a worker's spike is on that order. The figure
 * is deliberately conservative rather than measured: it only has to be big enough that a device
 * genuinely short on memory answers "no room", and `fitsWorkingSet` doubles it, which errs toward
 * one worker.
 */
internal val DECODE_WORKER_TRANSIENT_BYTES = 24L * 1024 * 1024
// KMK <--
