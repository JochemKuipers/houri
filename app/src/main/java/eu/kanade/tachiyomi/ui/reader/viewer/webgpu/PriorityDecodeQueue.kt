// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * The decode queue: elements owed a decode, ordered by a priority computed at enqueue time.
 *
 * The raw ArrayDeque this replaces encoded priority in its two ends - prioritized pages at the
 * tail (the pop end), background pages at the head - which made "prioritized" a single bit: the
 * current page jumped every preload, and everything else - including a page the renderer was
 * actively asking for - waited in insertion order no matter how close to the reader it was.
 * Priorities are now explicit (see [WebGpuViewer.decodePriorityOf]) and distance-aware, so the
 * worker always pops the page the reader needs next, and the side the reader is travelling
 * toward fills before the side they came from.
 *
 * Ordering contract preserved from the deque era: equal priorities among renderer-demanded pages
 * resolve newest-first (LIFO - the page just demanded wins), equal priorities among preload
 * pages resolve oldest-first (FIFO - the preload walk's ahead-before-behind insertion order
 * still decides within a distance tie).
 *
 * Thread-safety: every mutation happens under the viewer's lock, exactly as the deque's did;
 * the class itself takes none.
 *
 * Generic on the element type, with [label] supplied at construction: the ordering is the
 * load-bearing logic and [decodePriorityOf] is its policy, but the only thing the queue itself
 * ever needed to *know* about a page was how to name it in the stall summary - and keeping the
 * class free of `ViewerReaderPage` is what lets `scripts/kotlinc-check/run-harness.sh` compile
 * and run this file on a plain JVM, asserting the ordering algorithm against the thing that
 * runs rather than a copy of it ([DecodeQueueHarness]).
 *
 * @param label renders an element for [describe] - the stall summary names what the workers
 *   will touch next, so the label is the chapter/page identity.
 */
internal class PriorityDecodeQueue<T : Any>(
    private val label: (T) -> String,
) {

    private class Entry<E>(val element: E, val sortKey: Long)

    // Highest sortKey first - [pop] takes index 0. Small enough (the working set is dozens of
    // pages) that an ArrayList with a binary-search insert beats any heap on allocation churn.
    private val entries = ArrayList<Entry<T>>(32)
    private var seq = 0L

    /**
     * Adds [element] at [priority], replacing any existing entry for it first (identity compare -
     * the same dedup the LOADING branch guarded with `contains`, now unconditional, and the
     * queue never grows past the preload window).
     *
     * [lifoTies] decides how equal priorities order: true = newest first (renderer demand),
     * false = oldest first (preload walks).
     */
    fun enqueue(element: T, priority: Int, lifoTies: Boolean) {
        remove(element)
        val s = ++seq
        val sortKey = priority.toLong() * 1_000_000_000L + if (lifoTies) s else -s
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].sortKey >= sortKey) lo = mid + 1 else hi = mid
        }
        entries.add(lo, Entry(element, sortKey))
    }

    /** Removes and returns the highest-priority element, or null when empty. */
    fun pop(): T? =
        if (entries.isEmpty()) null else entries.removeAt(0).element

    /** Drops [element]'s entry, wherever it sits. Returns whether it was queued. */
    fun remove(element: T): Boolean =
        entries.removeAll { it.element === element }

    fun contains(element: T): Boolean = entries.any { it.element === element }

    /**
     * Whether any queued element satisfies [predicate] - the shape of the stale-guard probe in
     * [WebGpuViewer.preloadChapterThenRetry], which chapter-qualifies a bare index match because
     * every chapter counts its pages from 0. The predicate lives with the caller: the queue
     * never needed to know what a chapter was.
     */
    fun any(predicate: (T) -> Boolean): Boolean = entries.any { predicate(it.element) }

    fun clear() = entries.clear()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * The queue in pop order, capped, for the stall summary: what the workers will touch next.
     */
    fun describe(): String {
        if (entries.isEmpty()) return ""
        val named = entries.take(6).joinToString(",") { label(it.element) }
        return "${entries.size} [$named]"
    }
}
// KMK <--
