// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.source.model.Page
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// The decode queue and its recovery ladder: which pages are owed a decode, how often a page that
// cannot finish is retried, and when its own state is thrown away instead.
//
// The invariant the whole file exists to protect is that a page is never sitting in a state that
// `queueForDecode` treats as "someone is already on it" while nothing actually is. `ensureDecoding`
// is the one producer (the renderer asked for it); `requeueStuckPage` is the one place allowed to
// break the tie.
//
// The queue itself is [PriorityDecodeQueue] (PriorityDecodeQueue.kt) - generic, so the offline
// harness can compile and run the real class against this file's priority policy.
//
// The policy halves are extracted and pure: the ordering is `decodePriorityFor`
// (WebGpuDecodePriority.kt) and the escalation table is `stuckLadderActionFor` /
// `stuckRetryDue` (WebGpuStuckLadder.kt). Both are asserted offline; what stays here is the state
// machine that applies them.

// KMK -->
/**
 * What the decode worker owes [page] relative to everything else queued, computed at enqueue.
 *
 * The two facts that need the viewer - which page is current, and how far [page] sits from it - are
 * resolved here; the ordering itself is [decodePriorityFor], which the offline harness drives
 * directly. Keeping the split at that seam is what makes the band arithmetic testable at all: it
 * used to be reachable only through a live viewer with a populated cache.
 */
internal fun WebGpuViewer.decodePriorityOf(page: ViewerReaderPage): Int {
    val onScreen = page.imagePage.isOnScreen
    val anchor = currentPage
    // Only a page that falls through to the preload bands needs a distance, and computing one walks
    // the chapter chain - so the three viewer-bound classes short-circuit before it. This runs at
    // enqueue time for every speculative preload in the window, which is exactly the case where an
    // avoidable chain walk would be paid most often.
    val distance = if (page === anchor || onScreen || page.wantedByRender) {
        null
    } else {
        anchor?.let { pageDistance(it, page) }
    }
    return decodePriorityFor(
        isCurrent = page === anchor,
        isOnScreen = onScreen,
        rendererDemanded = page.wantedByRender,
        distance = distance,
        scrollDirection = lastScrollDirection,
    )
}
// KMK <--

/**
 * Queue a page for decoding if not already queued/loading/decoded.
 * The page's priority is computed at enqueue (see [decodePriorityOf]); `prioritize` still forces
 * the renderer-demand class's LIFO tie-break for the current page.
 * Thread-safe: acquires [lock] internally.
 */
internal fun WebGpuViewer.queueForDecode(page: ViewerReaderPage, prioritize: Boolean = false) {
    synchronized(lock) {
        // KMK --> A shell that is no longer the cache's copy (evicted, or replaced by a rebuild)
        // has nowhere to put a decoded image: the worker would upload full textures nobody will
        // draw. The field log shows what queueing one anyway costs - an evicted-then-re-demanded
        // page gained a queue entry every frame while every pop skipped it (`Decode skipped ...
        // cached=false`), so the queue grew and drained in lockstep for the whole session. Every
        // other queueing site already checks membership; this closes the one path (the render
        // walk's ensureDecoding) that did not. KMK <--
        if (!pageInCache(page)) return

        // Already has a decoded image
        if (page.isDecoded) return

        when (page.state) {
            PageState.IDLE -> {
                page.state = PageState.QUEUED
                // KMK --> Priority, not insertion order: the current page, the pages the render
                // walk is demanding, and the direction the reader is travelling toward all jump
                // the preload window; distance breaks the rest. LIFO ties when the page is
                // renderer-demanded, FIFO ties for background preloads - the deque's two ends,
                // now as explicit classes.
                // KMK <--
                val priority = decodePriorityOf(page)
                decodeQueue.enqueue(page, priority, lifoTies = prioritize || page.wantedByRender)
                lock.notify()
                logcat(LogPriority.DEBUG) {
                    "Queued ch=${page.page.chapter.chapter.id}/i=${page.page.index} " +
                        "priority=$priority depth=${decodeQueue.size}"
                }
            }

            PageState.QUEUED -> {
                // Already queued - re-prioritise rather than blindly reorder: a page queued as a
                // background preload and demanded since moves forward, and enqueue dedups first.
                // wantedByRender is checked alongside prioritize because ensureDecoding only
                // passes prioritize for the current page - a render-walk demand for any other
                // page in the window must still lift it out of its stale preload class (the flag
                // is set before queueForDecode is called, and the one-shot guard above means this
                // branch runs once per demand, not per frame).
                if (prioritize || page.wantedByRender) {
                    decodeQueue.enqueue(page, decodePriorityOf(page), lifoTies = true)
                }
            }

            PageState.LOADING -> {
                // KMK --> A LOADING page whose bytes are already on disk only needs decoding, so
                // it can join the decode queue directly. Treating LOADING as "already being
                // processed" and returning is what wedged pages forever: [startPageLoad]'s cleanup
                // only resets LOADING -> IDLE while the page is still in the cache, so a shell
                // evicted mid-load (or whose load coroutine was cancelled) kept the state, and
                // re-entering it - getPage hands the same shell back - re-entered this branch,
                // which did nothing. Nothing else in the viewer writes LOADING, so the page spun
                // its ProgressPage indefinitely.
                //
                // The membership test closes the mirror-image hazard: a LOADING page is not always
                // un-queued, because [startPageLoad] can claim a page the worker already popped off
                // it while it was still QUEUED. Enqueueing then gave the worker two entries for one
                // page - the second decode uploaded a full image only for the `!isDecoded` guard to
                // discard it, leaking every texture that upload created.
                // KMK <--
                if (page.page.status == Page.State.Ready && !decodeQueue.contains(page)) {
                    page.state = PageState.QUEUED
                    decodeQueue.enqueue(page, decodePriorityOf(page), lifoTies = true)
                    lock.notify()
                }
            }

            PageState.DECODING -> {
                // Already being processed
            }
        }
    }
}

