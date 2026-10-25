package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import mihon.app.di.globalAppGraph
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.replace
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun GlobalSearchScreen(
    searchQuery: String = "",
    extensionFilter: String? = null,
) {
    if (!ifSourcesLoaded()) {
        LoadingScreen()
        return
    }

    val backStack = LocalBackStack.current

    val screenModel = assistedMetroViewModel<GlobalSearchViewModel, GlobalSearchViewModel.Factory> {
        create(
            initialQuery = searchQuery,
            initialExtensionFilter = extensionFilter,
        )
    }
    val state by screenModel.state.collectAsState()
    var showSingleLoadingScreen by remember {
        mutableStateOf(searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty() && state.total == 1)
    }

    // KMK -->
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteScreenModel.backHandler()
    }

    val getSourceCategories = remember { globalAppGraph.getSourceCategories }
    val sourceCategories by remember { getSourceCategories.subscribe() }
        .collectAsState(initial = emptyList())
    // KMK <--

    if (showSingleLoadingScreen) {
        LoadingScreen()

        LaunchedEffect(state.items) {
            when (val result = state.items.values.singleOrNull()) {
                SearchItemResult.Loading -> return@LaunchedEffect
                is SearchItemResult.Success -> {
                    val manga = result.result.singleOrNull()
                    if (manga != null) {
                        backStack.replace(MangaRoute(manga.id, true))
                    } else {
                        // Backoff to result screen
                        showSingleLoadingScreen = false
                    }
                }
                else -> showSingleLoadingScreen = false
            }
        }
    } else {
        GlobalSearchScreen(
            state = state,
            navigateUp = backStack::removeLastOrNull,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            getManga = { screenModel.getManga(it) },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            onClickSource = {
                backStack.add(BrowseSourceRoute(it.id, state.searchQuery))
            },
            onClickItem = { manga ->
                // KMK -->
                if (bulkFavoriteState.selectionMode) {
                    bulkFavoriteScreenModel.toggleSelection(manga)
                } else {
                    // KMK <--
                    backStack.add(MangaRoute(manga.id, true))
                }
            },
            onLongClickItem = { manga ->
                // KMK -->
                if (!bulkFavoriteState.selectionMode) {
                    bulkFavoriteScreenModel.addRemoveManga(manga, haptic)
                } else {
                    // KMK <--
                    backStack.add(MangaRoute(manga.id, true))
                }
            },
            // KMK -->
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            hasPinnedSources = screenModel.hasPinnedSources(),
            sourceCategories = sourceCategories,
            onChangeSourceCategory = screenModel::setSourceCategory,
            onToggleExactAuthorMatch = screenModel::toggleExactAuthorMatch,
            // KMK <--
        )
    }

    // KMK -->
    BulkFavoriteDialogs(
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        dialog = bulkFavoriteState.dialog,
    )
    // KMK <--
}
