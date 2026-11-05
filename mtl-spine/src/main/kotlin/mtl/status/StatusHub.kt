package mtl.status

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import mtl.core.ProviderId
import mtl.sched.PagePhase

/**
 * Runtime translation status, keyed per chapter, observed as Flows.
 *
 * Direct successor of the old [TranslationStatus] with the same page states and progress
 * semantics, plus:
 * - finer [phase] reporting from the spine's page jobs,
 * - which provider served each page (logged and displayed — no silent fallbacks),
 * - machine-readable [errorKind] beside the user-facing message.
 *
 * State is process-lifetime; persistence is the page store's business.
 */
class StatusHub {

    enum class PageState { TRANSLATING, DONE, CACHED, SKIPPED, FAILED }

    data class PageStatus(
        val pageIndex: Int,
        val state: PageState,
        val phase: PagePhase = PagePhase.QUEUED,
        val error: String? = null,
        val errorKind: String? = null,
        val servedBy: ProviderId? = null,
        val updatedAt: Long = 0,
    ) {
        /**
         * Whether attempting this page again could plausibly produce a translation.
         *
         * Phases [PagePhase.DONE]..[PagePhase.STORING] on a non-failed page are progress, not
         * outcomes; only terminal states carry retryability, and only failures the spine marked
         * retryable (provider/network class) are worth a retry.
         */
        val isRetryable: Boolean
            get() = state == PageState.FAILED && errorKind != null && errorKind in RETRYABLE_KINDS

        companion object {
            private val RETRYABLE_KINDS = setOf(
                PageOutcomeKind.PROVIDER_UNAVAILABLE.name,
                PageOutcomeKind.RATE_LIMITED.name,
                PageOutcomeKind.TIMEOUT.name,
                PageOutcomeKind.QUALITY_REJECTED.name,
                PageOutcomeKind.CRASH.name,
            )
        }
    }

    /** Machine-readable error kinds, mirroring [PageOutcome.MtlError]; string-stable for persistence. */
    enum class PageOutcomeKind {
        PROVIDER_UNAVAILABLE,
        AUTH_REQUIRED,
        RATE_LIMITED,
        TIMEOUT,
        MODEL_MISSING,
        QUALITY_REJECTED,
        NOT_ACTIONABLE,
        CRASH,
    }

    data class ChapterStatus(
        val mangaId: Long,
        val chapterId: Long,
        val totalPages: Int = 0,
        val pages: Map<Int, PageStatus> = emptyMap(),
        val lastError: String? = null,
        val lastUpdated: Long = 0,
    ) {
        val isTranslating: Boolean get() = pages.values.any { it.state == PageState.TRANSLATING }
        val translatedCount: Int get() = pages.values.count { it.state == PageState.DONE || it.state == PageState.CACHED }
        val failedCount: Int get() = pages.values.count { it.state == PageState.FAILED }
        val skippedCount: Int get() = pages.values.count { it.state == PageState.SKIPPED }
        val pendingCount: Int get() = (totalPages - translatedCount).coerceAtLeast(0)
        val activePhase: PagePhase?
            get() = pages.values
                .filter { it.state == PageState.TRANSLATING }
                .maxByOrNull { it.updatedAt }
                ?.phase
    }

    private val _chapters = MutableStateFlow<Map<Pair<Long, Long>, ChapterStatus>>(emptyMap())
    val chapters: StateFlow<Map<Pair<Long, Long>, ChapterStatus>> = _chapters.asStateFlow()

    fun chapterStatus(mangaId: Long, chapterId: Long): ChapterStatus? =
        _chapters.value[mangaId to chapterId]

    /**
     * Cold per-chapter flow derived from [chapters]. UI call sites collect this; the spine
     * publishes through [chapters] only, so there is exactly one write path.
     */
    fun chapterFlow(mangaId: Long, chapterId: Long): Flow<ChapterStatus?> =
        chapters.map { it[mangaId to chapterId] }.distinctUntilChanged()

