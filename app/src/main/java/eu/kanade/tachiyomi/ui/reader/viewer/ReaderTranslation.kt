package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.UpscaleReaderHook
import kotlinx.coroutines.CoroutineScope
import mihon.app.di.globalAppGraph
import mtl.sched.PageOutcome
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext

/**
 * Shared entry point for MTL translation in the non-WebGPU readers (pager + webtoon).
 *
 * Cut over to the new spine: every call goes through [MtlController], and the pipeline's
 * cache fast-path answers re-binds without re-running anything — the old session-level
 * "already done" checks and the separate [getTranslatedBytes] lookup were that cache's
 * older, duplicated self.
 *
 * Unlike the WebGPU viewer (which bakes translated images into its own [ImagePage] pipeline),
 * these readers display pages through [ReaderPageImageView]; this helper submits the original
 * encoded bytes and hands the translated WEBP back on the main thread for the holder to swap in.
 */
// Top-level rather than a companion: `ReaderTranslation` is an `object`, and a standalone
// object cannot declare one.
private const val TRANSLATION_SPRINT_ID = "rotating_weekly_extra_3"
private const val TRANSLATION_SPRINT_MIN_CHAPTERS = 10

object ReaderTranslation : PageTranslator {

    /**
     * Whether anything on the display path would consume [originalBytes] for [page].
     *
     * Callers use this to skip materialising the encoded page when neither MTL nor upscaling
     * can apply. Reading the page costs a full decode-size copy, so on the default path
     * (both features off) it is pure waste multiplied by every page scrolled past.
     */
    suspend fun needsOriginalBytes(page: ReaderPage): Boolean {
        val mangaId = page.chapter.chapter.manga_id ?: 0L
        if (globalAppGraph.mtlController.isEnabledForManga(mangaId)) return true
        return UpscaleReaderHook.isUpscaleActive(mangaId)
    }

    /**
     * Submits [page] for translation when the controller's gates pass. The spine owns the
     * rest: cache hits answer immediately, in-flight pages deduplicate to the same deferred,
     * and a skip/failure leaves the original page in place with the status hub recording why.
     */
    override fun translate(
        scope: CoroutineScope,
        page: ReaderPage,
        originalBytes: ByteArray?,
        onResult: (ByteArray) -> Unit,
    ) {
        if (originalBytes == null || originalBytes.isEmpty()) return

        val controller = globalAppGraph.mtlController
        val mangaId = page.chapter.chapter.manga_id ?: 0L
        if (!controller.isEnabledForManga(mangaId)) return

        val chapterId = page.chapter.chapter.id ?: 0L
        controller.setChapterTotalPages(mangaId, chapterId, page.chapter.pages?.size ?: 0)

        scope.launchIO {
            val outcome = try {
                controller.translatePage(
                    mangaId = mangaId,
                    chapterId = chapterId,
                    pageIndex = page.index,
                    imageBytes = originalBytes,
                ).await()
            } catch (_: Exception) {
                return@launchIO
            }
            val bytes = when (outcome) {
                is PageOutcome.Done -> outcome.imageBytes
                is PageOutcome.Cached -> outcome.imageBytes
                else -> null
            } ?: return@launchIO
            withUIContext { onResult(bytes) }
            countChapterOnceComplete(mangaId, chapterId)
        }
    }

    // Chapters already counted this session. pendingCount settles at 0 and stays there, so
    // without this the last page of a chapter would count once per remaining page.
    private val countedChapters = mutableSetOf<Pair<Long, Long>>()

    // Manga whose translation-sprint tier has already been offered this week, so the chapter
    // count is fetched once per series rather than once per translated chapter.
    private val sprintChecked = mutableSetOf<Long>()

    private suspend fun countChapterOnceComplete(mangaId: Long, chapterId: Long) {
        runCatching {
            val status = globalAppGraph.mtlStatus.chapterStatus(mangaId, chapterId) ?: return
            if (status.pendingCount != 0 || status.translatedCount == 0) return
            if (!countedChapters.add(mangaId to chapterId)) return
            globalAppGraph.achievementManager.incrementCounter("translated_chapters")
            globalAppGraph.rotatingAchievementPool.markProgress("rotating_daily_translate_2")
            globalAppGraph.rotatingAchievementPool.markProgress("rotating_weekly_translate_10")
            markTranslationSprint(mangaId)
        }
    }

    /**
     * "Translate a 10+ chapter manga this week". The series length is the whole condition, so
     * it is read from the chapter list - once per series per session, and only when the tier
     * could still be live.
     */
    private suspend fun markTranslationSprint(mangaId: Long) {
        if (mangaId <= 0L) return
        // Checked before the memo: the id is usually not on show, and there is no reason to
        // count a series' chapters for a tier the user cannot currently see. Ordering it first
        // also keeps the memo meaning "this series has been evaluated", not "was considered".
        if (!globalAppGraph.rotatingAchievementPool.isActive(TRANSLATION_SPRINT_ID)) return
        if (!sprintChecked.add(mangaId)) return
        val chapterCount = runCatching {
            globalAppGraph.getChaptersByMangaId.await(mangaId).size
        }.getOrDefault(0)
        if (chapterCount >= TRANSLATION_SPRINT_MIN_CHAPTERS) {
            globalAppGraph.rotatingAchievementPool.markProgress(TRANSLATION_SPRINT_ID)
        }
    }
}
