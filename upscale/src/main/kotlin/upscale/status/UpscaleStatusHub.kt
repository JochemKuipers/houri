package upscale.status

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The one source of truth for what upscaling did: per-page state for the HUD and the settings
 * live panel, aggregate counters for the stats, and an event stream for side-effect observers
 * (achievements). Process-lifetime; persistence is the page cache's business.
 */
class UpscaleStatusHub {

    enum class PageState { PLANNED, RUNNING, DONE, FELL_BACK, FAILED, SKIPPED }

    data class PageStatus(
        val pageIndex: Int,
        val state: PageState,
        val backendId: String? = null,
        val modelId: String? = null,
        val elapsedMs: Long = 0,
        /** When FELL_BACK: the backend that was demoted, and why. */
        val fallbackFrom: String? = null,
        val reason: String? = null,
        val updatedAt: Long = 0,
    )

    data class ChapterStatus(
        val mangaId: Long,
        val chapterId: Long,
        val pages: Map<Int, PageStatus> = emptyMap(),
        val lastUpdated: Long = 0,
    ) {
        val upscaledCount: Int get() = pages.values.count { it.state == PageState.DONE }
        val failedCount: Int get() = pages.values.count { it.state == PageState.FAILED }
        val activeCount: Int
            get() = pages.values.count { it.state == PageState.PLANNED || it.state == PageState.RUNNING }
    }

    /** One finished unit of work, emitted for observers (achievements, stats). */
    sealed interface Event {
        data class Served(
            val mangaId: Long,
            val chapterId: Long,
            val pageIndex: Int,
            val backendId: String,
            val modelId: String?,
            val cacheKey: String,
            val elapsedMs: Long,
            val prefetched: Boolean,
            /** The plan step's accelerator preference, e.g. VULKAN/NPU — drives achievement unlocks. */
            val backendPref: String? = null,
        ) : Event

        data class Failed(
            val mangaId: Long,
            val chapterId: Long,
            val pageIndex: Int,
            val backendId: String?,
            val reason: String,
        ) : Event
    }

    private val _chapters = MutableStateFlow<Map<Pair<Long, Long>, ChapterStatus>>(emptyMap())
    val chapters: StateFlow<Map<Pair<Long, Long>, ChapterStatus>> = _chapters.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: Flow<Event> = _events.asSharedFlow()

    /** Per-backend served counts over the process lifetime, for the settings panel. */
    private val _servedByBackend = MutableStateFlow<Map<String, Int>>(emptyMap())
    val servedByBackend: StateFlow<Map<String, Int>> = _servedByBackend.asStateFlow()

    private val _totals = MutableStateFlow(Totals())

    /** Guards the Totals counters in [markDone]-family updates. */
    private val countersLock = Any()
    val totals: StateFlow<Totals> = _totals.asStateFlow()

    data class Totals(
        val served: Int = 0,
        val fellBack: Int = 0,
        val failed: Int = 0,
        val servedFromCache: Int = 0,
        val prefetched: Int = 0,
        val totalInferenceMs: Long = 0,
    )

    fun chapterFlow(mangaId: Long, chapterId: Long): Flow<ChapterStatus?> =
        _chapters.map { it[mangaId to chapterId] }

    fun chapterStatus(mangaId: Long, chapterId: Long): ChapterStatus? =
        _chapters.value[mangaId to chapterId]

    fun pageStatus(mangaId: Long, chapterId: Long, pageIndex: Int): PageStatus? =
        _chapters.value[mangaId to chapterId]?.pages?.get(pageIndex)

    fun markPlanned(mangaId: Long, chapterId: Long, pageIndex: Int, backendId: String?) =
        update(mangaId, chapterId, pageIndex) {
            PageStatus(pageIndex, PageState.PLANNED, backendId = backendId, updatedAt = now())
        }

    fun markRunning(mangaId: Long, chapterId: Long, pageIndex: Int, backendId: String) =
        update(mangaId, chapterId, pageIndex) {
            PageStatus(pageIndex, PageState.RUNNING, backendId = backendId, updatedAt = now())
        }

    fun markSkipped(mangaId: Long, chapterId: Long, pageIndex: Int, reason: String) =
        update(mangaId, chapterId, pageIndex) {
            PageStatus(pageIndex, PageState.SKIPPED, reason = reason, updatedAt = now())
        }

    fun markDone(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        backendId: String,
        modelId: String?,
        elapsedMs: Long,
        fromCache: Boolean,
    ) {
        update(mangaId, chapterId, pageIndex) {
            PageStatus(
                pageIndex = pageIndex,
                state = PageState.DONE,
                backendId = backendId,
                modelId = modelId,
                elapsedMs = elapsedMs,
                reason = if (fromCache) "cache" else null,
                updatedAt = now(),
            )
        }
        synchronized(countersLock) {
            _totals.value = _totals.value.copy(
                served = _totals.value.served + 1,
                servedFromCache = _totals.value.servedFromCache + if (fromCache) 1 else 0,
                totalInferenceMs = _totals.value.totalInferenceMs + if (fromCache) 0 else elapsedMs,
                prefetched = _totals.value.prefetched,
            )
            _servedByBackend.value = _servedByBackend.value
                .let { it + (backendId to (it[backendId] ?: 0) + 1) }
        }
    }

    fun markFellBack(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        fromBackendId: String,
        toBackendId: String,
        reason: String,
    ) {
        update(mangaId, chapterId, pageIndex) {
            PageStatus(
                pageIndex = pageIndex,
                state = PageState.FELL_BACK,
                backendId = toBackendId,
                fallbackFrom = fromBackendId,
                reason = reason,
                updatedAt = now(),
            )
        }
        synchronized(countersLock) {
            _totals.value = _totals.value.copy(fellBack = _totals.value.fellBack + 1)
        }
    }

    fun markFailed(mangaId: Long, chapterId: Long, pageIndex: Int, backendId: String?, reason: String) {
        update(mangaId, chapterId, pageIndex) {
            PageStatus(
                pageIndex = pageIndex,
                state = PageState.FAILED,
                backendId = backendId,
                reason = reason.take(160),
                updatedAt = now(),
            )
        }
        synchronized(countersLock) {
            _totals.value = _totals.value.copy(failed = _totals.value.failed + 1)
        }
    }

    suspend fun emit(event: Event) {
        _events.emit(event)
    }

    fun tryEmit(event: Event) {
        _events.tryEmit(event)
    }

    fun clearChapter(mangaId: Long, chapterId: Long) {
        _chapters.update { it - (mangaId to chapterId) }
    }

    /** Wipes per-page tracking and the totals counters; model caches are managed elsewhere. */
    fun clear() {
        _chapters.value = emptyMap()
        _servedByBackend.value = emptyMap()
        _totals.value = Totals()
    }

    private fun update(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        transform: (PageStatus?) -> PageStatus,
    ) {
        _chapters.update { current ->
            val key = mangaId to chapterId
            val chapter = current[key] ?: ChapterStatus(mangaId, chapterId)
            // Cap the tracked pages per chapter: a 100k-strip is a legitimate chapter, and the
            // hub exists for the visible window, not for archival.
            val pages = if (chapter.pages.size >= MAX_TRACKED_PAGES && pageIndex !in chapter.pages) {
                chapter.pages.entries
                    .sortedBy { it.value.updatedAt }
                    .drop(1)
                    .associate { it.key to it.value }
            } else {
                chapter.pages
            }
            current + (key to chapter.copy(
                pages = pages + (pageIndex to transform(chapter.pages[pageIndex])),
                lastUpdated = now(),
            ))
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val MAX_TRACKED_PAGES = 64
    }
}
