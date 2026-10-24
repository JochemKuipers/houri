package tachiyomi.data.chapter

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.BookmarkColor
import tachiyomi.domain.chapter.model.Chapter

object ChapterMapper {
    fun mapChapter(
        id: Long,
        mangaId: Long,
        remoteUrl: String,
        remoteName: String,
        remoteScanlator: String?,
        remoteChapterNumber: Double,
        remoteDateUpload: Long,
        remoteOrder: Long,
        remoteMemo: JsonObject,
        userRead: Boolean,
        userBookmark: Boolean,
        // KMK -->
        userBookmarkColor: Long,
        // KMK <--
        userLastPageRead: Long,
        stateDateFetch: Long,
        // SY --> The sync-era columns this fork still ships
        lastModifiedAt: Long,
        version: Long,
        @Suppress("UNUSED_PARAMETER")
        isSyncing: Long,
        // SY <--
    ): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        read = userRead,
        bookmark = userBookmark,
        lastPageRead = userLastPageRead,
        dateFetch = stateDateFetch,
        sourceOrder = remoteOrder,
        url = remoteUrl,
        name = remoteName,
        dateUpload = remoteDateUpload,
        chapterNumber = remoteChapterNumber,
        scanlator = remoteScanlator,
        lastModifiedAt = lastModifiedAt,
        version = version,
        memo = remoteMemo,
        // KMK -->
        bookmarkColor = BookmarkColor.fromValue(userBookmarkColor),
        // KMK <--
    )
}
