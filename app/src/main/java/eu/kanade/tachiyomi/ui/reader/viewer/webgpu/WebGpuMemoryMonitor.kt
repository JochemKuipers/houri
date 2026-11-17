// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Watches live memory and moves the reader's caches to match, shrinking and recovering on its own.
 *
 * This is the fourth long-lived coroutine, and it exists because `onTrimMemory` is not enough.
 * The system only sends a trim callback when it is already deciding about *some* process, which
 * means by the time one arrives the reader has usually already paid for the spike. The failure
 * this prevents is concrete: one webtoon segment costs ~47MB of texture, and cutting eight of them
 * back to back is a ~380MB swing that no trim signal necessarily precedes.
 *
 * ### Why it shrinks fast and recovers slowly
 *
 * Shrinking costs a rebuild that tiles refill out of, so it is cheap enough to do eagerly. Growing
 * costs the same rebuild, and a reader that grows and shrinks on alternating samples oscillates -
 * every transition a visible flash of un-tiled page. So recovery is gated behind both a cooldown
 * and a genuinely better pressure reading ([RECOVER_PRESSURE]), not merely "not TIGHT any more".
 * The two dials are the same split the stuck-page ladder uses: cooldown and escalation are
 * separate concerns, and resetting one must not reset the other.
 *
 * ### What it deliberately does not do
 *
 * It never touches the decode queue. Retracting work a page was already promised is how you get a
 * page stuck with `wantedByRender` set and nothing queued behind it - see the stuck-page ladder.
 * It only ever *drops* decoded shells and resizes the renderer, both of which are recoverable by
 * re-decode.
 */
internal fun WebGpuViewer.startMemoryMonitor() {
    // First sample immediately: a reader opened into an already-shrunken process must not run a
    // whole chapter at the device ceiling before the first tick.
    applyMemoryBudget("open")
    scope.launch {
        // How long the device has been continuously CRITICAL, which is what the backoff is computed
        // from. Monitor-local on purpose: it describes this loop's cadence, not the reader's state,
        // and nothing outside the loop has any use for it.
        var criticalForMs = 0L
        try {
            while (!isDestroyed) {
                val pressure = memoryBudget.budget().pressure

                // Tighter pressure means sampling harder, because that is when the answer changes
                // and when acting on a stale reading costs a device loss. The curve lives in
                // `sampleIntervalMs` so the app-wide sampler and this loop cannot drift apart.
                //
                // KMK --> Two additions to the cadence. A device that has been CRITICAL for a while
                // is already being reclaimed by the system, so the wait backs off rather than
                // polling a dying process every second (`criticalBackoffMs`). And while the reader
                // is not being looked at - screen off, activity stopped, surface detached - there is
                // nothing for a tighter cache to buy, so the loop drops to the idle cadence and
                // stops being a wakeup source across a long session.
                val idle = isReaderIdle()
                val waitMs = when {
                    idle -> IDLE_SAMPLE_INTERVAL_MS
                    pressure == MemoryPressure.CRITICAL -> criticalBackoffMs(criticalForMs)
                    else -> sampleIntervalMs(pressure)
                }

                delay(waitMs)
                if (isDestroyed) break
                applyMemoryBudget(if (idle) "poll-idle" else "poll")

                // Charged against the *previous* reading, deliberately: the pressure that decided
                // this wait is the one we now know lasted that long. Resetting it after a good
                // sample is what makes the backoff recover.
                criticalForMs = if (pressure == MemoryPressure.CRITICAL) criticalForMs + waitMs else 0L
            }
        } catch (_: CancellationException) {
            // Normal teardown.
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Memory monitor stopped" }
        }
    }
}

/**
 * Whether nothing is happening that a tighter or looser cache would change.
 *
 * Two signals, because both are ways the reader stops being looked at. [isReaderActive] is the
 * viewer hook (`ReaderActivity` clears it on stop), and the pager's own attachment to the window is
 * the automatic backstop for anything that detaches the surface without a lifecycle callback -
 * screen off, a window teardown, the reader replaced under a configuration change.
 *
 * Either one alone would be wrong: the hook covers "activity stopped but view still attached", and
 * the attachment check covers "surface gone without a callback". A reader mid-construction has
 * neither, so it answers false and keeps the normal cadence.
 *
 * The cost of a wrong answer here is small in both directions - an idle reader that was actually
 * busy gets a slower sample, and a busy reader that looked idle gets a faster one - which is why it
 * is worth degrading gracefully rather than threading a new callback through every teardown path.
 */