/**
 * Puts a page the renderer has asked for into the decode pipeline.
 *
 * Called from `fetchPage`, which is the only place that knows a page is genuinely wanted on screen
 * right now. Every other queueing site is a speculative preload walking outward from currentPage,
 * and its window is computed from the reach reported by the renderer on the *previous* pass - so a
 * page the renderer walks to can sit outside it. That left the shell in the cache showing its
 * placeholder with nothing scheduled: `getPage` had created it, `preloadPages` had not reached it,
 * and no later call would. The gap then persisted as a spinning indicator with the pages around it
 * decoded normally, which is exactly what scrolling back into an evicted zone produced.
 *
 * Queueing here makes the guarantee local - a page the renderer holds is a page being worked on -
 * rather than depending on a speculative walk having covered it.
 */
internal fun WebGpuViewer.ensureDecoding(page: ViewerPage) {
    when (page) {
        // A transition is drawn procedurally; there is nothing to decode.
        is ViewerTransitionPage -> Unit
        is ViewerReaderPage -> {
            // One-shot per page. fetchPage runs every frame for every page in the render window, so
            // queueing unconditionally turned any page that could not complete into a frame-rate
            // retry loop: each pass allocated decoder and network buffers, the worker forced a GC on
            // every failure, and the heap was exhausted while the whole app stalled behind the lock.
            // Queueing once at first demand still closes the gap this was written for, and the
            // signal-driven liveness sweep remains the recovery path for a genuine stall.
            if (page.wantedByRender) return
            page.wantedByRender = true
            synchronized(lock) {
                val record = stuckRecords[pageKey(page)] ?: return@synchronized
                // Only a real absence counts as a fresh chance. A shell rebuilt by the escalation is
                // a new shell moments after the old one, so resetting on that would restart the
                // ladder on every rebuild - which is the churn the escalation exists to stop.
                val now = System.currentTimeMillis()
                if (stuckRetryBudgetReset(now, record.lastAttemptAt)) {
                    logcat(LogPriority.DEBUG) {
                        "Re-demand ch=${page.page.chapter.chapter.id}/i=${page.page.index} " +
                            "after ${now - record.lastAttemptAt}ms away " +
                            "- stuck ladder reset from ${record.attempts} attempts"
                    }
                    record.attempts = 0
                }
            }
            queueForDecode(page, prioritize = page === currentPage)
        }
        else -> Unit
    }
}

/**
 * Releases a page the viewer believes is in flight but is not, so it can be worked on again.
 *
 * The recovery counterpart to [queueForDecode], which is deliberately conservative: it treats
 * `LOADING`/`DECODING` as "someone is on it" and does nothing. That is right while the owning
 * coroutine is alive and wrong the moment it is not, and because nothing else in the viewer can tell
 * the difference, the page kept its state with no work behind it and spun forever. This is the only
 * place that breaks the tie, and it does so only when the page is demonstrably not queued and not
 * being decoded.
 *
 * Unbounded, this is the loop rather than the cure. The worker's own cleanup hands a failed page
 * back as IDLE and signals the sweep that calls here, so every retry manufactures the condition that
 * schedules the next one, as fast as a decode can fail - a thousand attempts a second, each
 * allocating decoder memory, until the app is killed. What bounds it is the cooldown, and what makes
 * it useful rather than merely quiet is the escalation: repeated cheap retries, then the page's own
 * state is thrown away and rebuilt, which is the only thing that can help when the reason it is
 * stuck is state it is holding.
 *
 * Answers what it did, so the caller can log real recoveries and re-arm after a cooldown instead
 * of assuming every orphan was put back on the queue.
 */
