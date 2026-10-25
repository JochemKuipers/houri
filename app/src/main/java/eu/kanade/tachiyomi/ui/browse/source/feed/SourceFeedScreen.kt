package eu.kanade.tachiyomi.ui.browse.source.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.SourceFeedOrderScreen
import eu.kanade.presentation.browse.SourceFeedScreen
import eu.kanade.presentation.browse.SourceFeedUI
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.FeedActionsDialog
import eu.kanade.presentation.browse.components.SourceFeedAddDialog
import eu.kanade.presentation.browse.components.SourceFeedDeleteDialog
import eu.kanade.presentation.more.settings.screen.SettingsEhRoute
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.util.system.toast
import exh.source.ExhPreferences
import exh.source.anyIs
import exh.source.isEhBasedSource
import exh.util.nullIfBlank
import mihon.app.di.globalAppGraph
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.MangaDexFollowsRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.SourcePreferencesRoute
import mihon.core.navigation.WebViewRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun SourceFeedScreen(sourceId: Long) {
    if (!ifSourcesLoaded()) {
        LoadingScreen()
        return
    }

    val screenModel = assistedMetroViewModel<SourceFeedViewModel, SourceFeedViewModel.Factory> { create(sourceId) }
    val state by screenModel.state.collectAsState()
    val backStack = LocalBackStack.current
    val context = LocalContext.current

    // KMK -->
    screenModel.source.let {
        if (it is StubSource) {
            MissingSourceScreen(
                source = it,
                navigateUp = { backStack.removeLastOrNull() },
            )
            return
        }
    }

    LaunchedEffect(backStack.lastOrNull()) {
        // Reset filters & reload saved-searches when screen is navigated back to
        screenModel.resetFilters()
    }

    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
    val showingFeedOrderScreen = rememberSaveable { mutableStateOf(false) }

    val isHentaiEnabled: Boolean = globalAppGraph.exhPreferences.isHentaiEnabled().get()
    val isConfigurableSource = screenModel.source.anyIs<ConfigurableSource>() ||
        (screenModel.source.isEhBasedSource() && isHentaiEnabled)

    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = bulkFavoriteState.selectionMode || showingFeedOrderScreen.value) {
        when {
            bulkFavoriteState.selectionMode -> bulkFavoriteScreenModel.backHandler()
            showingFeedOrderScreen.value -> showingFeedOrderScreen.value = false
        }
    }
    Crossfade(
        targetState = showingFeedOrderScreen.value,
        label = "feed_order_crossfade",
    ) { targetState ->
        if (targetState) {
            SourceFeedOrderScreen(
                state = state,
                onClickDelete = screenModel::openDeleteFeed,
                onChangeOrder = screenModel::changeOrder,
                navigateUp = { showingFeedOrderScreen.value = false },
            )
        } else {
            // KMK <--
            SourceFeedScreen(
                name = screenModel.source.name,
                isLoading = state.isLoading,
                items = state.items,
                hasFilters = state.filters.isNotEmpty(),
                onFabClick = screenModel::openFilterSheet,
                onClickBrowse = { onBrowseClick(backStack, screenModel.source) },
                onClickLatest = { onLatestClick(backStack, screenModel.source) },
                onClickSavedSearch = { onSavedSearchClick(backStack, screenModel.source, it) },
                // KMK -->
                // onClickDelete = screenModel::openDeleteFeed,
                onLongClickFeed = screenModel::openActionsDialog,
                // KMK <--
                onClickManga = { manga ->
                    // KMK -->
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.toggleSelection(manga)
                    } else {
                        // KMK <--
                        onMangaClick(backStack, manga)
                    }
                },
                onClickSearch = { onSearchClick(backStack, screenModel.source, it) },
                searchQuery = state.searchQuery,
                onSearchQueryChange = screenModel::search,
                getMangaState = { screenModel.getManga(initialManga = it) },
                // KMK -->
                navigateUp = { backStack.removeLastOrNull() },
                onWebViewClick = {
                    val source = screenModel.source as HttpSource
                    backStack.add(
                        WebViewRoute(
                            url = source.baseUrl,
                            initialTitle = source.name,
                            sourceId = source.id,
                        ),
                    )
                    Unit
                }.takeIf { screenModel.source is HttpSource },
                onToggleIncognito = screenModel::toggleIncognitoMode,
                onSourceSettingClick = {
                    when {
                        screenModel.source.isEhBasedSource() && isHentaiEnabled ->
                            backStack.add(SettingsEhRoute)
                        screenModel.source.anyIs<ConfigurableSource>() ->
                            backStack.add(SourcePreferencesRoute(screenModel.source.id))
                        else -> {}
                    }
                    Unit
                }.takeIf { isConfigurableSource },
                onSortFeedClick = { showingFeedOrderScreen.value = true }
                    .takeIf {
                        screenModel.state.value.items
                            .filterIsInstance<SourceFeedUI.SourceSavedSearch>()
                            .isNotEmpty()
                    },
                onLongClickManga = { manga ->
                    if (!bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.addRemoveManga(manga, haptic)
                    } else {
                        backStack.add(MangaRoute(manga.id, true))
                    }
                },
                bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                // KMK <--
            )
        }
    }

    val onDismissRequest = screenModel::dismissDialog
    when (val dialog = state.dialog) {
        is SourceFeedViewModel.Dialog.AddFeed -> {
            SourceFeedAddDialog(
                onDismissRequest = onDismissRequest,
                name = dialog.name,
                addFeed = {
                    screenModel.createFeed(dialog.feedId)
                    onDismissRequest()
                },
            )
        }
        is SourceFeedViewModel.Dialog.DeleteFeed -> {
            SourceFeedDeleteDialog(
                onDismissRequest = onDismissRequest,
                deleteFeed = {
                    screenModel.deleteFeed(dialog.feed)
                    onDismissRequest()
                },
            )
        }
        // KMK -->
        is SourceFeedViewModel.Dialog.FeedActions -> {
            FeedActionsDialog(
                feed = dialog.feedItem.feed,
                title = dialog.feedItem.title,
                onDismissRequest = screenModel::dismissDialog,
                onClickRefresh = { screenModel.refreshFeed(dialog.feedItem.feed) },
                onClickDelete = { screenModel.openDeleteFeed(it) },
            )
        }
        // KMK <--
        is SourceFeedViewModel.Dialog.Filter -> {
            SourceFilterDialog(
                onDismissRequest = onDismissRequest,
                filters = state.filters,
                // KMK -->
                onReset = screenModel::resetFilters,
                // KMK <--
                onFilter = {
                    screenModel.onFilter { query, filters ->
                        onBrowseClick(
                            backStack = backStack,
                            sourceId = sourceId,
                            search = query,
                            filters = filters,
                        )
                    }
                },
                onUpdate = screenModel::setFilters,
                startExpanded = screenModel.startExpanded,
                onSave = {},
                savedSearches = state.savedSearches,
                onSavedSearch = { search ->
                    screenModel.onSavedSearch(
                        search,
                        onBrowseClick = { query, searchId ->
                            onBrowseClick(
                                backStack = backStack,
                                sourceId = sourceId,
                                search = query,
                                savedSearch = searchId,
                            )
                        },
                        onToast = {
                            context.toast(it)
                        },
                    )
                },
                onSavedSearchPress = { search ->
                    screenModel.onSavedSearchAddToFeed(search) {
                        context.toast(it)
                    }
                },
                // KMK -->
                onSavedSearchPressDesc = stringResource(KMR.strings.saved_searches_add_feed),
                shouldShowSavingButton = false,
                // KMK <--
                openMangaDexRandom = if (screenModel.sourceIsMangaDex) {
                    {
                        screenModel.onMangaDexRandom {
                            // KMK -->
                            backStack.add(
                                BrowseSourceRoute(
                                    sourceId,
                                    "id:$it",
                                ),
                            )
                            // KMK <--
                        }
                    }
                } else {
                    null
                },
                openMangaDexFollows = if (screenModel.sourceIsMangaDex) {
                    {
                        backStack.add(MangaDexFollowsRoute(sourceId))
                    }
                } else {
                    null
                },
            )
        }
        null -> Unit
    }

    // KMK -->
    BulkFavoriteDialogs(
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        dialog = bulkFavoriteState.dialog,
    )
    // KMK <--
}

