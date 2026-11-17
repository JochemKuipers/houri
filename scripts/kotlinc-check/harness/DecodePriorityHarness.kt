// Runs the REAL decode-priority policy (compiled straight from app/.../WebGpuDecodePriority.kt)
// and the REAL queue (PriorityDecodeQueue.kt) together, so the bands and the ordering they produce
// are asserted as one mechanism.
//
// Why this file exists: the bands are documented as load-bearing - "travelling-behind beats
// idle-ahead is the whole point of the direction boost" - and until now the only way to exercise
// them was to build a viewer with a populated cache and watch a log. The ordering algorithm itself
// is `DecodeQueueHarness`'s; what this adds is the *policy* that feeds it: which class a page is
// in, and therefore which page a real queue pops next.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_AHEAD_BASE
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_BEHIND_BASE
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_CURRENT
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_DEMANDED
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_DISTANCE_CAP
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_ON_SCREEN
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.DECODE_PRIORITY_OTHER
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.PriorityDecodeQueue
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.decodePriorityFor

private class FakePage(val name: String)

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private fun priority(
    distance: Int?,
    scrollDirection: Int = 1,
    isCurrent: Boolean = false,
    isOnScreen: Boolean = false,
    rendererDemanded: Boolean = false,
): Int = decodePriorityFor(isCurrent, isOnScreen, rendererDemanded, distance, scrollDirection)

