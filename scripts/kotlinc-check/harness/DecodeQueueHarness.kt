// Runs the REAL PriorityDecodeQueue (compiled straight from app/.../PriorityDecodeQueue.kt)
// through its ordering contract. The queue is generic precisely so this file can link the thing
// that runs instead of a copy of it; see the class's own doc for why.
//
// run-harness.sh compiles both files with one kotlinc invocation, so the queue's `internal`
// visibility is visible here (one compiler invocation = one module).

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.PriorityDecodeQueue

private class FakePage(val name: String)

fun main() {
    val q = PriorityDecodeQueue<FakePage> { it.name }
    var failures = 0
    fun check(label: String, cond: Boolean) {
        if (!cond) {
            failures++
            println("FAIL: $label")
        } else {
            println("ok: $label")
        }
    }

    // 1. Higher priority pops first, regardless of insertion order.
    val a = FakePage("a")
    val b = FakePage("b")
    val c = FakePage("c")
    q.enqueue(a, 600, lifoTies = false)
    q.enqueue(b, 800, lifoTies = true)
    q.enqueue(c, 100, lifoTies = false)
    check("highest priority pops first", q.pop() === b)
    check("then middle", q.pop() === a)
    check("then lowest", q.pop() === c)
    check("empty after drain", q.isEmpty())

    // 2. LIFO ties: equal priority among renderer-demanded pages, newest first.
    q.clear()
    val l1 = FakePage("l1")
    val l2 = FakePage("l2")
    val l3 = FakePage("l3")
    q.enqueue(l1, 800, lifoTies = true)
    q.enqueue(l2, 800, lifoTies = true)
    q.enqueue(l3, 800, lifoTies = true)
    check("lifo tie: newest first", q.pop() === l3)
    check("lifo tie: second newest", q.pop() === l2)
    check("lifo tie: oldest last", q.pop() === l1)

    // 3. FIFO ties: equal priority among preloads, oldest first (deque-era ahead-before-behind).
    q.clear()
    val f1 = FakePage("f1")
    val f2 = FakePage("f2")
    val f3 = FakePage("f3")
    q.enqueue(f1, 700, lifoTies = false)
    q.enqueue(f2, 700, lifoTies = false)
    q.enqueue(f3, 700, lifoTies = false)
    check("fifo tie: oldest first", q.pop() === f1)
    check("fifo tie: second", q.pop() === f2)
    check("fifo tie: newest last", q.pop() === f3)

    // 4. Distance ordering within a class: closer ahead outranks farther ahead.
    q.clear()
    val near = FakePage("near")
    val far = FakePage("far")
    q.enqueue(far, 730 - 30, lifoTies = false)
    q.enqueue(near, 730 - 3, lifoTies = false)
    check("closer ahead pops first", q.pop() === near)
    check("farther ahead pops second", q.pop() === far)

    // 5. Direction rule: scrolling up, the nearest page above (behind, travelling) outranks the
    //    nearest page below (ahead, idle). These are the real bands decodePriorityOf computes.
    q.clear()
    val aheadIdle = FakePage("aheadIdle")
    val behindTravelling = FakePage("behindTravelling")
    q.enqueue(aheadIdle, 730 - 1, lifoTies = false)        // idle ahead, distance 1
    q.enqueue(behindTravelling, 740 - 1, lifoTies = false) // travelling behind, distance 1
    check("travelling behind outranks idle ahead", q.pop() === behindTravelling)
    check("idle ahead next", q.pop() === aheadIdle)

    // 5b. Renderer demand outranks the farthest travelling preload (max preload 749 < 800).
    q.clear()
    val demanded = FakePage("demanded")
    val farTravelling = FakePage("farTravelling")
    q.enqueue(farTravelling, 750 - 1, lifoTies = false)
    q.enqueue(demanded, 800, lifoTies = true)
    check("demand outranks travelling preload", q.pop() === demanded)
    check("travelling preload next", q.pop() === farTravelling)

    // 6. Dedup by identity: re-enqueue replaces, never duplicates, and upgrades priority.
    q.clear()
    val d = FakePage("d")
    q.enqueue(d, 700, lifoTies = false)
    q.enqueue(d, 800, lifoTies = true)
    check("re-enqueue dedups", q.size == 1)
    check("re-enqueue upgrades priority", q.pop() === d && q.isEmpty())

    // 7. remove() of a middle entry keeps order.
    q.clear()
    val r1 = FakePage("r1")
    val r2 = FakePage("r2")
    val r3 = FakePage("r3")
    q.enqueue(r1, 800, lifoTies = false)
    q.enqueue(r2, 700, lifoTies = false)
    q.enqueue(r3, 600, lifoTies = false)
    check("remove reports presence", q.remove(r2))
    check("remove of absent reports false", !q.remove(r2))
    check("order kept after middle remove", q.pop() === r1 && q.pop() === r3)

    // 8. contains/any are identity- and predicate-based.
    q.clear()
    val id1 = FakePage("id1")
    q.enqueue(id1, 700, lifoTies = false)
    check("contains by identity", q.contains(id1))
    check("equal-but-distinct not contained", !q.contains(FakePage("id1")))
    check("any matches by predicate", q.any { it.name == "id1" })
    check("any rejects by predicate", !q.any { it.name == "other" })

    // 9. Same page in both classes (demand then preload re-offer): demand wins, no duplicate.
    q.clear()
    val both = FakePage("both")
    q.enqueue(both, 700, lifoTies = false)
    q.enqueue(both, 800, lifoTies = true)
    check("single entry after dual-class offers", q.size == 1 && q.pop() === both)

    // 10. describe() names the label in pop order, capped, empty-safe.
    check("describe of empty is empty", PriorityDecodeQueue<FakePage> { it.name }.describe() == "")
    q.clear()
    q.enqueue(FakePage("n1"), 900, lifoTies = true)
    q.enqueue(FakePage("n2"), 800, lifoTies = false)
    val described = q.describe()
    check("describe names pop order", described == "2 [n1,n2]")

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}
