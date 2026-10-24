package tachiyomi.data.manga

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

object MangaMapper {
    // KMK --> Parameters are named after the columns they fill, in the table's order, and every
    // manga query projects the columns in this same order. favorite and date_added became
    // user_favorite_at upstream: a row in the library is one with a timestamp, and the timestamp
    // is the date it was added.
    fun mapManga(
        id: Long,
        sourceId: Long,
        remoteUrl: String,
        remoteTitle: String,
        remoteAuthor: String?,
        remoteArtist: String?,
        remoteDescription: String?,
        remoteGenre: List<String>?,
        remoteStatus: Long,
        remoteCover: String?,
        remoteUpdateStrategy: UpdateStrategy,
        remoteMemo: JsonObject,
        userFavoriteAt: Long?,
        userNotes: String,
        userReaderFlags: Long,
        userChapterFlags: Long,
        stateChapterLastUpdate: Long?,
        stateChapterNextUpdate: Long?,
        stateChapterFetchInterval: Long,
        stateCoverLastModified: Long,
        stateInitialized: Boolean,
        // SY --> The sync-era columns this fork still ships
        lastModifiedAt: Long,
        favoriteModifiedAt: Long?,
        version: Long,
        @Suppress("UNUSED_PARAMETER")
        isSyncing: Long,
        // SY <--
        // KMK -->
        scanlatorPriority: List<String>,
        blacklistedChapters: List<String>,
        rereadCount: Long,
        rereading: Boolean,
        rereadStartedAt: Long,
        scanlatorRangeRules: List<String>,
        isLightNovel: Boolean,
        // KMK <--
    ): Manga = Manga(
        id = id,
        source = sourceId,
        favorite = userFavoriteAt != null,
        lastUpdate = stateChapterLastUpdate ?: 0,
        nextUpdate = stateChapterNextUpdate ?: 0,
        fetchInterval = stateChapterFetchInterval.toInt(),
        dateAdded = userFavoriteAt ?: 0,
        viewerFlags = userReaderFlags,
        chapterFlags = userChapterFlags,
        coverLastModified = stateCoverLastModified,
        url = remoteUrl,
        // SY -->
        ogTitle = remoteTitle,
        ogArtist = remoteArtist,
        ogAuthor = remoteAuthor,
        ogThumbnailUrl = remoteCover,
        ogDescription = remoteDescription,
        ogGenre = remoteGenre,
        ogStatus = remoteStatus,
        // SY <--
        updateStrategy = remoteUpdateStrategy,
        initialized = stateInitialized,
        lastModifiedAt = lastModifiedAt,
        favoriteModifiedAt = favoriteModifiedAt,
        version = version,
        notes = userNotes,
        memo = remoteMemo,
        // KMK -->
        scanlatorPriority = scanlatorPriority,
        blacklistedChapters = blacklistedChapters,
        rereadCount = rereadCount.toInt(),
        rereading = rereading,
        rereadStartedAt = rereadStartedAt,
        scanlatorRangeRules = scanlatorRangeRules,
        isLightNovel = isLightNovel,
        // KMK <--
    )

