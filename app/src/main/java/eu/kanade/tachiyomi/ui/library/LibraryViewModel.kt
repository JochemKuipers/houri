package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapNotNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.MergeMangaBySmartSearch
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.category.categorySortOrderOf
import eu.kanade.tachiyomi.ui.library.controller.LibraryDownloadController
import eu.kanade.tachiyomi.ui.library.controller.LibraryFilterHandler
import eu.kanade.tachiyomi.ui.library.controller.LibraryGroupingController
import eu.kanade.tachiyomi.ui.library.controller.LibraryItemPreferences
import eu.kanade.tachiyomi.ui.library.controller.LibrarySearchController
import eu.kanade.tachiyomi.ui.library.controller.LibrarySelectionController
import eu.kanade.tachiyomi.ui.library.controller.LibrarySortController
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import exh.favorites.FavoritesSyncHelper
import exh.recs.batch.RecommendationSearchHelper
import exh.source.EH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.isEhBasedManga
import exh.source.isMergedSourceId
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import logcat.LogPriority
import mihon.core.common.utils.mutate
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategorySortOrder
import tachiyomi.domain.category.model.sortedForDisplay
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.applyScanlatorVisibility
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.SubcategoryUpdateScope
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import tachiyomi.domain.source.model.Source as DomainSource

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class LibraryViewModel(
    private val getLibraryManga: GetLibraryManga,
    // KMK --> Light favorites projections (covers) and the plain rows, without the libraryView
    // aggregate.
    private val mangaRepository: MangaRepository,
    // KMK <--
    private val getCategories: GetCategories,
    private val getTracksPerManga: GetTracksPerManga,
    private val getNextChapters: GetNextChapters,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId,
    private val setReadStatus: SetReadStatus,
    private val updateManga: UpdateManga,
    private val setMangaCategories: SetMangaCategories,
    private val preferences: BasePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val coverCache: CoverCache,
    private val uiPreferences: UiPreferences,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val trackerManager: TrackerManager,
    // SY -->
    private val exhPreferences: ExhPreferences,
    private val sourcePreferences: SourcePreferences,
    private val getMergedMangaById: GetMergedMangaById,
    private val getTracks: GetTracks,
    private val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata,
    private val getSearchTags: GetSearchTags,
    private val getSearchTitles: GetSearchTitles,
    private val searchEngine: exh.search.SearchEngine,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    // SY <--
    // KMK -->
    private val mergeMangaBySmartSearch: MergeMangaBySmartSearch,
    private val createCategoryWithName: CreateCategoryWithName,
    // KMK <--
    // Concern handlers. The ViewModel keeps state composition and the public intent surface;
    // every heavy concern (filter, search, group, sort, download, selection bulk actions)
    // lives in a co-located controller with its own narrow dependency set, mirroring the
    // MangaInfoTranslationController precedent. See controller/ for each one.
    private val libraryFilterHandler: LibraryFilterHandler = LibraryFilterHandler(downloadManager, getMergedMangaById),
    private val searchController: LibrarySearchController = LibrarySearchController(
        searchEngine = searchEngine,
        libraryPreferences = libraryPreferences,
        trackerManager = trackerManager,
        sourceManager = sourceManager,
        getTracks = getTracks,
        getIdsOfFavoriteMangaWithMetadata = getIdsOfFavoriteMangaWithMetadata,
        getSearchTags = getSearchTags,
        getSearchTitles = getSearchTitles,
    ),
    private val sortController: LibrarySortController = LibrarySortController(libraryPreferences, trackerManager),
    private val groupingController: LibraryGroupingController = LibraryGroupingController(
        getTracks = getTracks,
        trackerManager = trackerManager,
        sourceManager = sourceManager,
    ),
    private val downloadController: LibraryDownloadController = LibraryDownloadController(
        downloadManager = downloadManager,
        getNextChapters = getNextChapters,
        getBookmarkedChaptersByMangaId = getBookmarkedChaptersByMangaId,
        getMergedMangaById = getMergedMangaById,
        smartScanlatorMergeEnabled = { libraryPreferences.smartScanlatorMerge().get() },
    ),
    private val selectionController: LibrarySelectionController = LibrarySelectionController(
        setCustomMangaInfo = setCustomMangaInfo,
        setReadStatus = setReadStatus,
        updateManga = updateManga,
        setMangaCategories = setMangaCategories,
        coverCache = coverCache,
        sourceManager = sourceManager,
        downloadManager = downloadManager,
        getMergedMangaById = getMergedMangaById,
        mergeMangaBySmartSearch = mergeMangaBySmartSearch,
    ),
    syncPreferences: SyncPreferences,
) : StateViewModel<LibraryViewModel.State>(State()) {

    // SY -->
    val favoritesSync = FavoritesSyncHelper(preferences.context)
    val recommendationSearch = RecommendationSearchHelper(preferences.context)

    private var recommendationSearchJob: Job? = null
    // SY <--

    init {
        mutableState.update { state ->
            state.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory().get())
        }
        pruneRetainedCovers()
        viewModelScope.launchIO {
            combine(
                combine(
                    state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                    getCategories.subscribe(),
                    getFavoritesFlow(),
                    ::Triple,
                ),
                combine(
                    getTracksPerManga.subscribe(),
                    getTrackingFiltersFlow(),
                    libraryPreferences.filterTrackingOverall().changes(),
                    ::Triple,
                ),
                // KMK -->
                combine(
                    state.map { it.includedCategories }.distinctUntilChanged(),
                    state.map { it.excludedCategories }.distinctUntilChanged(),
                    libraryPreferences.categoryManagerSortMode().changes(),
                    ::Triple,
                ),
                // KMK <--
                getLibraryItemPreferencesFlow(),
            ) { (searchQuery, categories, favorites), (tracksMap, trackingFilters, trackedOverall), (includedCategories, excludedCategories, categorySortMode), itemPreferences ->
                // KMK --> The transform only hands the inputs to the collector; the actual
                // filtering happens inside collectLatest below, where a batching storm can
                // cancel it and recompute for the newest inputs instead of queuing passes.
                LibraryDataRequest(
                    searchQuery = searchQuery,
                    categories = categories,
                    favorites = favorites,
                    tracksMap = tracksMap,
                    trackingFilters = trackingFilters,
                    trackedOverall = trackedOverall,
                    includedCategories = includedCategories,
                    excludedCategories = excludedCategories,
                    categorySortMode = categorySortMode,
                    itemPreferences = itemPreferences,
                )
                // KMK <--
            }
                .distinctUntilChanged()
                // KMK --> conflate: while the collector is busy re-filtering a large library,
                // superseded requests collapse into the newest one. Nothing is added to the
                // latency of a single steady emission - the first request always flows straight
                // through - but N invalidations landing back-to-back cost one pass, not N.
                .conflate()
                // KMK <--
                .collectLatest { (searchQuery, categories, favorites, tracksMap, trackingFilters, trackedOverall, includedCategories, excludedCategories, categorySortMode, itemPreferences) ->
                    val filteredFavorites = favorites
                        .let {
                            libraryFilterHandler.filter(
                                items = it,
                                trackMap = tracksMap,
                                trackingFilter = trackingFilters,
                                trackedOverall = trackedOverall,
                                preferences = itemPreferences,
                                includedCategories = includedCategories,
                                excludedCategories = excludedCategories,
                            )
                        }
                        .let {
                            if (searchQuery == null) {
                                // Don't do anything
                                it
                            } else {
                                // Filter query
                                // SY -->
                                searchController.filter(it, searchQuery, trackingFilters, categories)
                                // SY <--
                            }
                        }

                    LibraryData(
                        isInitialized = true,
                        // KMK --> display order only; the stored manual order is untouched
                        categories = categories.sortedForDisplay(categorySortOrderOf(categorySortMode)),
                        // KMK --> carried alongside so the subcategories the reader sees under a
                        // category are ordered by the mode they chose, not just the tabs
                        categorySortOrder = categorySortOrderOf(categorySortMode),
                        favorites = filteredFavorites,
                        tracksMap = tracksMap,
                        loggedInTrackerIds = trackingFilters.keys,
                    ).let { libraryData ->
                        mutableState.update { state ->
                            state.copy(libraryData = libraryData)
                        }
                    }
                }
        }

        viewModelScope.launchIO {
            combine(
                state
                    .dropWhile { !it.libraryData.isInitialized }
                    .map {
                        Triple(
                            it.libraryData,
                            // SY -->
                            it.groupType,
                            // SY <--
                            // KMK -->
                            it.searchQuery.isNullOrBlank() && !it.hasActiveFilters,
                            // KMK <--
                        )
                    }
                    .distinctUntilChanged(),
                // KMK -->
                combine(
                    libraryPreferences.sortingMode().changes(),
                    libraryPreferences.showHiddenCategories().changes(),
                    libraryPreferences.showEmptyCategoriesSearch().changes(),
                    libraryPreferences.categoryManagerSortMode().changes(),
                    ::LibraryDisplayPreferences,
                ),
                combine(
                    state.map { it.filterCategory }.distinctUntilChanged(),
                    state.map { it.includedCategories }.distinctUntilChanged(),
                    // KMK -->
                    combine(
                        state.map { it.activeCategoryId }.distinctUntilChanged(),
                        state.map { it.activeSubCategoryId }.distinctUntilChanged(),
                        ::Pair,
                    ),
                    ::Triple,
                    // KMK <--
                ),
                // KMK <--
            ) { (data, groupType, noActiveFilterOrSearch), (sort, showHiddenCategories, showEmptyCategoriesSearch, categorySortMode), (filterCategory, includedCategories, activeSelection) ->
                // KMK --> Inputs only; the grouping/sorting work runs inside collectLatest
                // below so superseded recomputations can be cancelled rather than stacked up.
                LibraryGroupingRequest(
                    data = data,
                    groupType = groupType,
                    noActiveFilterOrSearch = noActiveFilterOrSearch,
                    sort = sort,
                    showHiddenCategories = showHiddenCategories,
                    showEmptyCategoriesSearch = showEmptyCategoriesSearch,
                    categorySortMode = categorySortMode,
                    filterCategory = filterCategory,
                    includedCategories = includedCategories,
                    activeCategoryId = activeSelection.first,
                    activeSubCategoryId = activeSelection.second,
                )
            }
                .distinctUntilChanged()
                // KMK --> Same collapse as the data pipeline: during rapid invalidation only
                // the newest grouping request is worth a pass.
                .conflate()
                // KMK <--
                .collectLatest { (data, groupType, noActiveFilterOrSearch, sort, showHiddenCategories, showEmptyCategoriesSearch, categorySortMode, filterCategory, includedCategories, activeCategoryId, activeSubCategoryId) ->
                    // KMK -->
                    val subcategoryMangaMap = if (groupType == LibraryGroup.BY_DEFAULT) {
                        val subCategoryIds = data.categories.filter { it.parentId != 0L }.mapTo(HashSet()) { it.id }
                        buildMap {
                            for (item in data.favorites) {
                                for (categoryId in item.libraryManga.categories) {
                                    if (categoryId in subCategoryIds) getOrPut(categoryId) { mutableListOf() }.add(item.id)
                                }
                            }
                        }
                    } else {
                        emptyMap()
                    }
                    // KMK <--
                    data.favorites
                        .let { favorites ->
                            groupingController.group(
                                items = favorites,
                                categories = data.categories,
                                // KMK -->
                                groupType = if (filterCategory && includedCategories.isNotEmpty()) {
                                    LibraryGroup.UNGROUPED
                                } else {
                                    groupType
                                },
                                showHiddenCategories = showHiddenCategories,
                                activeCategoryId = activeCategoryId,
                                activeSubCategoryId = activeSubCategoryId,
                                // KMK <--
                                context = preferences.context,
                            )
                        }
                        .let {
                            sortController.sort(
                                groups = it,
                                favoritesById = data.favoritesById,
                                trackMap = data.tracksMap,
                                loggedInTrackerIds = data.loggedInTrackerIds,
                                // SY -->
                                groupSort = sort.takeIf { groupType != LibraryGroup.BY_DEFAULT },
                                // SY <--
                            )
                        }
                        // KMK -->
                        .filter {
                            // Hide empty categories unless the setting is enabled or there are no active filters/search
                            showEmptyCategoriesSearch || noActiveFilterOrSearch || it.value.isNotEmpty() ||
                                // Keep the active category visible when a selected subcategory is empty
                                (it.key.id == activeCategoryId && activeSubCategoryId != null)
                        }
                        .let {
                            // Fall back to default category if no categories are present
                            it.ifEmpty {
                                mapOf(
                                    Category(
                                        0,
                                        preferences.context.stringResource(MR.strings.default_category),
                                        0,
                                        0,
                                        false,
                                    ) to emptyList(),
                                )
                            }
                        }
                        .let { map -> map }
                        // KMK --> The count-based orders. The name and date orders already came in with
                        // `data.categories`, which this combine receives pre-sorted; the counts only exist
                        // here, on the groups just built, so they cannot be applied any earlier.
                        // sortedBy* is stable, so a tie keeps that incoming order rather than whatever the
                        // grouping happened to produce.
                        .let { map ->
                            when (categorySortOrderOf(categorySortMode)) {
                                CategorySortOrder.MOST_ITEMS ->
                                    map.entries.sortedByDescending { it.value.size }
                                        .associateTo(LinkedHashMap()) { it.key to it.value }

                                CategorySortOrder.LEAST_ITEMS ->
                                    map.entries.sortedBy { it.value.size }
                                        .associateTo(LinkedHashMap()) { it.key to it.value }

                                else -> map
                            }
                        }
                        // KMK <--
                        .let { grouping ->
                            mutableState.update { state ->
                                state.copy(
                                    isLoading = false,
                                    groupedFavorites = grouping,
                                    // KMK -->
                                    subcategoryMangaMap = subcategoryMangaMap,
                                    // KMK <--
                                )
                            }
                        }
                }
        }

        combine(
            libraryPreferences.categoryTabs().changes(),
            libraryPreferences.categoryNumberOfItems().changes(),
            libraryPreferences.showContinueReadingButton().changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(viewModelScope)

        combine(
            getLibraryItemPreferencesFlow(),
            getTrackingFiltersFlow(),
            libraryPreferences.filterTrackingOverall().changes(),
        ) { prefs, trackFilters, trackedOverall ->
            listOf(
                prefs.filterDownloaded,
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
                prefs.filterCompleted,
                prefs.filterIntervalCustom,
                // SY -->
                prefs.filterLewd,
                // SY <--
                *trackFilters.values.toTypedArray(),
                trackedOverall,
            )
                .fastAny { it != TriState.DISABLED } ||
                // KMK -->
                prefs.filterCategories
            // KMK <--
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(viewModelScope)

        // SY -->
        combine(
            exhPreferences.isHentaiEnabled().changes(),
            sourcePreferences.disabledSources().changes(),
            exhPreferences.enableExhentai().changes(),
        ) { isHentaiEnabled, disabledSources, enableExhentai ->
            isHentaiEnabled && (EH_SOURCE_ID.toString() !in disabledSources || enableExhentai)
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(showSyncExh = it)
                }
            }
            .launchIn(viewModelScope)

        libraryPreferences.groupLibraryBy().changes()
            .onEach {
                mutableState.update { state ->
                    state.copy(groupType = it)
                }
            }
            .launchIn(viewModelScope)
        syncPreferences.syncService()
            .changes()
            .distinctUntilChanged()
            .onEach { syncService ->
                mutableState.update { it.copy(isSyncEnabled = syncService != 0) }
            }
            .launchIn(viewModelScope)
        // SY <--

        // KMK -->
        combine(
            libraryPreferences.filterCategories().changes(),
            libraryPreferences.filterCategoriesInclude().changes(),
            libraryPreferences.filterCategoriesExclude().changes(),
        ) { filter, included, excluded ->
            Triple(
                filter,
                included.mapNotNull(String::toLongOrNull).toImmutableSet(),
                excluded.mapNotNull(String::toLongOrNull).toImmutableSet(),
            )
        }
            .distinctUntilChanged()
            .onEach { (filter, included, excluded) ->
                mutableState.update { state ->
                    state.copy(
                        filterCategory = filter,
                        includedCategories = included,
                        excludedCategories = excluded,
                    )
                }
            }
            .launchIn(viewModelScope)

        // KMK <--
    }

    /**
     * Drops covers that no library entry points at any more, once past the retention window.
     *
     * Runs from the library rather than from the cache because only the library knows which covers
     * are still referenced - pruning on age alone would eventually delete the library's own covers
     * and quietly re-download them.
     *
     * One pass per screen model, not per emission: the list re-emits on every preference change and
     * a directory walk per emission is exactly the kind of small repeated work this avoids. Nothing
     * is told to redraw either - what goes is a cover for a manga that is no longer in the library,
     * so there is nothing on screen holding one.
     */
    private fun pruneRetainedCovers() {
        viewModelScope.launchIO {
            // KMK --> Not before the library has landed. This used to fire its own full
            // libraryView scan alongside the load's - doubling the single most expensive query
            // per cold start on a large library - and nothing on screen waits on the result,
            // so waiting for the first emission queues the prune behind the frames the user
            // is actually waiting on instead.
            state.first { it.libraryData.isInitialized }
            // KMK --> Read the referenced covers off a two-column projection of the favorites
            // table instead of a second full libraryView run: pruning only ever asks for each
            // entry's thumbnail key and id.
            val referenced = buildSet {
                mangaRepository.getFavoriteCovers().forEach { (mangaId, thumbnailUrl) ->
                    coverCache.libraryCoverKey(thumbnailUrl)?.let(::add)
                    add(coverCache.customCoverKey(mangaId))
                }
            }
            // KMK <--
            val retention = uiPreferences.removedCoverRetention().get().retentionMillis
            coverCache.pruneOrphanedCovers(referenced, retention)
        }
    }

    private fun getLibraryItemPreferencesFlow(): Flow<LibraryItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge().changes(),
            libraryPreferences.unreadBadge().changes(),
            libraryPreferences.localBadge().changes(),
            libraryPreferences.languageBadge().changes(),
            libraryPreferences.autoUpdateMangaRestrictions().changes(),

            preferences.downloadedOnly().changes(),
            libraryPreferences.filterDownloaded().changes(),
            libraryPreferences.filterUnread().changes(),
            libraryPreferences.filterStarted().changes(),
            libraryPreferences.filterBookmarked().changes(),
            libraryPreferences.filterCompleted().changes(),
            libraryPreferences.filterIntervalCustom().changes(),
            // SY -->
            libraryPreferences.filterLewd().changes(),
            // SY <--
            // KMK -->
            libraryPreferences.sourceBadge().changes(),
            libraryPreferences.useLangIcon().changes(),
            libraryPreferences.filterCategories().changes(),
            // KMK <--
        ) {
            LibraryItemPreferences(
                downloadBadge = it[0] as Boolean,
                unreadBadge = it[1] as Boolean,
                localBadge = it[2] as Boolean,
                languageBadge = it[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (it[4] as Set<*>),
                globalFilterDownloaded = it[5] as Boolean,
                filterDownloaded = it[6] as TriState,
                filterUnread = it[7] as TriState,
                filterStarted = it[8] as TriState,
                filterBookmarked = it[9] as TriState,
                filterCompleted = it[10] as TriState,
                filterIntervalCustom = it[11] as TriState,
                // SY -->
                filterLewd = it[12] as TriState,
                // SY <--
                // KMK -->
                sourceBadge = it[13] as Boolean,
                useLangIcon = it[14] as Boolean,
                filterCategories = it[15] as Boolean,
                // KMK <--
            )
        }
    }

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
            // The library emits straight from the database, well before ExtensionManager has
            // finished scanning the installed APKs, so every source below resolved to a StubSource
            // and the badge read "not installed" until the next restart. A StateFlow never
            // re-emits true, so this holds the first emission once and nothing after it.
            sourceManager.isInitialized.filter { it },
            // getNextUnreadChapter reads the merge flag with .get(), so without this nothing
            // re-emitted the library when it was toggled and the continue-reading button kept
            // picking from the old merge. unreadCount itself needs no nudge; the chapter list
            // behind the button does.
            libraryPreferences.smartScanlatorMerge().changes(),
        ) { libraryManga, preferences, _, _, _ ->
            // Pre-fetch merged manga data to avoid N+1 queries
            val mergedMangaCache = mutableMapOf<Long, List<Manga>>()
            libraryManga.filter { isMergedSourceId(it.manga.source) }.forEach { manga ->
                mergedMangaCache[manga.manga.id] = getMergedMangaById.await(manga.manga.id)
            }

            libraryManga.map { manga ->
                // Display mode based on user preference: take it from global library setting or category
                // KMK -->
                // One source lookup per entry, and none at all when no badge consumes it.
                // Resolved unconditionally before, the stub lookup (and `lang` read) ran twice
                // per entry per emission on a 10k-entry library.
                val source = if (preferences.sourceBadge || preferences.languageBadge) {
                    sourceManager.getOrStub(manga.manga.source)
                } else {
                    null
                }
                // KMK <--
                LibraryItem(
                    libraryManga = manga,
                    downloadCount = if (preferences.downloadBadge) {
                        // SY -->
                        if (isMergedSourceId(manga.manga.source)) {
                            mergedMangaCache[manga.manga.id]
                                .orEmpty()
                                .sumOf { downloadManager.getDownloadCount(it) }
                                .toLong()
                        } else {
                            // SY <--
                            downloadManager.getDownloadCount(manga.manga).toLong()
                        }
                    } else {
                        0
                    },
                    unreadCount = if (preferences.unreadBadge) {
                        manga.unreadCount
                    } else {
                        0
                    },
                    isLocal = if (preferences.localBadge) {
                        manga.manga.isLocal()
                    } else {
                        false
                    },
                    sourceLanguage = if (preferences.languageBadge) {
                        source?.lang.orEmpty()
                    } else {
                        ""
                    },
                    // KMK -->
                    useLangIcon = preferences.useLangIcon,
                    source = if (preferences.sourceBadge) {
                        DomainSource(
                            // KMK --> source is non-null whenever the badge asks for it
                            source!!.id,
                            source.lang,
                            source.name,
                            supportsLatest = false,
                            isStub = source is StubSource,
                        )
                    } else {
                        null
                    },
                    // KMK <--
                )
            }
        }
    }

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    /**
     * Returns the ids of the categories a manga belongs to.
     *
     * The loaded library already knows them for every entry it shows, so they are read from
     * it instead of one query per entry (mihon#4022); an entry the current filter or search
     * hides is not in the loaded set and falls back to the database.
     */
    private suspend fun getCategoryIds(manga: Manga): Set<Long> {
        return state.value.libraryData.favoritesById[manga.id]
            ?.libraryManga
            ?.categories
            // The library reports no category as 0
            ?.filterTo(mutableSetOf()) { it != 0L }
            ?: getCategories.await(manga.id).mapTo(mutableSetOf()) { it.id }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        // SY -->
        val mergedManga = getMergedMangaById.await(manga.id).associateBy { it.id }
        return if (isMergedSourceId(manga.source)) {
            getMergedChaptersByMangaId.await(manga.id, applyFilter = true)
        } else {
            getChaptersByMangaId.await(manga.id, applyFilter = true)
        }
            // KMK -->
            .applyScanlatorVisibility(
                priority = manga.scanlatorPriority,
                blacklistedChapters = manga.blacklistedChapters,
                rangeRules = manga.scanlatorRangeRules,
                mergeEnabled = libraryPreferences.smartScanlatorMerge().get(),
            )
            // KMK <--
            .getNextUnread(manga, downloadManager, mergedManga)
        // SY <--
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(action: DownloadAction) {
        val mangas = state.value.selectedManga
        viewModelScope.launchNonCancellable {
            downloadController.performDownloadAction(action, mangas)
        }
        clearSelection()
    }

    // SY -->
    fun cleanTitles() {
        selectionController.cleanTitles(state.value.selectedManga)
        // Refresh library so Manga objects are reconstructed with updated customMangaInfo
        // Must call before clearSelection() since it reads the current selection
        updateSelectedManga()
        clearSelection()
    }

    fun resetInfo() {
        selectionController.resetInfo(state.value.selection)
        clearSelection()
        updateSelectedManga()
    }
    // SY <--

    // KMK -->
    /**
     * Update Selected Mangas
     */
    fun updateSelectedManga(): Boolean {
        val mangaIds = state.value.selection.toList()
        return LibraryUpdateJob.startNow(
            context = preferences.context,
            mangaIds = mangaIds,
            target = LibraryUpdateJob.Target.CHAPTERS,
        )
    }
    // KMK <--

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        val selection = state.value.selectedManga
        viewModelScope.launchNonCancellable {
            selectionController.markRead(selection, read)
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        viewModelScope.launchNonCancellable {
            selectionController.removeMangas(mangas, deleteFromLibrary, deleteChapters)
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        viewModelScope.launchNonCancellable {
            selectionController.setMangaCategories(
                mangaList = mangaList,
                addCategories = addCategories,
                removeCategories = removeCategories,
                categoryIdsOf = ::getCategoryIds,
            )
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode().asState(viewModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns())
            .asState(viewModelScope)
    }

    fun getRandomLibraryItemForCurrentCategory(): LibraryItem? {
        val state = state.value
        return state.getItemsForCategoryId(state.activeCategory?.id).randomOrNull()
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = LibraryDialog.SettingsSheet) }
    }

    // SY -->
    fun showRecommendationSearchDialog() {
        val mangaList = state.value.selectedManga
        mutableState.update { it.copy(dialog = LibraryDialog.RecommendationSearchSheet(mangaList)) }
    }
    // SY <--

    private var lastSelectionCategory: Long? = null

    fun clearSelection() {
        lastSelectionCategory = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    fun toggleSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { set ->
                if (!set.remove(manga.id)) set.add(manga.id)
            }
            lastSelectionCategory = category.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                if (lastSelectionCategory != category.id) {
                    list.add(manga.id)
                    return@mutate
                }

                val items = state.getItemsForCategoryId(category.id).fastMap { it.id }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga.id)

                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> lastMangaIndex..curMangaIndex
                    curMangaIndex < lastMangaIndex -> curMangaIndex..lastMangaIndex
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                selectionRange.mapNotNull { items[it] }.let(list::addAll)
            }
            lastSelectionCategory = category.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                state.getItemsForCategoryId(state.activeCategory?.id).fastMap { it.id }.let(list::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val itemIds = state.getItemsForCategoryId(state.activeCategory?.id).fastMap { it.id }
                val (toRemove, toAdd) = itemIds.partition { it in list }
                list.removeAll(toRemove.toSet())
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateActiveCategoryIndex(index: Int) {
        val newIndex = mutableState.updateAndGet { state ->
            val newCategoryId = state.displayedCategories.getOrNull(index)?.id
            state.copy(
                activeCategoryIndex = index,
                // KMK -->
                activeCategoryId = newCategoryId,
                // Recomposition on return from manga details re-fires this with the same page: keep the subcategory.
                activeSubCategoryId = if (newCategoryId == state.activeCategoryId) {
                    state.activeSubCategoryId
                } else {
                    defaultSubCategoryId(state, newCategoryId)
                },
                // KMK <--
            )
        }
            .coercedActiveCategoryIndex

        // Don't persist the synthetic achievements category as last used — it has no manga and traps the user on restart.
        val newCategoryId = mutableState.value.displayedCategories.getOrNull(index)?.id
        if (newCategoryId != ACHIEVEMENTS_CATEGORY_ID) {
            libraryPreferences.lastUsedCategory().set(newIndex)
        }
    }

    /**
     * The subcategory a category should open on, or null to open on the combined view.
     *
     * With the "All" chip turned off there is no way back to the combined view from the chip row, so
     * opening a category on it left the user on a screen they could not change: every title in every
     * subcategory, with no control to narrow it. Falling through to the first subcategory makes the
     * chip row the thing that was already on screen do the work.
     *
     * Deliberately not applied under the folder layout. There, a null subcategory is not the combined
     * view but the folder overview itself - it is how you navigate - so auto-selecting a subcategory
     * would hide the folders on entry and strand the user inside the first one.
     *
     * Filters hidden and unnamed subcategories exactly as the composable does before showing the row.
     * Selecting one the UI then refuses to draw would open the category on nothing at all, which is a
     * worse failure than the one being fixed.
     */
    private fun defaultSubCategoryId(state: State, categoryId: Long?): Long? {
        if (categoryId == null) return null
        if (categoryId == ACHIEVEMENTS_CATEGORY_ID) return null
        if (!libraryPreferences.hideSubcategoryAllChip().get()) return null
        if (libraryPreferences.subcategoryFolderLayout().get()) return null
        return state.libraryData.categories
            .firstOrNull {
                it.parentId == categoryId &&
                    !it.hidden &&
                    it.name.isNotBlank()
            }
            ?.id
    }

    fun leaveAchievements() {
        val state = mutableState.value
        val current = state.displayedCategories.getOrNull(state.coercedActiveCategoryIndex)
        if (current?.id != ACHIEVEMENTS_CATEGORY_ID) return
        val categories = state.displayedCategories
        // Prefer the last real category the user was on; fall back to first non-achievements.
        val saved = libraryPreferences.lastUsedCategory().get().coerceIn(0, categories.lastIndex.coerceAtLeast(0))
        val savedIsAchievements = categories.getOrNull(saved)?.id == ACHIEVEMENTS_CATEGORY_ID
        val fallback = categories.indexOfFirst { it.id != ACHIEVEMENTS_CATEGORY_ID }.coerceAtLeast(0)
        val target = when {
            !savedIsAchievements -> saved
            fallback != -1 -> fallback
            else -> 0
        }.coerceIn(0, categories.lastIndex.coerceAtLeast(0))
        updateActiveCategoryIndex(target)
    }

    // KMK -->
    fun getSubcategoriesForCategory(category: Category): List<Category> {
        if (category.id == ACHIEVEMENTS_CATEGORY_ID) return emptyList()
        return state.value.sortedSubcategories(category)
    }

    fun selectSubcategory(subCategoryId: Long?) {
        mutableState.update { it.copy(activeSubCategoryId = subCategoryId) }
    }

    /**
     * Resolves the [Category] a manual refresh should target: the selected
     * subcategory when the update scope is SUBCATEGORY_ONLY and one is active,
     * otherwise the given top-level category.
     */
    fun getRefreshCategory(category: Category?): Category? {
        if (category == null) return null
        if (libraryPreferences.subcategoryUpdateScope().get() != SubcategoryUpdateScope.SUBCATEGORY_ONLY) {
            return category
        }
        val subCategoryId = state.value.activeSubCategoryId ?: return category
        return state.value.libraryData.categories.firstOrNull { it.id == subCategoryId } ?: category
    }
    // KMK <--

    fun openChangeCategoryDialog() {
        viewModelScope.launchIO {
            // Create a copy of selected manga
            val mangaList = state.value.selectedManga

            // Hide the default category because it has a different behavior than the ones from db.
            // KMK -->
            val categories = state.value.libraryData.categories.fastFilter { it.id != 0L }
            // KMK <--

            val preselected = changeCategoryPreselection(mangaList, categories, ::getCategoryIds)
            mutableState.update { it.copy(dialog = LibraryDialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        mutableState.update { it.copy(dialog = LibraryDialog.DeleteManga(state.value.selectedManga)) }
    }

    // KMK -->
    /**
     * Only offers an artist or author when every selected entry agrees on it, so a
     * mixed selection falls back to a plain name instead of guessing.
     */
    fun openCreateSubcategoryDialog() {
        val selected = state.value.selectedManga
        mutableState.update {
            it.copy(
                dialog = LibraryDialog.CreateSubcategory(
                    artist = selected.sharedMetadata { manga -> manga.artist },
                    author = selected.sharedMetadata { manga -> manga.author },
                ),
            )
        }
    }

    fun createSubcategoryFromSelection(name: String, type: Int? = null) {
        val parent = state.value.activeCategory ?: return
        val manga = state.value.selectedManga
        if (parent.id <= 0L || manga.isEmpty()) return
        clearSelection()
        viewModelScope.launchIO {
            when (val result = createCategoryWithName.await(name, parent.id, type)) {
                is CreateCategoryWithName.Result.Success -> {
                    viewModelScope.launchNonCancellable {
                        selectionController.setMangaCategories(
                            mangaList = manga,
                            addCategories = listOf(result.category.id),
                            removeCategories = emptyList(),
                            categoryIdsOf = ::getCategoryIds,
                        )
                    }
                }
                is CreateCategoryWithName.Result.InternalError -> {
                    logcat(LogPriority.ERROR, result.error) { "Failed to create subcategory" }
                }
            }
        }
    }
    // KMK <--

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    // SY -->
    fun runRecommendationSearch(selection: List<Manga>) {
        recommendationSearch.runSearch(viewModelScope, selection)?.let {
            recommendationSearchJob = it
        }
    }

    fun cancelRecommendationSearch() {
        recommendationSearchJob?.cancel()
    }

    fun runSync() {
        favoritesSync.runSync(viewModelScope)
    }

    fun onAcceptSyncWarning() {
        exhPreferences.exhShowSyncIntro().set(false)
    }

    fun openFavoritesSyncDialog() {
        mutableState.update {
            it.copy(
                dialog = if (exhPreferences.exhShowSyncIntro().get()) {
                    LibraryDialog.SyncFavoritesWarning
                } else {
                    LibraryDialog.SyncFavoritesConfirm
                },
            )
        }
    }
    // SY <--

    // KMK -->
    /**
     * Will get first merged manga in the list as target merging.
     * If there is no merged manga, then it will use the first one in list to create a new target.
     */
    suspend fun smartSearchMerge(selectedMangas: PersistentList<Manga>): Long? {
        return selectionController.smartSearchMerge(selectedMangas)
    }
    // KMK <--

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
        val tracksMap: Map</* Manga */ Long, List<Track>> = emptyMap(),
        val loggedInTrackerIds: Set<Long> = emptySet(),
        // KMK --> the mode `categories` is ordered by, needed to order subcategories too: the
        // count-based modes cannot be applied there, see sortedSubcategories
        val categorySortOrder: CategorySortOrder = CategorySortOrder.MANUAL,
    ) {
        val favoritesById by lazy { favorites.associateBy { it.id } }
    }

    // KMK -->
    /**
     * The inputs [LibraryData] is built from. The data pipeline emits this instead of the built
     * result so the expensive filtering lives in the collector, where a superseded request
     * collapses into the newest one under rapid invalidation (see conflate in init).
     */
    @Immutable
    private data class LibraryDataRequest(
        val searchQuery: String?,
        val categories: List<Category>,
        val favorites: List<LibraryItem>,
        val tracksMap: Map</* Manga */ Long, List<Track>>,
        val trackingFilters: Map</* Tracker */ Long, TriState>,
        val trackedOverall: TriState,
        val includedCategories: ImmutableSet<Long>,
        val excludedCategories: ImmutableSet<Long>,
        val categorySortMode: Int,
        val itemPreferences: LibraryItemPreferences,
    )

    /**
     * The inputs the category grouping/sorting pass needs, for the same reason as
     * [LibraryDataRequest]: emit cheap, compute heavy, keep only the newest request.
     */
    @Immutable
    private data class LibraryGroupingRequest(
        val data: LibraryData,
        val groupType: Int,
        val noActiveFilterOrSearch: Boolean,
        val sort: LibrarySort,
        val showHiddenCategories: Boolean,
        val showEmptyCategoriesSearch: Boolean,
        val categorySortMode: Int,
        val filterCategory: Boolean,
        val includedCategories: ImmutableSet<Long>,
        val activeCategoryId: Long?,
        val activeSubCategoryId: Long?,
    )
    // KMK <--

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set</* Manga */ Long> = setOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: LibraryDialog? = null,
        val libraryData: LibraryData = LibraryData(),
        private val activeCategoryIndex: Int = 0,
        // KMK -->
        val activeCategoryId: Long? = null,
        val activeSubCategoryId: Long? = null,
        // KMK <--
        private val groupedFavorites: Map<Category, List</* LibraryItem */ Long>> = emptyMap(),
        // KMK -->
        /** Manga ids assigned directly to each subcategory, used by the folder layout. */
        val subcategoryMangaMap: Map</* Subcategory */ Long, List</* LibraryItem */ Long>> = emptyMap(),
        // KMK <--
        // SY -->
        val showSyncExh: Boolean = false,
        val isSyncEnabled: Boolean = false,
        val groupType: Int = LibraryGroup.BY_DEFAULT,
        // SY <--
        // KMK -->
        val filterCategory: Boolean = false,
        val includedCategories: ImmutableSet<Long> = persistentSetOf(),
        val excludedCategories: ImmutableSet<Long> = persistentSetOf(),
        // KMK <--
    ) {
        /**
         * The grouped tabs which is displayed above the library screen.
         * They can be actual [Category], source, or [Track] tabs.
         */
        val displayedCategories: List<Category> = groupedFavorites.keys.toList()

        val coercedActiveCategoryIndex = /* KMK --> */ displayedCategories.indexOfFirst { it.id == activeCategoryId }
            .takeIf { it != -1 } ?: activeCategoryIndex
            // KMK <--
            .coerceIn(
                minimumValue = 0,
                maximumValue = displayedCategories.lastIndex.coerceAtLeast(0),
            )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = libraryData.favorites.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedManga by lazy { selection.mapNotNull { libraryData.favoritesById[it]?.libraryManga?.manga } }

        // SY -->
        val showCleanTitles: Boolean by lazy {
            selectedManga.fastAny {
                it.isEhBasedManga()
            }
        }

        val showResetInfo: Boolean by lazy {
            selectedManga.fastAny { manga ->
                manga.title != manga.ogTitle ||
                    manga.author != manga.ogAuthor ||
                    manga.artist != manga.ogArtist ||
                    manga.thumbnailUrl != manga.ogThumbnailUrl ||
                    manga.description != manga.ogDescription ||
                    manga.genre != manga.ogGenre ||
                    manga.status != manga.ogStatus
            }
        }
        // SY <--

        fun getItemsForCategoryId(categoryId: Long?): List<LibraryItem> {
            if (categoryId == null) return emptyList()
            val category = displayedCategories.find { it.id == categoryId } ?: return emptyList()
            return getItemsForCategory(category)
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> {
            // KMK --> Synchronous subcategory filter: selectSubcategory updates
            // activeSubCategoryId immediately, but groupedFavorites recomputes
            // async in the combine above. Without this the grid renders the full
            // category tree for a frame before the recomputed direct list arrives.
            // Filtering the current (sorted) tree list by subcategory membership
            // preserves sort order, since every list is sorted with the same
            // comparator after grouping. Once the recompute lands the stored list
            // is already the direct list and this filter is an identity.
            val subId = activeSubCategoryId
            if (subId == null || category.id != activeCategoryId || groupType != LibraryGroup.BY_DEFAULT) {
                return groupedFavorites[category].orEmpty().fastMapNotNull { libraryData.favoritesById[it] }
            }
            val allowed = subcategoryMangaMap[subId]
                ?: return groupedFavorites[category].orEmpty().fastMapNotNull { libraryData.favoritesById[it] }
            val allowedSet = allowed.toHashSet()
            return groupedFavorites[category].orEmpty().fastMapNotNull { id ->
                libraryData.favoritesById[id]?.takeIf { id in allowedSet }
            }
            // KMK <--
        }

        // KMK -->
        fun getItemsForSubcategory(subcategoryId: Long): List<LibraryItem> {
            return subcategoryMangaMap[subcategoryId].orEmpty().fastMapNotNull { libraryData.favoritesById[it] }
        }

        /**
         * The subcategories of [category] in the mode chosen in the category manager, so the chips
         * and the folders under a category line up with the tabs above them.
         *
         * [LibraryData.categories] already arrives in that order and filtering keeps it, so the
         * name and date modes need nothing here. The two count-based modes are the exception: they
         * need a per-subcategory count, and those only exist once the library has been grouped -
         * which is why the tabs are re-sorted over the groups they just built while subcategories
         * used to keep the stored order.
         */
        fun sortedSubcategories(category: Category): List<Category> {
            val subcategories = libraryData.categories
                .filter { it.parentId == category.id && it.name.isNotBlank() }
            val sortOrder = libraryData.categorySortOrder
            if (sortOrder != CategorySortOrder.MOST_ITEMS && sortOrder != CategorySortOrder.LEAST_ITEMS) {
                return subcategories
            }
            return subcategories.sortedForDisplay(
                sortOrder,
                subcategories.associate { it.id to (subcategoryMangaMap[it.id]?.size ?: 0) },
            )
        }

        /**
         * Splits a category's tree content into non-empty subcategory folders plus
         * the manga living directly in the category for the folder layout.
         */
        fun getFolderViewData(category: Category): Pair<List<Pair<Category, List<LibraryItem>>>, List<LibraryItem>> {
            // KMK --> Sorted by the chosen mode, not by the stored order: the folders are
            // subcategories of the category on screen, and the chips next to this layout were
            // already following it while the folders were the one list left on manual order.
            val folders = sortedSubcategories(category)
                .filterNot(Category::hidden)
                .mapNotNull { subcategory ->
                    getItemsForSubcategory(subcategory.id)
                        .takeIf { items -> items.isNotEmpty() }
                        ?.let { items -> subcategory to items }
                }
            val folderIds = folders.mapTo(HashSet()) { it.first.id }
            val loose = getItemsForCategory(category).filterNot { item ->
                item.libraryManga.categories.fastAny { it in folderIds }
            }
            return folders to loose
        }
        // KMK <--

        fun getItemCountForCategory(category: Category): Int? {
            if (!(showMangaCount || !searchQuery.isNullOrEmpty())) return null
            // KMK --> Match getItemsForCategory: count the filtered subset while
            // groupedFavorites is still the stale full tree.
            val subId = activeSubCategoryId
            if (subId == null || category.id != activeCategoryId || groupType != LibraryGroup.BY_DEFAULT) {
                return groupedFavorites[category]?.size
            }
            val allowed = subcategoryMangaMap[subId] ?: return groupedFavorites[category]?.size
            val allowedSet = allowed.toHashSet()
            return groupedFavorites[category]?.count { it in allowedSet }
            // KMK <--
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = displayedCategories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = category.let {
                if (it.isSystemCategory) defaultCategoryTitle else it.name
            }
            val title = if (showCategoryTabs) defaultTitle else categoryName
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getItemCountForCategory(category)
                // Whole library count
                else -> libraryData.favorites.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }

    companion object {
        const val ACHIEVEMENTS_CATEGORY_ID = -100L
    }
}

// KMK -->
/**
 * The four preference flows that drive grouping. Grouping needs the category sort mode alongside
 * the other three, but kotlinx.coroutines has no 4-tuple to destructure into, so it gets a named one.
 */
private data class LibraryDisplayPreferences(
    val sort: LibrarySort,
    val showHiddenCategories: Boolean,
    val showEmptyCategoriesSearch: Boolean,
    val categorySortMode: Int,
)
// KMK <--