fun main() {
    // --- the classes, and their strict ordering -------------------------------------------
    check("current page is the top class", priority(1, isCurrent = true) == DECODE_PRIORITY_CURRENT)
    check("on-screen page is next", priority(1, isOnScreen = true) == DECODE_PRIORITY_ON_SCREEN)
    check("renderer demand is next", priority(1, rendererDemanded = true) == DECODE_PRIORITY_DEMANDED)
    check("no anchor falls to OTHER", priority(null) == DECODE_PRIORITY_OTHER)
    check(
        "classes are strictly ordered",
        DECODE_PRIORITY_CURRENT > DECODE_PRIORITY_ON_SCREEN &&
            DECODE_PRIORITY_ON_SCREEN > DECODE_PRIORITY_DEMANDED &&
            DECODE_PRIORITY_DEMANDED > DECODE_PRIORITY_AHEAD_BASE + 20 &&
            DECODE_PRIORITY_AHEAD_BASE > DECODE_PRIORITY_OTHER,
    )

    // A page that is current outranks one that is merely on screen, however close the second is.
    check("current outranks on-screen", priority(1, isCurrent = true) > priority(1, isOnScreen = true))
    // Demand outranks the farthest possible travelling preload: the preload band must never reach
    // the demand class, or a speculative walk could starve the page the reader is looking at.
    check(
        "demand outranks every preload",
        DECODE_PRIORITY_DEMANDED > DECODE_PRIORITY_AHEAD_BASE + 20 - 1,
    )
    check("on-screen outranks demand", DECODE_PRIORITY_ON_SCREEN > DECODE_PRIORITY_DEMANDED)

    // --- distance orders within a class ----------------------------------------------------
    val near = priority(3)
    val far = priority(30)
    check("nearer page outranks farther", near > far)
    check("distance 1 is the base minus one", priority(1, scrollDirection = -1) == DECODE_PRIORITY_AHEAD_BASE - 1)
    check("distance 64 is the base minus the cap", priority(64, scrollDirection = -1) == DECODE_PRIORITY_AHEAD_BASE - DECODE_PRIORITY_DISTANCE_CAP)
    check("distance 65 clamps to the cap", priority(65, scrollDirection = -1) == priority(64, scrollDirection = -1))
    check("distance 100000 clamps too", priority(100_000, scrollDirection = -1) == priority(64, scrollDirection = -1))

    // A distance-0 page keeps the ahead base with no boost, exactly as the inline version did.
    check("distance 0 is the ahead base", priority(0) == DECODE_PRIORITY_AHEAD_BASE)

    // --- the direction flip, which is the reason the boost exists ----------------------------
    // Scrolling down: the page below (ahead) is the one the reader is travelling toward, so it
    // takes the boost and the page above (behind) does not.
    val aheadWhileDown = priority(10, scrollDirection = 1)
    val behindWhileDown = priority(-10, scrollDirection = 1)
    check("scrolling down boosts the page ahead", aheadWhileDown > behindWhileDown)
    check("scrolling down: ahead is base + boost - distance", aheadWhileDown == DECODE_PRIORITY_AHEAD_BASE + 20 - 10)
    check("scrolling down: behind is base - distance", behindWhileDown == DECODE_PRIORITY_BEHIND_BASE - 10)

    // Scrolling up reverses it: the nearest page above outranks the nearest below.
    val aheadWhileUp = priority(10, scrollDirection = -1)
    val behindWhileUp = priority(-10, scrollDirection = -1)
    check("scrolling up boosts the page behind", behindWhileUp > aheadWhileUp)
    check("scrolling up: behind is base + boost - distance", behindWhileUp == DECODE_PRIORITY_BEHIND_BASE + 20 - 10)

    // The interleaving that makes the bands non-stacking: at equal distance, travelling-behind
    // (740-d) beats idle-ahead (730-d), which is what "you scrolled up, so the pages above decode
    // first" means arithmetically. Scrolling up is what makes the page above the travelling one and
    // the page below the idle one, so both sides are evaluated with scrollDirection = -1.
    check(
        "travelling behind outranks idle ahead at equal distance",
        priority(-3, scrollDirection = -1) > priority(3, scrollDirection = -1),
    )
    // ...but not across a large distance gap: the boost is 20, so it only flips the order for
    // neighbours. A far page on the travelling side still loses to a near one on the idle side.
    check(
        "distance beats direction across a large gap",
        priority(3, scrollDirection = -1) > priority(-40, scrollDirection = -1),
    )
    // The crossover is exactly the boost: at d_travelling = 20 and d_idle = 1 the two meet, and the
    // assertion that matters is that the crossover is bounded by the boost, not by the bases.
    check(
        "direction flips the order only within the boost window",
        priority(-20, scrollDirection = -1) < priority(0, scrollDirection = -1),
    )

    // Ahead still outranks behind at equal distance and equal direction (the deque-era rule).
    check("ahead outranks behind when idle", priority(5, scrollDirection = 1) > priority(-5, scrollDirection = 1))

    // --- the real queue, driven by the real policy ------------------------------------------
    // Pop order for a small window: the page the reader is on, then the render-demanded page, then
    // the nearest preload in the direction of travel, then the idle ones.
    val queue = PriorityDecodeQueue<FakePage> { it.name }
    val pageCurrent = FakePage("current")
    val pageDemanded = FakePage("demanded")
    val pageTravellingNear = FakePage("travellingNear")
    val pageIdleNear = FakePage("idleNear")
    val pageFar = FakePage("far")
    queue.enqueue(pageFar, priority(40), lifoTies = false)
    queue.enqueue(pageIdleNear, priority(5, scrollDirection = -1), lifoTies = false)
    queue.enqueue(pageTravellingNear, priority(-5, scrollDirection = -1), lifoTies = false)
    queue.enqueue(pageDemanded, priority(5, rendererDemanded = true), lifoTies = true)
    queue.enqueue(pageCurrent, priority(0, isCurrent = true), lifoTies = true)

    check("queue pops the current page first", queue.pop() === pageCurrent)
    check("queue pops the demanded page second", queue.pop() === pageDemanded)
    check("queue pops the travelling page third", queue.pop() === pageTravellingNear)
    check("queue pops the idle page fourth", queue.pop() === pageIdleNear)
    check("queue pops the far page last", queue.pop() === pageFar)
    check("queue drains completely", queue.isEmpty())

    // A speculative preload must never jump the page the render walk is demanding, even when the
    // preload is adjacent and the demand is at the far clamp of its band.
    val q2 = PriorityDecodeQueue<FakePage> { it.name }
    val preload = FakePage("preload")
    val demand = FakePage("demand")
    q2.enqueue(preload, priority(1, scrollDirection = 1), lifoTies = false)
    q2.enqueue(demand, priority(1, rendererDemanded = true), lifoTies = true)
    check("a preload never outranks a demand", q2.pop() === demand && q2.pop() === preload)

    // A renderer-demanded page queued before a preload and re-offered as a preload must stay ahead:
    // enqueue dedups and upgrades, it never demotes.
    val q3 = PriorityDecodeQueue<FakePage> { it.name }
    val page = FakePage("page")
    q3.enqueue(page, priority(1, rendererDemanded = true), lifoTies = true)
    q3.enqueue(page, priority(1), lifoTies = false)
    check("re-offer does not demote a demanded page", q3.size == 1 && q3.pop() === page)

    // A far preload still outranks an unrelated page with no measurable distance.
    val q4 = PriorityDecodeQueue<FakePage> { it.name }
    val farPreload = FakePage("farPreload")
    val other = FakePage("other")
    q4.enqueue(other, priority(null), lifoTies = false)
    q4.enqueue(farPreload, priority(64), lifoTies = false)
    check("a far preload outranks an unrelated shell", q4.pop() === farPreload && q4.pop() === other)

    // --- negative and degenerate inputs -----------------------------------------------------
    // Int.MIN_VALUE is negative, so it lands in the behind band; what matters is that it clamps to
    // exactly what distance -64 produces and stays a positive, poppable priority. Without the
    // widening in decodePriorityFor this is the value that went negative.
    check(
        "Int.MIN_VALUE distance clamps",
        priority(Int.MIN_VALUE, scrollDirection = -1) == priority(-DECODE_PRIORITY_DISTANCE_CAP, scrollDirection = -1),
    )
    check("Int.MIN_VALUE distance stays positive", priority(Int.MIN_VALUE, scrollDirection = -1) > 0)
    check("Int.MAX_VALUE distance clamps", priority(Int.MAX_VALUE, scrollDirection = -1) == DECODE_PRIORITY_AHEAD_BASE - DECODE_PRIORITY_DISTANCE_CAP)
    check("a zero scroll direction is treated as up", priority(-5, scrollDirection = 0) == DECODE_PRIORITY_BEHIND_BASE + 20 - 5)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}