package eu.kanade.tachiyomi.ui.library.controller

import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.MergeMangaBySmartSearch
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.util.retainCovers
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.source.isEhBasedManga
import exh.source.isMergedSourceId
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import exh.util.nullIfBlank
import kotlinx.collections.immutable.PersistentList
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.category.interactor.SetMangaCategories as SetMangaCategoriesInteractor

/**
 * Bulk actions over the library's current selection, extracted from
 * [eu.kanade.tachiyomi.ui.library.LibraryViewModel].
 *
 * Each action is a suspend function over the explicit manga list (or selection id set); the
 * ViewModel owns scope, selection clearing and any result surfacing.
 */
class LibrarySelectionController(
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val setReadStatus: SetReadStatus,
    private val updateManga: UpdateManga,
    private val setMangaCategories: SetMangaCategoriesInteractor,
    // SY -->
    private val sourcePreferences: SourcePreferences,
    // SY <--
    private val coverCache: CoverCache,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val getMergedMangaById: GetMergedMangaById,
    private val mergeMangaBySmartSearch: MergeMangaBySmartSearch,
) {
    // SY -->
    /**
     * Strips bracket/brace/pipe noise from EH/NH-style titles of the selection, storing the
     * result as custom info (so the original title stays recoverable).
     */
    fun cleanTitles(mangas: List<Manga>) {
        mangas.fastFilter {
            it.isEhBasedManga() ||
                it.source in nHentaiSourceIds
        }.fastForEach { manga ->
            val editedTitle = manga.title
                .replace(CLEAN_TITLE_BRACKETS, "").trim()
                .replace(CLEAN_TITLE_PARENS, "").trim()
                .replace(CLEAN_TITLE_BRACES, "").trim()
                .let {
                    if (it.contains("|")) {
                        it.replace(CLEAN_TITLE_PIPE, "").trim()
                    } else {
                        it
                    }
                }
            if (manga.title == editedTitle) return@fastForEach
            val mangaInfo = CustomMangaInfo(
                id = manga.id,
                title = editedTitle.nullIfBlank(),
                author = manga.author.takeUnless { it == manga.ogAuthor },
                artist = manga.artist.takeUnless { it == manga.ogArtist },
                thumbnailUrl = manga.thumbnailUrl.takeUnless { it == manga.ogThumbnailUrl },
                description = manga.description.takeUnless { it == manga.ogDescription },
                genre = manga.genre.takeUnless { it == manga.ogGenre },
                status = manga.status.takeUnless { it == manga.ogStatus },
            )

            setCustomMangaInfo.set(mangaInfo)
        }
    }

    /**
     * Marks the selected MangaDex entries as READING on MangaDex itself.
     */
    suspend fun syncMangaToDex(mangas: List<Manga>) {
        MdUtil.getEnabledMangaDex(sourcePreferences, sourceManager)?.let { mdex ->
            mangas.fastFilter { it.source in mangaDexSourceIds }.fastForEach { manga ->
                mdex.updateFollowStatus(MdUtil.getMangaId(manga.url), FollowStatus.READING)
            }
        }
    }
    // SY <--

    /**
     * Clears every custom-info override of the given manga ids, restoring the source values.
     */
    fun resetInfo(mangaIds: Set<Long>) {
        mangaIds.forEach { id ->
            val mangaInfo = CustomMangaInfo(
                id = id,
                title = null,
                author = null,
                artist = null,
                thumbnailUrl = null,
                description = null,
                genre = null,
                status = null,
            )

            setCustomMangaInfo.set(mangaInfo)
        }
    }

    /**
     * Marks mangas' chapters read status.
     */
    suspend fun markRead(mangas: List<Manga>, read: Boolean) {
        mangas.forEach { manga ->
            setReadStatus.await(
                manga = manga,
                read = read,
            )
        }
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    suspend fun removeMangas(
        mangas: List<Manga>,
        deleteFromLibrary: Boolean,
        deleteChapters: Boolean,
    ) {
        if (deleteFromLibrary) {
            val toDelete = mangas
                .distinctBy { it.id }
                .map {
                    // retainCovers stamps the files for the retention window and returns a copy
                    // carrying the new coverLastModified. The single-manga path forwards that
                    // stamp to the DB; discarding it here left the bulk path bumping nothing,
                    // so a cover still on screen outlived the removal that should have redrawn it.
                    val retained = it.retainCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                        coverLastModified = retained.coverLastModified
                            .takeIf { stamp -> stamp != it.coverLastModified },
                    )
                }
            updateManga.awaitAll(toDelete)
            // KMK --> Counted after the update lands. The "without finishing" half is not
            // re-derived here: fetching a status per removal would be a round trip per manga
            // on a bulk delete, and the counter is a shame metric that stays correct often
            // enough at 10.
            runCatching {
                mihon.app.di.globalAppGraph.achievementManager
                    .incrementCounter("dropped_unfinished", toDelete.size)
            }
            // KMK <--
        }

        if (deleteChapters) {
            mangas.forEach { manga ->
                val source = sourceManager.get(manga.source) as? HttpSource
                if (source != null) {
                    if (source is MergedSource) {
                        val mergedMangas = getMergedMangaById.await(manga.id)
                        val sources = mergedMangas.distinctBy {
                            it.source
                        }.map { sourceManager.getOrStub(it.source) }
                        mergedMangas.forEach merge@{ mergedManga ->
                            val mergedSource =
                                sources.firstOrNull { mergedManga.source == it.id } as? HttpSource ?: return@merge
                            downloadManager.deleteManga(mergedManga, mergedSource)
                        }
                    } else {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    suspend fun setMangaCategories(
        mangaList: List<Manga>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
        categoryIdsOf: suspend (Manga) -> Set<Long>,
    ) {
        mangaList.forEach { manga ->
            val categoryIds = categoryIdsOf(manga)
                .subtract(removeCategories.toSet())
                .plus(addCategories)
                .toList()

            setMangaCategories.await(manga.id, categoryIds)
        }
    }

    // KMK -->
    /**
     * Will get first merged manga in the list as target merging.
     * If there is no merged manga, then it will use the first one in list to create a new target.
     */
    suspend fun smartSearchMerge(selectedMangas: PersistentList<Manga>): Long? {
        val mergedManga = selectedMangas.firstOrNull { isMergedSourceId(it.source) }?.let { listOf(it) }
            ?: emptyList()
        val mergingMangas = selectedMangas.fastFilterNot { isMergedSourceId(it.source) }
        val toMergeMangas = mergedManga + mergingMangas
        if (toMergeMangas.size <= 1) return null

        var mergingMangaId = toMergeMangas.first().id
        for (manga in toMergeMangas.drop(1)) {
            mergingMangaId = mergeMangaBySmartSearch.smartSearchMerge(manga, mergingMangaId).id
        }
        return mergingMangaId
    }
    // KMK <--

    private companion object {
        // SY -->
        val CLEAN_TITLE_BRACKETS = "\\[.*?]".toRegex()
        val CLEAN_TITLE_PARENS = "\\(.*?\\)".toRegex()
        val CLEAN_TITLE_BRACES = "\\{.*?\\}".toRegex()
        val CLEAN_TITLE_PIPE = ".*\\|".toRegex()
        // SY <--
    }
}
