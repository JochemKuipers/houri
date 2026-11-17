// Runs the REAL ChapterPreloadGuard (compiled straight from app/.../ChapterPreloadGuard.kt).
//
// The guard elects one preload-and-retry loop per adjacent chapter. Its failure mode is not a crash
// but a stall exactly at a chapter boundary: the page getters are hot paths (every render snapshot
// re-resolves page 0 and its neighbours), so a guard that lets duplicates through starts a
// `ChapterLoader.loadChapter` per hit - DB reads, download checks, network page lists - while the
// reader is trying to scroll. The other failure is the opposite one: a key that is never released,
// so that chapter is never preloaded again for the session.
//
// Both are pure bookkeeping, which is why the whole class can be asserted here.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.ChapterPreloadGuard

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
    // --- one winner per key, duplicates refused ------------------------------------------
    val guard = ChapterPreloadGuard()
    check("first caller wins", guard.tryBegin("ch-1"))
    check("second caller is refused", !guard.tryBegin("ch-1"))
    check("third caller is refused", !guard.tryBegin("ch-1"))
    check("key is in flight", guard.isInFlight("ch-1"))
    check("a different key is unaffected", guard.tryBegin("ch-2"))

    // --- releasing lets a later attempt through ------------------------------------------
    guard.end("ch-1")
    check("released key is no longer in flight", !guard.isInFlight("ch-1"))
    check("released key can be re-begun", guard.tryBegin("ch-1"))
    guard.end("ch-1")
    guard.end("ch-2")

    // --- end() is idempotent --------------------------------------------------------------
    guard.end("never-begun")
    check("ending an unknown key is safe", !guard.isInFlight("never-begun"))

    // --- blank and oversized keys are refused, not tracked --------------------------------
    for (bad in listOf("", "   ", "x".repeat(513))) {
        val accepted = guard.tryBegin(bad)
        check("unusable key (${bad.take(4)}… length ${bad.length}) refused", !accepted)
        check("unusable key is not in flight", !guard.isInFlight(bad))
    }
    check("a key at the length limit is accepted", guard.tryBegin("y".repeat(512)))

    // --- stale requeue: a holder that gave up must not wedge the key ----------------------
    val stale = ChapterPreloadGuard()
    check("stale guard: first begin", stale.tryBegin("ch-9"))
    check("stale guard: duplicate refused while fresh", !stale.tryBegin("ch-9"))
    var staleChecked = false
    check(
        "stale guard: requeue when the holder says it is stale",
        stale.tryBeginOrRequeueIfStale("ch-9") { staleChecked = true; true },
    )
    check("stale guard: the staleness test ran", staleChecked)
    check("stale guard: the requeueing caller now owns the key", stale.isInFlight("ch-9"))

    // A live holder must keep its key: the staleness test is consulted, and answering false leaves
    // the incumbent in place.
    val live = ChapterPreloadGuard()
    live.tryBegin("ch-10")
    check(
        "live guard: duplicate refused when the holder is not stale",
        !live.tryBeginOrRequeueIfStale("ch-10") { false },
    )
    check("live guard: incumbent still owns the key", live.isInFlight("ch-10"))

    // A key nobody holds can be begun through the stale path without the test mattering.
    check("free key begun through the stale path", live.tryBeginOrRequeueIfStale("ch-11") { false })
    check("free key is in flight", live.isInFlight("ch-11"))

    // --- clear drops everything -----------------------------------------------------------
    live.clear()
    check("clear empties the key set", !live.isInFlight("ch-10") && !live.isInFlight("ch-11"))
    check("a cleared key can be begun again", live.tryBegin("ch-10"))

    // --- the key set is bounded: a viewer torn down without releasing its keys cannot grow forever
    val bounded = ChapterPreloadGuard()
    var accepted = 0
    var largest = 0
    for (i in 0 until 64) {
        if (bounded.tryBegin("chapter-$i")) accepted++
        largest = maxOf(largest, (0 until 64).count { bounded.isInFlight("chapter-$it") })
    }
    check("bounded guard never exceeds the cap", largest == 32)
    // The cap is a bound, not a refusal: the newcomer is the one that gets in, because it is the
    // chapter the reader is actually approaching. An earlier key is the one that was dropped.
    check("bounded guard holds exactly the cap", (0 until 64).count { bounded.isInFlight("chapter-$it") } == 32)
    check("bounded guard kept the newest key", bounded.isInFlight("chapter-63"))
    check("bounded guard dropped the oldest key", !bounded.isInFlight("chapter-0"))

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}