internal fun WebGpuViewer.requeueStuckPage(page: ViewerReaderPage): StuckRecovery {
    val now = System.currentTimeMillis()
    var escalate = false
    synchronized(lock) {
        if (page.isDecoded || page.imagePage.destroyed) return StuckRecovery.GONE
        val record = stuckRecords.getOrPut(pageKey(page)) { StuckPageRecord() }

        // Rate limit rather than a cap. A cap would leave a page that needed one more attempt
        // permanently broken; this keeps trying, so the page does eventually load, while bounding
        // how fast a page that cannot load can spin. See WebGpuStuckLadder.kt for the table.
        if (!stuckRetryDue(now, record.lastAttemptAt)) return StuckRecovery.DEFERRED
        record.lastAttemptAt = now
        record.attempts++
        // A re-driven page gets a fresh load deadline. Without this the stamp from the attempt that
        // just timed out is still there, so the next startPageLoad overwrites it but a requeue that
        // does not reach a load at all would measure against the old entry and be rescued instantly.
        loadingSince.remove(pageKey(page))

        when (stuckLadderActionFor(record.attempts)) {
            StuckLadderAction.REQUEUE -> page.state = PageState.IDLE
            StuckLadderAction.REBUILD -> escalate = true
            StuckLadderAction.GIVE_UP -> {
                // Out of budget, and simply returning here is what makes it permanent. The shell
                // stays resident with `wantedByRender` still set, and ensureDecoding - the one
                // place that resets the record - returns before it can reach the reset. Nothing
                // then queues this page again for the rest of the session, however long the reader
                // sits on it. Releasing the flag hands the page back to the render walk, which
                // re-arms the whole ladder once the reader has been away for the fresh window.
                page.wantedByRender = false
                WebGpuDiagnostics.recordLadder("GIVE_UP")
                logcat(LogPriority.WARN) {
                    "Giving up on ch=${page.page.chapter.chapter.id}/i=${page.page.index} " +
                        "after ${record.attempts} attempts last=${lastDecodeOutcome(page) ?: "none"} " +
                        "- handing it back to the render walk"
                }
                return StuckRecovery.GONE
            }
        }
    }

    if (escalate) {
        // Past the cheap retries, so stop reusing this page's state: drop the shell and its tiles
        // entirely and let the next demand build it again from nothing. Whatever wedged it - a dead
        // tile, a half-applied split, an image the decoder rejected - is state this page is holding,
        // so the only way out is to stop holding it.
        return tearDownStuckPage(page)
    }

    queueForDecode(page, prioritize = currentPage?.let { pageKey(it) == pageKey(page) } ?: false)
    WebGpuDiagnostics.recordLadder("REDRIVEN")
    return StuckRecovery.REDRIVEN
}

/**
 * How long [page] has been LOADING, or 0 when it is not currently loading.
 */
internal fun WebGpuViewer.loadingFor(page: ViewerReaderPage): Long {
    val since = synchronized(lock) { loadingSince[pageKey(page)] } ?: return 0L
    return System.currentTimeMillis() - since
}

/** What one recovery attempt on a stuck page actually did. */
internal enum class StuckRecovery {
    REDRIVEN,
    REBUILT,
    DEFERRED,
    GONE,
}

internal class StuckPageRecord {
    var attempts: Int = 0
    var lastAttemptAt: Long = 0L
}

/**
 * Discards [page] and everything it owns, so the next [WebGpuViewer.getPage] builds it fresh.
 *
 * Cleanup runs outside the lock - it tears down textures, and holding the viewer lock across that is
 * what the eviction path deliberately avoids too.
 */
internal fun WebGpuViewer.tearDownStuckPage(page: ViewerReaderPage): StuckRecovery {
    synchronized(lock) {
        if (page.imagePage.destroyed) return StuckRecovery.GONE
        pageCache.remove(pageKey(page))
        decodeQueue.remove(page)
        WebGpuDiagnostics.recordLadder("REBUILT")
    }
    releasePageResources(page)
    return StuckRecovery.REBUILT
}
// Mihon <--
