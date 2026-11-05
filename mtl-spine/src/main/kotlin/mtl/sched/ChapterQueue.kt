package mtl.sched

/**
 * The pending-work buffer: per-chapter ordering, priorities, caps, lifecycle.
 *
 * Pure and synchronous — the scheduler polls it and drives dispatch. All ordering decisions
 * live here so they are unit-testable without coroutines:
 *
 * - pages of a chapter are dispatched in submission order per lane;
 * - [PagePriority.VISIBLE] work jumps ahead of [PagePriority.READ_AHEAD] work within a chapter;
 * - a duplicate submission of a page that is still pending is ignored (the first request wins);
 * - when a chapter is at its cap the **oldest pending page** is dropped, so one stalled page
 *   cannot wedge the chapter (carried over from the old queue's documented behavior);
 * - a paused chapter accepts nothing (the reader falls back to the original page rather than
 *   parking until resume).
 *
 * Completion is the scheduler's concern: it calls [complete] / [drop] to release a page from
 * the pending set, which is also what makes a later retry resubmittable.
 */
class ChapterQueue(private val pendingCap: Int = DEFAULT_PENDING_CAP) {

    /** Result of [submit]. */
    enum class SubmitResult { QUEUED, DUPLICATE, DROPPED_OLDEST, REJECTED_PAUSED }

    /** [dropped] carries the page the cap evicted, so its waiter can be completed. */
    data class SubmitOutcome(val result: SubmitResult, val dropped: PageWork? = null)

    private class ChapterBuffer {
        val visible = ArrayDeque<PageWork>()
        val readAhead = ArrayDeque<PageWork>()
        var paused: Boolean = false

        val size: Int get() = visible.size + readAhead.size
        val pendingIndexes: Set<Int> get() = (visible + readAhead).mapTo(HashSet()) { it.pageIndex }

        fun offer(work: PageWork) {
            if (work.priority == PagePriority.VISIBLE) visible.addLast(work) else readAhead.addLast(work)
        }

        /** Oldest pending page across both lanes, visible lane first (dispatch order); null when empty. */
        fun oldest(): PageWork? = visible.firstOrNull() ?: readAhead.firstOrNull()

        fun removeOldest(): PageWork = if (visible.isNotEmpty()) visible.removeFirst() else readAhead.removeFirst()

        fun poll(): PageWork? {
            val next = visible.removeFirstOrNull() ?: readAhead.removeFirstOrNull() ?: return null
            return next
        }

        fun remove(index: Int): Boolean {
            val v = visible.firstOrNull { it.pageIndex == index }
            if (v != null) return visible.remove(v)
            val r = readAhead.firstOrNull { it.pageIndex == index }
            if (r != null) return readAhead.remove(r)
            return false
        }

        fun clear(): List<Int> {
            val dropped = pendingIndexes
            visible.clear()
            readAhead.clear()
            return dropped.toList()
        }
    }

    private val buffers = HashMap<ChapterKey, ChapterBuffer>()

    val pendingCount: Int get() = synchronizedAll { buffers.values.sumOf { it.size } }

    /** Chapter keys with pending work, for status cleanup on cancel-all. */
    fun pendingChapters(): List<ChapterKey> = synchronizedAll { buffers.filterValues { it.size > 0 }.keys.toList() }

    fun isPaused(key: ChapterKey): Boolean = synchronizedAll { buffers[key]?.paused == true }

    fun submit(work: PageWork): SubmitOutcome = synchronizedAll {
        val buffer = buffers.getOrPut(work.key) { ChapterBuffer() }
        if (buffer.paused) return@synchronizedAll SubmitOutcome(SubmitResult.REJECTED_PAUSED)
        if (work.pageIndex in buffer.pendingIndexes) return@synchronizedAll SubmitOutcome(SubmitResult.DUPLICATE)

        // Drop the oldest pending page only after the duplicate check, so the page being
        // submitted is never the one the cap throws away.
        var dropped: PageWork? = null
        if (buffer.size >= pendingCap) {
            dropped = buffer.removeOldest()
        }
        buffer.offer(work)
        SubmitOutcome(
            result = if (dropped != null) SubmitResult.DROPPED_OLDEST else SubmitResult.QUEUED,
            dropped = dropped,
        )
    }

    /**
     * Next dispatchable page across all chapters, in chapter-submission order. Returns null
     * when nothing is eligible; the scheduler then waits for wakeups instead of spinning.
     *
     * [eligible] receives each candidate in global order (first pending page of the oldest
     * active chapter, and so on) so the scheduler can gate on lane availability.
     */
    fun pollNext(eligible: (PageWork) -> Boolean = { true }): PageWork? = synchronizedAll {
        for (buffer in buffers.values) {
            if (buffer.paused) continue
            val head = buffer.oldest() ?: continue
            if (eligible(head)) {
                buffer.poll()
                return@synchronizedAll head
            }
        }
        null
    }

    /** All pending work in dispatch order — used by tests and by status snapshots. */
    fun snapshot(): List<PageWork> = synchronizedAll {
        buildList {
            for (buffer in buffers.values) {
                addAll(buffer.visible)
                addAll(buffer.readAhead)
            }
        }
    }

    fun peek(key: ChapterKey, pageIndex: Int): Boolean = synchronizedAll {
        val buffer = buffers[key] ?: return@synchronizedAll false
        pageIndex in buffer.pendingIndexes
    }

    /** Page [pageIndex] finished (any outcome); frees its slot for a later retry. */
    fun complete(key: ChapterKey, pageIndex: Int) = synchronizedAll {
        buffers[key]?.remove(pageIndex)
        Unit
    }

    /** Drop [pageIndex] from pending without completing it (e.g. it was superseded). */
    fun drop(key: ChapterKey, pageIndex: Int) = complete(key, pageIndex)

    fun pause(key: ChapterKey) = synchronizedAll { buffers.getOrPut(key) { ChapterBuffer() }.paused = true }

    fun resume(key: ChapterKey) = synchronizedAll {
        buffers[key]?.paused = false
        Unit
    }

    /** Drops everything queued for a chapter; returns the dropped page indexes. */
    fun cancel(key: ChapterKey): List<Int> = synchronizedAll {
        buffers.remove(key)?.clear() ?: emptyList()
    }

    /** Cancels every chapter; returns the keys that had anything pending. */
    fun cancelAll(): List<ChapterKey> = synchronizedAll {
        val keys = buffers.filterValues { it.size > 0 }.keys.toList()
        keys.forEach { buffers.remove(it) }
        keys
    }

    private fun <T> synchronizedAll(block: () -> T): T = synchronized(this) { block() }

    companion object {
        const val DEFAULT_PENDING_CAP = 64
    }
}
