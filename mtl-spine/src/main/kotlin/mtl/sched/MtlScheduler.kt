package mtl.sched

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Drains the [ChapterQueue] into the [PagePipeline].
 *
 * - One pump coroutine pulls dispatchable work and launches one job per page; a conflated
 *   wake channel means bursts of submissions cost one pump pass, not one per page.
 * - A global cap bounds concurrently *running* page jobs; per-provider concurrency is the
 *   lanes' business inside the pipeline.
 * - Every submission answers a [Deferred]<[PageOutcome]> — the reader awaits exactly the page
 *   it asked about, and duplicate submissions await the same deferred instead of queueing a
 *   second copy.
 * - Cancel is immediate and exception-free: dropped waiters complete with
 *   [PageOutcome.Skipped] so callers never see a cancellation escape into reading code.
 *
 * Waiter resolution is dynamic: a completing job completes *whatever* waiter is registered at
 * that moment, so a re-submission of a page that is already running still receives the
 * running job's result.
 */
class MtlScheduler(
    parentScope: CoroutineScope,
    private val pipeline: PagePipeline,
    private val queue: ChapterQueue = ChapterQueue(),
    private val maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = ConcurrentHashMap<WorkKey, Job>()
    private val waiters = ConcurrentHashMap<WorkKey, CompletableDeferred<PageOutcome>>()

    private data class WorkKey(val mangaId: Long, val chapterId: Long, val pageIndex: Int)

    init {
        scope.launch {
            while (isActive) {
                wake.receive()
                drain()
            }
        }
    }

    /**
     * Queues [work] and answers the deferred that completes when the page is done.
     *
     * A page already pending or running returns its existing deferred — the first request's
     * result is the second request's result. A paused chapter answers immediately with a
     * skip, because parking the reader's await behind a pause was the old queue's worst
     * behavior. A page the cap evicted answers its waiter with a skip the same way.
     */
    fun submit(work: PageWork): Deferred<PageOutcome> {
        val key = WorkKey(work.key.mangaId, work.key.chapterId, work.pageIndex)
        waiters[key]?.let { return it }

        val deferred = CompletableDeferred<PageOutcome>()
        val existing = waiters.putIfAbsent(key, deferred)
        if (existing != null) return existing

        val submitted = queue.submit(work)
        when (submitted.result) {
            ChapterQueue.SubmitResult.REJECTED_PAUSED -> {
                waiters.remove(key, deferred)
                deferred.complete(PageOutcome.Skipped("translation is paused for this chapter"))
            }
            ChapterQueue.SubmitResult.DUPLICATE -> {
                // The page is already queued or running; its job completes whichever waiter
                // (ours now) is registered when it finishes. Just make sure the pump runs.
                wake.trySend(Unit)
            }
            ChapterQueue.SubmitResult.DROPPED_OLDEST -> {
                submitted.dropped?.let { dropped ->
                    val droppedKey = WorkKey(dropped.key.mangaId, dropped.key.chapterId, dropped.pageIndex)
                    if (droppedKey != key) waiters[droppedKey]?.complete(PageOutcome.Skipped("queue overflow"))
                }
                wake.trySend(Unit)
            }
            ChapterQueue.SubmitResult.QUEUED -> wake.trySend(Unit)
        }
        return deferred
    }

    private suspend fun execute(work: PageWork, key: WorkKey) {
        try {
            val outcome = pipeline.run(work)
            waiters[key]?.complete(outcome)
        } catch (e: kotlinx.coroutines.CancellationException) {
            waiters[key]?.complete(PageOutcome.Skipped("cancelled"))
            throw e
        } catch (e: Exception) {
            waiters[key]?.complete(
                PageOutcome.Failed(
                    error = e.message ?: "translation crashed",
                    retryable = true,
                    errorKind = PageOutcome.MtlError.CRASH,
                ),
            )
        } finally {
            running.remove(key)
            queue.complete(work.key, work.pageIndex)
            waiters.remove(key)
            wake.trySend(Unit)
        }
    }

    /** Runs queued work up to the concurrency cap; called after every wake. */
    private suspend fun drain() {
        while (running.size < maxConcurrentJobs) {
            val work = queue.pollNext { running.size < maxConcurrentJobs } ?: return
            val key = WorkKey(work.key.mangaId, work.key.chapterId, work.pageIndex)
            if (running.putIfAbsent(key, scope.launch { execute(work, key) }) != null) {
                // Already running (a submit raced past the queue); drop this duplicate poll.
                queue.complete(work.key, work.pageIndex)
            }
        }
    }

    fun cancelChapter(key: ChapterKey) {
        val droppedPages = queue.cancel(key)
        droppedPages.forEach { index ->
            waiters.remove(WorkKey(key.mangaId, key.chapterId, index))
                ?.complete(PageOutcome.Skipped("cancelled"))
        }
        running.keys.filter { it.mangaId == key.mangaId && it.chapterId == key.chapterId }.forEach { workKey ->
            running.remove(workKey)?.cancel()
            waiters.remove(workKey)?.complete(PageOutcome.Skipped("cancelled"))
        }
    }

    fun pauseChapter(key: ChapterKey) = queue.pause(key)

    fun resumeChapter(key: ChapterKey) {
        queue.resume(key)
        wake.trySend(Unit)
    }

    /** Cancels everything; returns the chapter keys that had pending or running work. */
    fun cancelAll(): List<ChapterKey> {
        val keys = (queue.cancelAll() + running.keys.map { ChapterKey(it.mangaId, it.chapterId) }).distinct()
        keys.forEach { cancelChapter(it) }
        return keys
    }

    fun pendingCount(): Int = queue.pendingCount + running.size

    fun isPagePending(key: ChapterKey, pageIndex: Int): Boolean =
        queue.peek(key, pageIndex) ||
            running.keys.any {
                it.mangaId == key.mangaId && it.chapterId == key.chapterId && it.pageIndex == pageIndex
            }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_JOBS = 4
    }
}
