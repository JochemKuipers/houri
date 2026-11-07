package eu.kanade.tachiyomi.ui.library.controller

import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilterNot
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import exh.source.isMergedSourceId
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga

/**
 * Bulk download actions for the library selection, extracted from
 * [eu.kanade.tachiyomi.ui.library.LibraryViewModel].
 *
 * The queue-deduplication check reads two different DownloadManager queries for the merged
 * and plain paths (merged entries queue as [chapter.id] members while plain ones go through
 * [DownloadManager.getQueuedDownloadOrNull]); both existed that way before extraction and are
 * preserved as-is.
 */
class LibraryDownloadController(
    private val downloadManager: DownloadManager,
    private val getNextChapters: GetNextChapters,
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId,
    private val getMergedMangaById: GetMergedMangaById,
    private val smartScanlatorMergeEnabled: () -> Boolean,
) {
    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    suspend fun performDownloadAction(action: DownloadAction, mangas: List<Manga>) {
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(1, mangas)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(5, mangas)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(10, mangas)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(25, mangas)
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(null, mangas)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters(mangas)
        }
    }

    private suspend fun downloadNextChapters(amount: Int?, mangas: List<Manga>) {
        mangas.forEach { manga ->
            // SY -->
            if (isMergedSourceId(manga.source)) {
                val mergedMangas = getMergedMangaById.await(manga.id)
                    .associateBy { it.id }
                getNextChapters.await(manga.id, mergeEnabled = smartScanlatorMergeEnabled())
                    .let { if (amount != null) it.take(amount) else it }
                    .groupBy { it.mangaId }
                    .forEach ab@{ (mangaId, chapters) ->
                        val mergedManga = mergedMangas[mangaId] ?: return@ab
                        val downloadChapters = chapters.fastFilterNot { chapter ->
                            downloadManager.queueState.value.fastAny { chapter.id == it.chapter.id } ||
                                downloadManager.isChapterDownloaded(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    mergedManga.ogTitle,
                                    mergedManga.source,
                                )
                        }

                        downloadManager.downloadChapters(mergedManga, downloadChapters)
                    }

                return@forEach
            }

            // SY <--
            val chapters = getNextChapters.await(manga.id, mergeEnabled = smartScanlatorMergeEnabled())
                .fastFilterNot { chapter ->
                    downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                        downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            // SY -->
                            manga.ogTitle,
                            // SY <--
                            manga.source,
                        )
                }
                .let { if (amount != null) it.take(amount) else it }

            downloadManager.downloadChapters(manga, chapters)
        }
    }

    private suspend fun downloadBookmarkedChapters(mangas: List<Manga>) {
        mangas.forEach { manga ->
            // SY -->
            if (isMergedSourceId(manga.source)) {
                val mergedMangas = getMergedMangaById.await(manga.id)
                    .associateBy { it.id }
                getBookmarkedChaptersByMangaId.await(manga.id)
                    .groupBy { it.mangaId }
                    .forEach ab@{ (mangaId, chapters) ->
                        val mergedManga = mergedMangas[mangaId] ?: return@ab
                        val downloadChapters = chapters.fastFilterNot { chapter ->
                            downloadManager.queueState.value.fastAny { chapter.id == it.chapter.id } ||
                                downloadManager.isChapterDownloaded(
                                    chapter.name,
                                    chapter.scanlator,
                                    chapter.url,
                                    mergedManga.ogTitle,
                                    mergedManga.source,
                                )
                        }

                        downloadManager.downloadChapters(mergedManga, downloadChapters)
                    }

                return@forEach
            }
            // SY <--

            val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                .fastFilterNot { chapter ->
                    downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                        downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            // SY -->
                            manga.ogTitle,
                            // SY <--
                            manga.source,
                        )
                }
            downloadManager.downloadChapters(manga, chapters)
        }
    }
}