    fun resetChapter(mangaId: Long, chapterId: Long) {
        _chapters.update { it - (mangaId to chapterId) }
    }

    fun clearAll() {
        _chapters.value = emptyMap()
    }

    fun setTotalPages(mangaId: Long, chapterId: Long, total: Int) =
        mutate(mangaId, chapterId) { it.copy(totalPages = total.coerceAtLeast(it.pages.size), lastUpdated = now()) }

    fun pagePhase(mangaId: Long, chapterId: Long, pageIndex: Int, phase: PagePhase) =
        mutate(mangaId, chapterId) {
            val prev = it.pages[pageIndex]
            it.copy(
                lastUpdated = now(),
                pages = it.pages + (pageIndex to (prev ?: fresh(pageIndex)).copy(phase = phase, state = PageState.TRANSLATING, updatedAt = now())),
            )
        }

    fun pageCached(mangaId: Long, chapterId: Long, pageIndex: Int) =
        terminal(mangaId, chapterId, pageIndex) { prev ->
            (prev ?: fresh(pageIndex)).copy(state = PageState.CACHED, phase = PagePhase.CACHED, error = null, errorKind = null, updatedAt = now())
        }

    fun pageDone(mangaId: Long, chapterId: Long, pageIndex: Int, servedBy: ProviderId?) =
        terminal(mangaId, chapterId, pageIndex) { prev ->
            (prev ?: fresh(pageIndex)).copy(state = PageState.DONE, phase = PagePhase.DONE, error = null, errorKind = null, servedBy = servedBy, updatedAt = now())
        }

    fun pageSkipped(mangaId: Long, chapterId: Long, pageIndex: Int, reason: String) =
        terminal(mangaId, chapterId, pageIndex) { prev ->
            (prev ?: fresh(pageIndex)).copy(state = PageState.SKIPPED, phase = PagePhase.SKIPPED, error = reason, errorKind = PageOutcomeKind.NOT_ACTIONABLE.name, updatedAt = now())
        }

    fun pageFailed(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        error: String,
        kind: PageOutcomeKind,
    ) = terminal(mangaId, chapterId, pageIndex) { prev ->
        (prev ?: fresh(pageIndex)).copy(
            state = PageState.FAILED,
            phase = PagePhase.FAILED,
            error = error,
            errorKind = kind.name,
            updatedAt = now(),
        )
    }

    /** Clears retryable failures for [pageIndexes] (the zero page-set means "nothing to clear"). */
    fun updateForRetry(mangaId: Long, chapterId: Long, pageIndexes: Set<Int>) {
        if (pageIndexes.isEmpty()) return
        mutate(mangaId, chapterId) {
            it.copy(pages = it.pages.filterKeys { index -> index !in pageIndexes }, lastError = null, lastUpdated = now())
        }
    }

    /** Page indexes whose failure is worth retrying, for the retry command. */
    fun retryablePages(mangaId: Long, chapterId: Long): Set<Int> =
        chapterStatus(mangaId, chapterId)?.pages
            ?.filterValues { it.isRetryable }
            ?.keys
            ?.toSet()
            ?: emptySet()

    private fun fresh(pageIndex: Int) = PageStatus(pageIndex = pageIndex, state = PageState.TRANSLATING)

    private fun terminal(mangaId: Long, chapterId: Long, pageIndex: Int, transform: (PageStatus?) -> PageStatus) =
        mutate(mangaId, chapterId) {
            it.copy(lastUpdated = now(), pages = it.pages + (pageIndex to transform(it.pages[pageIndex])))
        }

    private fun mutate(mangaId: Long, chapterId: Long, transform: (ChapterStatus) -> ChapterStatus) {
        _chapters.update { map ->
            val key = mangaId to chapterId
            map + (key to transform(map[key] ?: ChapterStatus(mangaId = mangaId, chapterId = chapterId)))
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