    fun mapLibraryManga(
        id: Long,
        sourceId: Long,
        remoteUrl: String,
        remoteTitle: String,
        remoteAuthor: String?,
        remoteArtist: String?,
        remoteDescription: String?,
        remoteGenre: List<String>?,
        remoteStatus: Long,
        remoteCover: String?,
        remoteUpdateStrategy: UpdateStrategy,
        remoteMemo: JsonObject,
        userFavoriteAt: Long?,
        userNotes: String,
        userReaderFlags: Long,
        userChapterFlags: Long,
        stateChapterLastUpdate: Long?,
        stateChapterNextUpdate: Long?,
        stateChapterFetchInterval: Long,
        stateCoverLastModified: Long,
        stateInitialized: Boolean,
        // SY --> The sync-era columns this fork still ships
        lastModifiedAt: Long,
        favoriteModifiedAt: Long?,
        version: Long,
        isSyncing: Long,
        // SY <--
        // KMK -->
        scanlatorPriority: List<String>,
        blacklistedChapters: List<String>,
        rereadCount: Long,
        rereading: Boolean,
        rereadStartedAt: Long,
        scanlatorRangeRules: List<String>,
        isLightNovel: Boolean,
        // KMK <--
        totalCount: Long,
        readCount: Long,
        latestUpload: Long,
        chapterFetchedAt: Long,
        lastRead: Long,
        bookmarkCount: Long,
        // KMK -->
        bookmarkedReadCount: Long,
        // KMK <--
        categories: String,
    ): LibraryManga = LibraryManga(
        manga = mapManga(
            id = id,
            sourceId = sourceId,
            remoteUrl = remoteUrl,
            remoteTitle = remoteTitle,
            remoteAuthor = remoteAuthor,
            remoteArtist = remoteArtist,
            remoteDescription = remoteDescription,
            remoteGenre = remoteGenre,
            remoteStatus = remoteStatus,
            remoteCover = remoteCover,
            remoteUpdateStrategy = remoteUpdateStrategy,
            remoteMemo = remoteMemo,
            userFavoriteAt = userFavoriteAt,
            userNotes = userNotes,
            userReaderFlags = userReaderFlags,
            userChapterFlags = userChapterFlags,
            stateChapterLastUpdate = stateChapterLastUpdate,
            stateChapterNextUpdate = stateChapterNextUpdate,
            stateChapterFetchInterval = stateChapterFetchInterval,
            stateCoverLastModified = stateCoverLastModified,
            stateInitialized = stateInitialized,
            // SY -->
            lastModifiedAt = lastModifiedAt,
            favoriteModifiedAt = favoriteModifiedAt,
            version = version,
            isSyncing = isSyncing,
            // SY <--
            // KMK -->
            scanlatorPriority = scanlatorPriority,
            blacklistedChapters = blacklistedChapters,
            rereadCount = rereadCount,
            rereading = rereading,
            rereadStartedAt = rereadStartedAt,
            scanlatorRangeRules = scanlatorRangeRules,
            isLightNovel = isLightNovel,
            // KMK <--
        ),
        categories = categories.split(",").map { it.toLong() },
        totalChapters = totalCount,
        readCount = readCount,
        bookmarkCount = bookmarkCount,
        // KMK -->
        bookmarkReadCount = bookmarkedReadCount,
        chapterFlags = userChapterFlags,
        // KMK <--
        latestUpload = latestUpload,
        chapterFetchedAt = chapterFetchedAt,
        lastRead = lastRead,
    )

    fun mapMangaWithChapterCount(
        id: Long,
        sourceId: Long,
        remoteUrl: String,
        remoteTitle: String,
        remoteAuthor: String?,
        remoteArtist: String?,
        remoteDescription: String?,
        remoteGenre: List<String>?,
        remoteStatus: Long,
        remoteCover: String?,
        remoteUpdateStrategy: UpdateStrategy,
        remoteMemo: JsonObject,
        userFavoriteAt: Long?,
        userNotes: String,
        userReaderFlags: Long,
        userChapterFlags: Long,
        stateChapterLastUpdate: Long?,
        stateChapterNextUpdate: Long?,
        stateChapterFetchInterval: Long,
        stateCoverLastModified: Long,
        stateInitialized: Boolean,
        // SY --> The sync-era columns this fork still ships
        lastModifiedAt: Long,
        favoriteModifiedAt: Long?,
        version: Long,
        isSyncing: Long,
        // SY <--
        // KMK -->
        scanlatorPriority: List<String>,
        blacklistedChapters: List<String>,
        rereadCount: Long,
        rereading: Boolean,
        rereadStartedAt: Long,
        scanlatorRangeRules: List<String>,
        isLightNovel: Boolean,
        // KMK <--
        chapterCount: Long,
    ): MangaWithChapterCount = MangaWithChapterCount(
        manga = mapManga(
            id = id,
            sourceId = sourceId,
            remoteUrl = remoteUrl,
            remoteTitle = remoteTitle,
            remoteAuthor = remoteAuthor,
            remoteArtist = remoteArtist,
            remoteDescription = remoteDescription,
            remoteGenre = remoteGenre,
            remoteStatus = remoteStatus,
            remoteCover = remoteCover,
            remoteUpdateStrategy = remoteUpdateStrategy,
            remoteMemo = remoteMemo,
            userFavoriteAt = userFavoriteAt,
            userNotes = userNotes,
            userReaderFlags = userReaderFlags,
            userChapterFlags = userChapterFlags,
            stateChapterLastUpdate = stateChapterLastUpdate,
            stateChapterNextUpdate = stateChapterNextUpdate,
            stateChapterFetchInterval = stateChapterFetchInterval,
            stateCoverLastModified = stateCoverLastModified,
            stateInitialized = stateInitialized,
            // SY -->
            lastModifiedAt = lastModifiedAt,
            favoriteModifiedAt = favoriteModifiedAt,
            version = version,
            isSyncing = isSyncing,
            // SY <--
            // KMK -->
            scanlatorPriority = scanlatorPriority,
            blacklistedChapters = blacklistedChapters,
            rereadCount = rereadCount,
            rereading = rereading,
            rereadStartedAt = rereadStartedAt,
            scanlatorRangeRules = scanlatorRangeRules,
            isLightNovel = isLightNovel,
            // KMK <--
        ),
        chapterCount = chapterCount,
    )
}
