package eu.kanade.presentation.manga

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MissingChapterCountListItem
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MergedMangaData
import exh.metadata.MetadataUtil
import exh.source.isEhBasedManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.isLocal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The chapter-list pieces of the manga screen shared by [MangaScreenSmallImpl] and
 * [MangaScreenLargeImpl], extracted from [MangaScreen] so both layouts compose the same
 * rendering without one reaching into the other's file.
 */

@Composable
internal fun SharedMangaBottomActionMenu(
    selected: List<ChapterList.Item>,
    onMultiBookmarkClicked: (List<Chapter>, bookmarked: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<Chapter>, markAsRead: Boolean) -> Unit,
    onMarkPreviousAsReadClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onMultiDeleteClicked: (List<Chapter>) -> Unit,
    // KMK -->
    onBlacklistClicked: (List<Chapter>) -> Unit,
    onBookmarkColorClicked: ((List<Chapter>) -> Unit)? = null,
    // KMK <--
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = modifier.fillMaxWidth(fillFraction),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAll { it.chapter.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, true)
        }.takeIf { selected.fastAny { !it.chapter.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected.fastMap { it.chapter }, false)
        }.takeIf { selected.fastAny { it.chapter.read || it.chapter.lastPageRead > 0L } },
        onMarkPreviousAsReadClicked = {
            onMarkPreviousAsReadClicked(selected[0].chapter)
        }.takeIf { selected.size == 1 },
        onDownloadClicked = {
            onDownloadChapter!!(selected.toList(), ChapterDownloadAction.START)
        }.takeIf {
            onDownloadChapter != null && selected.fastAny { it.downloadState != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected.fastMap { it.chapter })
        }.takeIf {
            selected.fastAny { it.downloadState == Download.State.DOWNLOADED }
        },
        // KMK -->
        onBlacklistClicked = {
            onBlacklistClicked(selected.fastMap { it.chapter })
        },
        onBookmarkColorClicked = onBookmarkColorClicked?.let { handler ->
            { handler(selected.fastMap { it.chapter }) }
        }.takeIf { onBookmarkColorClicked != null && selected.fastAll { it.chapter.bookmark } },
        // KMK <--
    )
}

internal fun LazyListScope.sharedChapterItems(
    manga: Manga,
    mergedData: MergedMangaData?,
    chapters: List<ChapterList>,
    isAnyChapterSelected: Boolean,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    // SY -->
    alwaysShowReadingProgress: Boolean,
    // SY <--
    onChapterClicked: (Chapter) -> Unit,
    onDownloadChapter: ((List<ChapterList.Item>, ChapterDownloadAction) -> Unit)?,
    onChapterSelected: (ChapterList.Item, Boolean, Boolean) -> Unit,
    onChapterSwipe: (ChapterList.Item, LibraryPreferences.ChapterSwipeAction) -> Unit,
) {
    items(
        items = chapters,
        key = { item ->
            when (item) {
                is ChapterList.MissingCount -> "missing-count-${manga.id}-${item.count}-${item.hashCode()}"
                is ChapterList.Item -> "chapter-${manga.id}-${item.id}"
            }
        },
        contentType = { MangaScreenItem.CHAPTER },
    ) { item ->
        val haptic = LocalHapticFeedback.current

        when (item) {
            is ChapterList.MissingCount -> {
                MissingChapterCountListItem(count = item.count)
            }
            is ChapterList.Item -> {
                MangaChapterListItem(
                    title = if (manga.displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                        stringResource(
                            MR.strings.display_mode_chapter,
                            formatChapterNumber(item.chapter.chapterNumber),
                        )
                    } else {
                        item.chapter.name
                    },
                    date = item.chapter.dateUpload
                        .takeIf { it > 0L }
                        ?.let {
                            // SY -->
                            if (manga.isEhBasedManga()) {
                                MetadataUtil.EX_DATE_FORMAT
                                    .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                            } else {
                                relativeDateText(item.chapter.dateUpload)
                            }
                            // SY <--
                        },
                    readProgress = item.chapter.lastPageRead
                        .takeIf {
                            /* SY --> */(!item.chapter.read || alwaysShowReadingProgress)/* SY <-- */ && it > 0L
                        }
                        ?.let {
                            stringResource(
                                MR.strings.chapter_progress,
                                it + 1,
                            )
                        },
                    scanlator = item.chapter.scanlator.takeIf {
                        !it.isNullOrBlank() /* SY --> */ && item.showScanlator /* SY <-- */
                    },
                    // SY -->
                    sourceName = item.sourceName,
                    // SY <--
                    read = item.chapter.read,
                    bookmark = item.chapter.bookmark,
                    // KMK -->
                    bookmarkColor = item.chapter.bookmarkColor,
                    // KMK <--
                    selected = item.selected,
                    downloadIndicatorEnabled =
                    !isAnyChapterSelected && !(mergedData?.manga?.get(item.chapter.mangaId) ?: manga).isLocal(),
                    downloadStateProvider = { item.downloadState },
                    downloadProgressProvider = { item.downloadProgress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onChapterSelected(item, !item.selected, true)
                    },
                    onClick = {
                        onChapterItemClick(
                            chapterItem = item,
                            isAnyChapterSelected = isAnyChapterSelected,
                            onToggleSelection = { onChapterSelected(item, !item.selected, false) },
                            onChapterClicked = onChapterClicked,
                        )
                    },
                    onDownloadClick = if (onDownloadChapter != null) {
                        { onDownloadChapter(listOf(item), it) }
                    } else {
                        null
                    },
                    onChapterSwipe = {
                        onChapterSwipe(item, it)
                    },
                    // KMK -->
                    translationProgress = item.translationProgress,
                    isTranslating = item.isTranslating,
                    isTranslationError = item.isTranslationError,
                    // KMK <--
                )
            }
        }
    }
}

internal fun onChapterItemClick(
    chapterItem: ChapterList.Item,
    isAnyChapterSelected: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onChapterClicked: (Chapter) -> Unit,
) {
    when {
        chapterItem.selected -> onToggleSelection(false)
        isAnyChapterSelected -> onToggleSelection(true)
        else -> onChapterClicked(chapterItem.chapter)
    }
}