private fun onMangaClick(backStack: NavBackStack<NavKey>, manga: Manga) {
    backStack.add(MangaRoute(manga.id, true))
}

private fun onBrowseClick(
    backStack: NavBackStack<NavKey>,
    sourceId: Long,
    search: String? = null,
    savedSearch: Long? = null,
    filters: String? = null,
) {
    // KMK -->
    backStack.add(BrowseSourceRoute(sourceId, search, savedSearch = savedSearch, filtersJson = filters))
    // KMK <--
}

private fun onLatestClick(backStack: NavBackStack<NavKey>, source: Source) {
    // KMK -->
    backStack.add(BrowseSourceRoute(source.id, GetRemoteManga.QUERY_LATEST))
    // KMK <--
}

private fun onBrowseClick(backStack: NavBackStack<NavKey>, source: Source) {
    // KMK -->
    backStack.add(BrowseSourceRoute(source.id, GetRemoteManga.QUERY_POPULAR))
    // KMK <--
}

private fun onSavedSearchClick(backStack: NavBackStack<NavKey>, source: Source, savedSearch: SavedSearch) {
    // KMK -->
    backStack.add(BrowseSourceRoute(source.id, listingQuery = null, savedSearch = savedSearch.id))
    // KMK <--
}

private fun onSearchClick(backStack: NavBackStack<NavKey>, source: Source, query: String) {
    onBrowseClick(backStack, source.id, query.nullIfBlank())
}
