package eu.kanade.tachiyomi.ui.library.controller

import android.content.Context
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMapNotNull
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.library.LOCAL_SOURCE_ID_ALIAS
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text
import exh.source.isMetadataSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.LocalSource

/**
 * The library's text-search pipeline, extracted from [eu.kanade.tachiyomi.ui.library.LibraryViewModel].
 *
 * Parses the query through [SearchEngine] (free text + `field:value` namespaces), then matches
 * every visible field of a library entry: title/author/artist/description, source name and id,
 * assigned category and subcategory names, tracker status and service names, genres, and the
 * metadata search tags/titles of metadata sources. Easter-egg queries are answered here too.
 */
class LibrarySearchController(
    private val searchEngine: SearchEngine,
    private val libraryPreferences: LibraryPreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val getTracks: GetTracks,
    private val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata,
    private val getSearchTags: GetSearchTags,
    private val getSearchTitles: GetSearchTitles,
) {
    // SY -->
    suspend fun filter(
        unfiltered: List<LibraryItem>,
        query: String?,
        loggedInTrackServices: Map<Long, TriState>,
        // KMK -->
        categories: List<Category>,
        // KMK <--
    ): List<LibraryItem> {
        return if (unfiltered.isNotEmpty() && !query.isNullOrBlank()) {
            // KMK -->
            val normalisedQuery = query.trim().lowercase()
            if (normalisedQuery == "mango") {
                // Mango easter egg: show manga with "mango" in the title. Counted, not just
                // unlocked - "find the mango easter egg twice" is a tier off the same counter,
                // so a second search is what earns it rather than a second hand-written hook.
                runCatching {
                    mihon.app.di.globalAppGraph.achievementManager.incrementCounter("mango_easter")
                }
                return unfiltered.fastFilter {
                    it.libraryManga.manga.title.lowercase().contains("mango")
                }
            }
            if (normalisedQuery == "pineapple") {
                runCatching {
                    mihon.app.di.globalAppGraph.achievementManager.tryUnlockDirect("secret_pineapple")
                }
            }
            // KMK <--
            // Prepare filter object
            val parsedQuery = searchEngine.parseQuery(
                query,
                enableAst = libraryPreferences.librarySearchAstEnabled().get(),
            )
            // KMK --> "Search by tag": judged on the parsed query rather than the raw
            // string, so it matches how the search actually narrowed the library - a free-text
            // query that merely mentions a tag name does not count.
            if (parsedQuery.any { it is Namespace && it.namespace in TAG_NAMESPACES }) {
                runCatching {
                    mihon.app.di.globalAppGraph.rotatingAchievementPool.markProgress("rotating_daily_extra_2")
                }
            }
            // KMK <--
            val mangaWithMetaIds = getIdsOfFavoriteMangaWithMetadata.await()
            val metaIdSet = mangaWithMetaIds.toHashSet()
            val idsNeedingMeta = unfiltered
                .filter { isMetadataSource(it.libraryManga.manga.source) && it.libraryManga.manga.id in metaIdSet }
                .map { it.libraryManga.manga.id }
            val tagsById = if (idsNeedingMeta.isNotEmpty()) getSearchTags.awaitBulk(idsNeedingMeta) else emptyMap()
            val titlesById = if (idsNeedingMeta.isNotEmpty()) getSearchTitles.awaitBulk(idsNeedingMeta) else emptyMap()
            val tracks = if (loggedInTrackServices.isNotEmpty()) {
                getTracks.await().groupBy { it.mangaId }
            } else {
                emptyMap()
            }
            val sources = unfiltered
                .distinctBy { it.libraryManga.manga.source }
                .fastMapNotNull { sourceManager.get(it.libraryManga.manga.source) }
                .associateBy { it.id }
            withIOContext {
                unfiltered.filter { item ->
                    val mangaId = item.libraryManga.manga.id
                    if (query.startsWith("id:", true)) {
                        return@filter mangaId == query.substringAfter("id:").toLongOrNull()
                    }
                    val sourceId = item.libraryManga.manga.source
                    if (query.startsWith("src:", true)) {
                        val querySource = query.substringAfter("src:")
                        return@filter if (querySource.equals(LOCAL_SOURCE_ID_ALIAS, ignoreCase = true)) {
                            sourceId == LocalSource.ID
                        } else {
                            sourceId == querySource.toLongOrNull()
                        }
                    }
                    if (isMetadataSource(sourceId) && mangaId in metaIdSet) {
                        filterManga(
                            queries = parsedQuery,
                            libraryManga = item.libraryManga,
                            tracks = tracks[mangaId],
                            source = sources[sourceId],
                            checkGenre = false,
                            searchTags = tagsById[mangaId],
                            searchTitles = titlesById[mangaId],
                            loggedInTrackServices = loggedInTrackServices,
                            // KMK -->
                            allCategories = categories,
                            // KMK <--
                        )
                    } else {
                        filterManga(
                            queries = parsedQuery,
                            libraryManga = item.libraryManga,
                            tracks = tracks[mangaId],
                            source = sources[sourceId],
                            loggedInTrackServices = loggedInTrackServices,
                            // KMK -->
                            allCategories = categories,
                            // KMK <--
                        )
                    }
                }
            }
        } else {
            unfiltered
        }
    }
    // SY <--

    private fun filterManga(
        queries: List<QueryComponent>,
        libraryManga: LibraryManga,
        tracks: List<Track>?,
        source: Source?,
        checkGenre: Boolean = true,
        searchTags: List<SearchTag>? = null,
        searchTitles: List<SearchTitle>? = null,
        loggedInTrackServices: Map<Long, TriState>,
        // KMK -->
        allCategories: List<Category> = emptyList(),
        // KMK <--
    ): Boolean {
        val manga = libraryManga.manga
        val sourceIdString = manga.source.takeUnless { it == LocalSource.ID }?.toString()
        val genre = if (checkGenre) manga.genre.orEmpty() else emptyList()
        val context = mihon.app.di.globalAppGraph.context
        // KMK --> names of categories/subcategories this entry belongs to
        val categoryById = allCategories.associateBy { it.id }
        val assignedCategories = libraryManga.categories.mapNotNull { categoryById[it] }
        // KMK <--
        return queries.all { queryComponent ->
            when (queryComponent.excluded) {
                false -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        manga.title.contains(query, true) ||
                            (manga.author?.contains(query, true) == true) ||
                            (manga.artist?.contains(query, true) == true) ||
                            (manga.description?.contains(query, true) == true) ||
                            (source?.name?.contains(query, true) == true) ||
                            (sourceIdString != null && sourceIdString == query) ||
                            // KMK --> plain text also matches assigned category/subcategory names
                            assignedCategories.fastAny { it.name.contains(query, true) } ||
                            // KMK <--
                            (
                                loggedInTrackServices.isNotEmpty() &&
                                    tracks != null &&
                                    filterTracks(query, tracks, context)
                                ) ||
                            (genre.fastAny { it.contains(query, true) }) ||
                            (searchTags?.fastAny { it.name.contains(query, true) } == true) ||
                            (searchTitles?.fastAny { it.title.contains(query, true) } == true)
                    }
                    is Namespace -> {
                        // KMK --> match `field:value` against library fields first,
                        // metadata search tags second (previously tags-only, which
                        // filtered out every non-metadata entry for a:/g:/genre:)
                        matchNamespace(
                            namespace = queryComponent.namespace,
                            tag = queryComponent.tag?.asQuery().orEmpty(),
                            libraryManga = libraryManga,
                            genre = genre,
                            tracks = tracks,
                            source = source,
                            sourceIdString = sourceIdString,
                            searchTags = searchTags,
                            searchTitles = searchTitles,
                            // KMK -->
                            assignedCategories = assignedCategories,
                            // KMK <--
                        )
                        // KMK <--
                    }
                    else -> true
                }
                true -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        query.isBlank() ||
                            (
                                (!manga.title.contains(query, true)) &&
                                    (manga.author?.contains(query, true) != true) &&
                                    (manga.artist?.contains(query, true) != true) &&
                                    (manga.description?.contains(query, true) != true) &&
                                    (source?.name?.contains(query, true) != true) &&
                                    (sourceIdString != null && sourceIdString != query) &&
                                    // KMK --> exclusion mirrors the category match above
                                    (!assignedCategories.fastAny { it.name.contains(query, true) }) &&
                                    // KMK <--
                                    (
                                        loggedInTrackServices.isEmpty() ||
                                            tracks == null ||
                                            !filterTracks(query, tracks, context)
                                        ) &&
                                    (!genre.fastAny { it.contains(query, true) }) &&
                                    (searchTags?.fastAny { it.name.contains(query, true) } != true) &&
                                    (searchTitles?.fastAny { it.title.contains(query, true) } != true)
                                )
                    }
                    is Namespace -> {
                        // KMK --> exclusion is the negation of the same
                        // library-fields-first match used above
                        !matchNamespace(
                            namespace = queryComponent.namespace,
                            tag = queryComponent.tag?.asQuery().orEmpty(),
                            libraryManga = libraryManga,
                            genre = genre,
                            tracks = tracks,
                            source = source,
                            sourceIdString = sourceIdString,
                            searchTags = searchTags,
                            searchTitles = searchTitles,
                            // KMK -->
                            assignedCategories = assignedCategories,
                            // KMK <--
                        )
                        // KMK <--
                    }
                    else -> true
                }
            }
        }
    }

    private fun filterTracks(constraint: String, tracks: List<Track>, context: Context): Boolean {
        return tracks.fastAny { track ->
            val trackService = trackerManager.get(track.trackerId)
            if (trackService != null) {
                val status = trackService.getStatus(track.status)?.let {
                    context.stringResource(it)
                }
                val name = trackerManager.get(track.trackerId)?.name
                status?.contains(constraint, true) == true || name?.contains(constraint, true) == true
            } else {
                false
            }
        }
    }

    // KMK -->
    private fun matchNamespace(
        namespace: String,
        tag: String,
        libraryManga: LibraryManga,
        genre: List<String>,
        tracks: List<Track>?,
        source: Source?,
        sourceIdString: String?,
        searchTags: List<SearchTag>?,
        searchTitles: List<SearchTitle>?,
        // KMK -->
        assignedCategories: List<Category> = emptyList(),
        // KMK <--
    ): Boolean {
        val manga = libraryManga.manga
        fun tagsMatch(): Boolean {
            if (searchTags == null) return false
            return searchTags.fastAny {
                it.namespace.equals(namespace, true) &&
                    (tag.isBlank() || it.name.contains(tag, true))
            }
        }
        return when (namespace.lowercase()) {
            "genre", "tag", "tags" ->
                (tag.isBlank() && genre.isNotEmpty()) ||
                    genre.fastAny { it.contains(tag, true) } || tagsMatch()
            "title" ->
                (tag.isBlank() && manga.title.isNotBlank()) ||
                    manga.title.contains(tag, true) ||
                    searchTitles?.fastAny { it.title.contains(tag, true) } == true
            "author", "a" ->
                // KMK --> a: matches either credit field: many sources only fill author
                tag.isNotBlank() && (
                    (manga.author?.contains(tag, true) == true) ||
                        (manga.artist?.contains(tag, true) == true) || tagsMatch()
                    )
            "artist" ->
                (tag.isBlank() && (!manga.artist.isNullOrBlank() || !manga.author.isNullOrBlank())) ||
                    (manga.artist?.contains(tag, true) == true) ||
                    // KMK --> fall back to author: sources that only fill author still match a:
                    (manga.author?.contains(tag, true) == true) || tagsMatch()
            "group", "g", "creator", "circle" ->
                // KMK --> g: is genre in library context (EH group tags fold into genre/tags)
                (tag.isBlank() && genre.isNotEmpty()) ||
                    genre.fastAny { it.contains(tag, true) } || tagsMatch()
            "source" ->
                (tag.isBlank() && source != null) ||
                    (source?.name?.contains(tag, true) == true) ||
                    (sourceIdString != null && sourceIdString == tag)
            "src" ->
                (tag.isBlank() && source != null) ||
                    if (tag.equals(LOCAL_SOURCE_ID_ALIAS, ignoreCase = true)) {
                        manga.source == LocalSource.ID
                    } else {
                        manga.source == tag.toLongOrNull()
                    }
            "id" -> tag.toLongOrNull()?.let { manga.id == it } ?: false
            "status" -> matchMangaStatus(tag, manga.status)
            "desc", "description" ->
                tag.isNotBlank() && (manga.description?.contains(tag, true) == true)
            "tracker" ->
                tracks != null && tracks.isNotEmpty() &&
                    (tag.isBlank() || filterTracks(tag, tracks, mihon.app.di.globalAppGraph.context))
            // KMK --> category/subcategory field search
            "category", "cat" ->
                (tag.isBlank() && assignedCategories.isNotEmpty()) ||
                    assignedCategories.fastAny { it.name.contains(tag, true) }
            "subcategory", "sub", "subcat" ->
                (tag.isBlank() && assignedCategories.any { it.parentId != 0L }) ||
                    assignedCategories.fastAny { it.parentId != 0L && it.name.contains(tag, true) }
            // KMK <--
            else -> tagsMatch()
        }
    }

    private fun matchMangaStatus(tag: String, status: Long): Boolean {
        val t = tag.trim().lowercase().replace('_', ' ').replace('-', ' ')
        if (t.isBlank()) return true
        if (t == status.toString()) return true
        val names = when (status) {
            SManga.UNKNOWN.toLong() -> listOf("unknown")
            SManga.ONGOING.toLong() -> listOf("ongoing")
            SManga.COMPLETED.toLong() -> listOf("completed", "complete", "finished")
            SManga.LICENSED.toLong() -> listOf("licensed")
            SManga.PUBLISHING_FINISHED.toLong() -> listOf("publishing finished")
            SManga.CANCELLED.toLong() -> listOf("cancelled", "canceled")
            SManga.ON_HIATUS.toLong() -> listOf("on hiatus", "hiatus")
            else -> return false
        }
        return names.fastAny { it.contains(t) }
    }
    // KMK <--

    companion object {
        /** Namespace prefixes that mean "match a tag", for the tag-search rotating tier. */
        private val TAG_NAMESPACES = setOf("tag", "tags")
    }
}
