// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// The stuck-page ladder: how many times a page may be re-driven before its own state is thrown
// away, and how long a page must wait between two attempts.
//
// Both halves used to be inline in `WebGpuViewer.requeueStuckPage`, which meant the escalation
// table - the thing that decides whether a page that will not load is retried forever, rebuilt, or
// handed back - could only be exercised by building a viewer with a cache full of wedged shells.
// The table is now pure, and `StuckLadderHarness` asserts it.
//
// What each rung means (see the folder AGENTS' "stuck-page ladder" section for the full argument):
//
//   attempts <= SOFT  -> re-queue the same shell; the common case is a decode that raced an
//                        eviction, and the shell is fine.
//   attempts <= HARD  -> escalate: drop the shell and its tiles. Whatever wedged the page is state
//                        the page is holding, so the only way out is to stop holding it.
//   attempts >  HARD  -> give up: clear `wantedByRender` and hand the page back to the render walk,
//                        which re-arms the whole ladder once the reader has been away long enough.
//
// The cooldown is a separate dial from the escalation on purpose: without it, a page that cannot
// load spins as fast as a decode can fail, each attempt allocating decoder memory. With it, nine
// attempts is roughly fourteen seconds of trying - long enough to outlast a slow load.

/** Attempts re-queued before the page's own state is thrown away instead. */
internal const val STUCK_REDRIVE_SOFT_LIMIT = 3

/**
 * Attempts after which the page is left alone; recovery has clearly not fixed it. Nine at the
 * cooldown is roughly fourteen seconds of trying, which outlasts a slow load rather than writing
 * a page off, and a page that comes back on screen gets its budget again from `ensureDecoding`.
 */
internal const val STUCK_REDRIVE_HARD_LIMIT = 9

/**
 * Minimum gap between two recovery attempts on one page - the difference between a page that spins
 * and a page that is merely slow.
 */
internal const val STUCK_REDRIVE_COOLDOWN_MS = 1_500L

/**
 * How long a page must be out of demand before `ensureDecoding` treats coming back to it as a
 * fresh chance. Comfortably longer than the cooldown, so a rebuild is never mistaken for the user
 * having left and returned, and short enough that scrolling away and back still retries a hard
 * failure.
 */
internal const val STUCK_REDRIVE_FRESH_WINDOW_MS = 5_000L

/**
 * How long a page may sit in [PageState.LOADING] before the sweep treats it as stranded.
 *
 * A fetch that is genuinely slow must be left alone, so this is well above a slow connection
 * pulling a large image on a weak device. It exists for the page whose load coroutine ended with
 * the status stranded at Queue/LoadPage/DownloadImage: no further emission will ever arrive, so
 * without a deadline that page is LOADING for the rest of the session and matches no sweep branch.
 */
internal const val STUCK_LOAD_DEADLINE_MS = 30_000L

/** What one attempt of the ladder does to a page. */
internal enum class StuckLadderAction {
    /** Re-queue the same shell: the cheap retry. */
    REQUEUE,

    /** Throw the shell and its tiles away and let the next demand rebuild it. */
    REBUILD,

    /** Out of budget: hand the page back to the render walk. */
    GIVE_UP,
}

/**
 * The rung [attempts] sits on.
 *
 * [attempts] is the count *after* the current attempt was recorded, which is what the caller has
 * in hand when it asks: a first-ever attempt is 1 and re-queues.
 */
internal fun stuckLadderActionFor(attempts: Int): StuckLadderAction = when {
    attempts <= STUCK_REDRIVE_SOFT_LIMIT -> StuckLadderAction.REQUEUE
    attempts <= STUCK_REDRIVE_HARD_LIMIT -> StuckLadderAction.REBUILD
    else -> StuckLadderAction.GIVE_UP
}

/**
 * Whether an attempt recorded at [lastAttemptAtMs] is allowed to run at [nowMs].
 *
 * Rate limiting rather than a cap: a cap would leave a page that needed one more attempt
 * permanently broken, while this keeps trying - so a page that *can* load eventually does - and
 * only bounds how fast one that cannot may spin.
 */
internal fun stuckRetryDue(nowMs: Long, lastAttemptAtMs: Long): Boolean =
    nowMs - lastAttemptAtMs >= STUCK_REDRIVE_COOLDOWN_MS

/**
 * Whether a page that has been out of demand since [lastAttemptAtMs] is back for long enough that
 * `ensureDecoding` should reset its attempt count.
 *
 * Strictly greater, not greater-or-equal: the fresh window is compared against the age of the
 * last attempt, and an exact tie is the boundary case where the safest reading is "not fresh yet".
 */
internal fun stuckRetryBudgetReset(nowMs: Long, lastAttemptAtMs: Long): Boolean =
    nowMs - lastAttemptAtMs > STUCK_REDRIVE_FRESH_WINDOW_MS
// KMK <--
