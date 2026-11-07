package upscale.sched

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-ahead warming: runs the same plan the display path would run for the next N pages, writing
 * only to the disk cache, so the reader's cache-first lookup hits when the page arrives.
 *
 * Dedup is by (manga, chapter, page); a chapter's queue dies wholesale on
 * [cancelChapter] (chapter swap) or when [memoryGate] says the device cannot afford speculative
 * work right now. Execution is serialized: inference backends serialize themselves anyway, and
 * two concurrent prefetch decodes only double the transient bitmap spike.
 */
class PrefetchScheduler(
    private val scope: CoroutineScope,
    private val memoryGate: () -> Boolean,
    private val executor: suspend (PrefetchWork) -> Unit,
) {
    data class PrefetchWork(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        /** Supplies the page's ORIGINAL encoded bytes; null output = nothing to prefetch. */
        val bytesProvider: suspend () -> ByteArray?,
    ) {
        val key: String get() = "$mangaId:$chapterId:$pageIndex"
    }

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Submits one page for warming. Cheap, idempotent, and a no-op while memory is tight. */
    fun submit(work: PrefetchWork) {
        if (work.mangaId <= 0 || work.pageIndex < 0) return
        if (!inFlight.add(work.key)) return
        if (!memoryGate()) {
            inFlight.remove(work.key)
            return
        }
        scope.launch {
            try {
                executor(work)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(tachiyomi.core.common.util.system.LogPriority.DEBUG) {
                    "Prefetch failed for ${work.key}: ${e.message}"
                }
            } finally {
                inFlight.remove(work.key)
            }
        }
    }

    /** Whether this page is queued or running. */
    fun isPending(mangaId: Long, chapterId: Long, pageIndex: Int): Boolean =
        inFlight.contains("$mangaId:$chapterId:$pageIndex")

    /** Chapter-scoped cancellation note: in-flight work finishes (its output is still valid cache). */
    fun cancelChapter(mangaId: Long, chapterId: Long) {
        // Nothing to interrupt cooperatively: the executor checks the cache/status first and
        // writes are content-addressed. The set membership is the only bookkeeping.
        inFlight.removeIf { it.startsWith("$mangaId:$chapterId:") }
    }

    fun pendingCount(): Int = inFlight.size
}
