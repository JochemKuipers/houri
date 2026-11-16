// Runs the REAL stuck-page ladder policy (compiled straight from app/.../WebGpuStuckLadder.kt).
//
// The ladder is the answer to "a page will not finish loading" - retry cheaply a few times, then
// throw the shell away and rebuild it, then give up and hand it back to the render walk. Its
// failure modes are the reason this folder has a symptom table:
//
//   - too eager  -> a page that spins as fast as a decode can fail, allocating decoder memory each
//                   time, until the process is killed;
//   - too patient -> a permanently broken page is retried for the rest of the session, visibly;
//   - never giving up -> the page holds `wantedByRender` forever and nothing re-queues it.
//
// All three are arithmetic on two numbers (attempt count, elapsed time), which is why the table can
// be asserted here instead of inferred from a log on a phone.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.STUCK_LOAD_DEADLINE_MS
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.STUCK_REDRIVE_COOLDOWN_MS
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.STUCK_REDRIVE_FRESH_WINDOW_MS
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.STUCK_REDRIVE_HARD_LIMIT
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.STUCK_REDRIVE_SOFT_LIMIT
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.StuckLadderAction
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.stuckLadderActionFor
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.stuckRetryBudgetReset
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.stuckRetryDue

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

fun main() {
    // --- the table, rung by rung ----------------------------------------------------------
    check("attempt 1 re-queues", stuckLadderActionFor(1) == StuckLadderAction.REQUEUE)
    check("attempt 3 re-queues", stuckLadderActionFor(3) == StuckLadderAction.REQUEUE)
    check("attempt 4 rebuilds", stuckLadderActionFor(4) == StuckLadderAction.REBUILD)
    check("attempt 9 rebuilds", stuckLadderActionFor(9) == StuckLadderAction.REBUILD)
    check("attempt 10 gives up", stuckLadderActionFor(10) == StuckLadderAction.GIVE_UP)
    check("attempt 10_000 gives up", stuckLadderActionFor(10_000) == StuckLadderAction.GIVE_UP)

    check("soft limit is where the rebuild starts", stuckLadderActionFor(STUCK_REDRIVE_SOFT_LIMIT) == StuckLadderAction.REQUEUE)
    check("hard limit is the last rebuild", stuckLadderActionFor(STUCK_REDRIVE_HARD_LIMIT) == StuckLadderAction.REBUILD)
    check("past the hard limit is the end", stuckLadderActionFor(STUCK_REDRIVE_HARD_LIMIT + 1) == StuckLadderAction.GIVE_UP)

    // A non-positive count cannot be produced by the caller (it increments before asking), but a
    // zero or negative count must still land on the safe rung rather than fall off the table.
    check("attempt 0 re-queues", stuckLadderActionFor(0) == StuckLadderAction.REQUEUE)
    check("negative attempt re-queues", stuckLadderActionFor(-5) == StuckLadderAction.REQUEUE)

    // --- the cooldown: a spin is a page that retries faster than this ----------------------
    val now = 100_000L
    check("first attempt is always due", stuckRetryDue(now, 0L))
    check("an attempt just made is not due", !stuckRetryDue(now, now))
    check("one millisecond before the cooldown is not due", !stuckRetryDue(now, now - STUCK_REDRIVE_COOLDOWN_MS + 1))
    check("exactly at the cooldown is due", stuckRetryDue(now, now - STUCK_REDRIVE_COOLDOWN_MS))
    check("long past the cooldown is due", stuckRetryDue(now, now - 60_000L))

    // The cooldown is what makes nine attempts "roughly fourteen seconds" rather than "as fast as
    // a decode can fail": a decode that throws immediately would otherwise burn the ladder in
    // microseconds.
    val nineAttemptsMs = (1..STUCK_REDRIVE_HARD_LIMIT).sumOf { STUCK_REDRIVE_COOLDOWN_MS }
    check("nine attempts at the cooldown is a real span of time", nineAttemptsMs >= 10_000L)

    // --- the fresh window: coming back after a while re-arms the budget --------------------
    check("a page away for a moment has not re-armed", !stuckRetryBudgetReset(now, now - 1_000L))
    check("a page away past the fresh window has re-armed", stuckRetryBudgetReset(now, now - STUCK_REDRIVE_FRESH_WINDOW_MS - 1))
    // Strictly greater: an exact tie is the boundary where "not fresh yet" is the safe reading.
    check("an exact fresh-window tie has not re-armed", !stuckRetryBudgetReset(now, now - STUCK_REDRIVE_FRESH_WINDOW_MS))
    // The fresh window must be longer than the cooldown, or a rebuild would read as the reader
    // having left and returned, restarting the ladder every time it escalated.
    check("fresh window outlasts the cooldown", STUCK_REDRIVE_FRESH_WINDOW_MS > STUCK_REDRIVE_COOLDOWN_MS)

    // --- the load deadline ----------------------------------------------------------------
    // The deadline is what turns a page whose load coroutine ended with the status stranded at
    // Queue/LoadPage/DownloadImage from a silent permanent wedge into a bounded retry.
    check("the load deadline is well above a slow fetch", STUCK_LOAD_DEADLINE_MS >= 30_000L)

    // --- an escalation sequence, as the caller runs it ------------------------------------
    // Walk the ladder the way requeueStuckPage does: record the attempt, ask, act. The sequence
    // must reach GIVE_UP and must not reach it early - a page that can still be rescued gets the
    // whole cheap budget first.
    var attempts = 0
    val rungs = ArrayList<StuckLadderAction>()
    while (true) {
        attempts++
        val action = stuckLadderActionFor(attempts)
        rungs.add(action)
        if (action == StuckLadderAction.GIVE_UP) break
        if (attempts > 1_000) break // a runaway table must fail here, not hang the harness
    }
    check("the ladder terminates", attempts <= STUCK_REDRIVE_HARD_LIMIT + 1)
    check("the ladder ends by giving up", rungs.last() == StuckLadderAction.GIVE_UP)
    check("the ladder re-queues exactly the soft limit", rungs.count { it == StuckLadderAction.REQUEUE } == STUCK_REDRIVE_SOFT_LIMIT)
    check(
        "the ladder rebuilds exactly the hard band",
        rungs.count { it == StuckLadderAction.REBUILD } == STUCK_REDRIVE_HARD_LIMIT - STUCK_REDRIVE_SOFT_LIMIT,
    )
    check("the ladder never gives up twice", rungs.count { it == StuckLadderAction.GIVE_UP } == 1)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}