internal fun WebGpuViewer.isReaderIdle(): Boolean {
    if (isDestroyed) return true
    if (!isReaderActive) return true
    return try {
        !pager.isAttachedToWindow
    } catch (_: Exception) {
        false
    }
}

/**
 * While idle, one sample every ten seconds is still more often than the value can change.
 *
 * Not a stop: a reader that comes back to the foreground is sampled on the next tick, and
 * `onTrimMemory` still calls [applyMemoryBudget] directly at any moment.
 */
private const val IDLE_SAMPLE_INTERVAL_MS = 10_000L

/**
 * Samples the device once and moves the caches toward the answer.
 *
 * Idempotent by construction - every action below is "make the cache match this budget", so
 * running it when nothing changed is a few volatile reads and no GPU work. That matters because it
 * is also the path `onTrimMemory` takes, and a trim callback can arrive at any time.
 */
internal fun WebGpuViewer.applyMemoryBudget(reason: String) {
    if (isDestroyed) return
    val budget = memoryBudget.sample()

    try {
        // The renderer's half. Always pushed, every time - it is a single volatile assignment and
        // the renderer ignores a value equal to the one it already has, so there is no churn in
        // having it react to everything rather than only to the tiers.
        pager.state.setCacheByteBudget(budget.tileCacheBytes)
    } catch (_: Exception) {
        // The surface may not exist yet, or may be mid-teardown. The budget is re-pushed next tick.
    }

    WebGpuDiagnostics.recordBudget(budget.describe())
    val underPressure = memoryBudget.shouldShrinkDecodedPages()
    val previous = memoryPressureLast

    // KMK --> The stability clock. Stamped before the field is overwritten, and only when the
    // reading actually moved: recovery asks "how long has *this* reading held", so a reading that
    // oscillates has its clock reset on every crossing and can never satisfy the stability gate.
    // That is the intended behaviour - it is what stops a boundary-hovering device rebuilding.
    if (previous != budget.pressure) {
        memoryPressureSinceMs = SystemClock.elapsedRealtime()
    }
    memoryPressureLast = budget.pressure

    if (underPressure) {
        memoryLastShrinkAt = SystemClock.elapsedRealtime()
        shrinkCacheOnTrim()
        if (previous != budget.pressure) logMemoryBudget("$reason -> ${budget.describe()}")
        return
    }

    // Recovery. Three gates, and each one is a different failure mode:
    //
    //  1. the pressure must actually be good, not merely no longer tight;
    //  2. the reading must have *held* for `RECOVERY_STABLE_MS` - a device hovering on the TIGHT
    //     boundary crosses it for a single sample and falls back, and rebuilding the whole page
    //     cache on that is a visible flash for nothing (`isMeaningfulPressureChange`);
    //  3. enough time must have passed since the last shrink (`RECOVER_COOLDOWN_MS`), which is
    //     about how long ago the shed was and says nothing about the current reading's stability.
    //
    // Two and three are separate dials on purpose: the same principle as the stuck-page ladder,
    // where cooldown and escalation are independent. Collapsing them means a device that genuinely
    // recovered waits 12 s for no reason, or one that is oscillating rebuilds every sample.
    val sinceShrink = SystemClock.elapsedRealtime() - memoryLastShrinkAt
    val improving = previous != null && isPressureImproving(previous, budget.pressure)
    val stableFor = SystemClock.elapsedRealtime() - memoryPressureSinceMs
    val stable = isMeaningfulPressureChange(previous ?: budget.pressure, budget.pressure, stableFor)
    if (improving && stable && budget.pressure <= RECOVER_PRESSURE && sinceShrink > RECOVER_COOLDOWN_MS) {
        try {
            pager.state.restoreMemoryBudget()
        } catch (_: Exception) {
        }
        if (previous != budget.pressure) logMemoryBudget("$reason recovered -> ${budget.describe()}")
    }
}

/**
 * How long after a shrink the reader waits before growing anything back.
 *
 * Long enough that a pressure spike with a normal recovery inside it never triggers one. Six
 * samples at the tight cadence.
 */
private const val RECOVER_COOLDOWN_MS = 12_000L

/** Recovery also needs pressure to actually be good, not merely no longer tight. */
private val RECOVER_PRESSURE = MemoryPressure.ROOMY
// KMK <--
