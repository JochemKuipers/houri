package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.system.toast
import exh.source.isMergedSourceId
import kotlinx.coroutines.CoroutineScope
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.BookmarkColor
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal

/**
 * Chapter mutations on the manga details screen, extracted from [MangaViewModel] following the
 * [ChapterDownloadsController]/[ChapterSelectionController] seam: the controller receives the
 * interactors it needs plus read/write accessors for the ViewModel's success state, and owns
 * everything that mutates chapters (read status, bookmarks, colors, deletion, library reset).
 *
 * The ViewModel keeps scope-dependent glue (trackers auto-update preference snapshot, selection
 * clearing, local-source refetch) behind the lambdas below.
 */
internal class ChapterActionsController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val snackbarHostState: SnackbarHostState,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val getTracks: GetTracks,
    private val trackChapter: TrackChapter,
    private val deleteChaptersFromDb: DeleteChapters,
    private val downloadManager: DownloadManager,
    private val provideSuccessState: () -> MangaViewModel.State.Success?,
    private val provideMangaId: () -> Long,
    private val provideAutoTrackState: () -> AutoTrackState,
    private val onClearSelection: () -> Unit,
    private val onRefreshTrackers: suspend () -> Unit,
    private val onRefetchLocalChapters: suspend () -> Unit,
) {
    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        onClearSelection()
        if (chapters.isEmpty()) return
        scope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || provideSuccessState()?.hasLoggedInTrackers == false || provideAutoTrackState() == AutoTrackState.NEVER) {
                return@launchIO
            }

            onRefreshTrackers()

            val mangaId = provideMangaId()
            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (provideAutoTrackState() == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Long,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        scope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        onClearSelection()
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        scope.launchNonCancellable {
            try {
                provideSuccessState()?.let { state ->
                    // KMK -->
                    if (isMergedSourceId(state.source.id)) {
                        chapters.groupBy { it.mangaId }.forEach { map ->
                            val manga = state.mergedData?.manga?.get(map.key) ?: return@forEach
                            val source = state.mergedData.sources.find { !isMergedSourceId(it.id) && manga.source == it.id } ?: return@forEach
                            downloadManager.deleteChapters(
                                map.value,
                                manga,
                                source,
                                ignoreCategoryExclusion = true,
                            )
                            if (source.isLocal()) {
                                // Refresh chapters state for Local source. Runs only after
                                // deleteChapters has actually removed the files — it suspends —
                                // so the re-listing cannot race the deletion it is reacting to.
                                onRefetchLocalChapters()
                            }
                        }
                    } else {
                        // KMK <--
                        downloadManager.deleteChapters(
                            chapters,
                            state.manga,
                            state.source,
                            // KMK -->
                            ignoreCategoryExclusion = true,
                            // KMK <--
                        )
                        // KMK -->
                        if (state.source.isLocal()) {
                            // Refresh chapters state for Local source. Runs only after
                            // deleteChapters has actually removed the files — it suspends —
                            // so the re-listing cannot race the deletion it is reacting to.
                            onRefetchLocalChapters()
                        }
                        // KMK <--
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    // KMK -->
    fun clearManga(
        deleteDownload: Boolean,
        removeChapters: Boolean,
    ) {
        if (deleteDownload) {
            deleteDownloadedData()
        }
        if (removeChapters) {
            removeChaptersDatabase()
        }
    }

    private fun deleteDownloadedData() {
        scope.launchNonCancellable {
            try {
                provideSuccessState()?.let { state ->
                    if (isMergedSourceId(state.source.id)) {
                        state.mergedData?.manga
                            ?.forEach { (_, manga) ->
                                val source = state.mergedData.sources.find { !isMergedSourceId(it.id) && manga.source == it.id } ?: return@forEach

                                downloadManager.deleteManga(
                                    manga = manga,
                                    source = source,
                                    removeQueued = true,
                                )
                                if (source.isLocal()) {
                                    // Refresh chapters state for Local source. deleteManga
                                    // suspends until the folder is gone, so this re-listing
                                    // cannot open a file the delete is about to remove.
                                    onRefetchLocalChapters()
                                }
                            }
                    } else {
                        downloadManager.deleteManga(
                            manga = state.manga,
                            source = state.source,
                            removeQueued = true,
                        )
                        if (state.source.isLocal()) {
                            // Refresh chapters state for Local source. deleteManga suspends
                            // until the folder is gone, so this re-listing cannot open a file
                            // the delete is about to remove.
                            onRefetchLocalChapters()
                        }
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun removeChaptersDatabase() {
        scope.launchNonCancellable {
            try {
                provideSuccessState()?.chapters?.map { it.id }
                    ?.let { deleteChaptersFromDb.await(it) }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }
    // KMK <--

    // KMK -->
    fun setBookmarkColor(chapters: List<Chapter>, color: BookmarkColor) {
        if (chapters.isEmpty()) return
        scope.launchIO {
            updateChapter.awaitAll(
                chapters.map { it.toChapterUpdate().copy(bookmarkColor = color) },
            )
        }
    }
    // KMK <--
}
