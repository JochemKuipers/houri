package exh.recs

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.components.BrowseSourceSimpleToolbar
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.recs.batch.RankedSearchResults
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.SmartSearchSourcesRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen
import java.io.Serializable

/**
 * What the browse-recommends screen should show. Formerly nested in the `BrowseRecommendsScreen`
 * Voyager screen class; top-level so it can remain an assisted ViewModel parameter.
 */
sealed interface BrowseRecommendsScreenArgs : Serializable {
    data class SingleSourceManga(
        val mangaId: Long,
        val sourceId: Long,
        val recommendationSourceName: String,
    ) : BrowseRecommendsScreenArgs
    data class MergedSourceMangas(val results: RankedSearchResults) : BrowseRecommendsScreenArgs
}

/** Paged recommendations for a single manga, produced by a named [exh.recs.sources.RecommendationPagingSource]. */
@Composable
fun BrowseRecommendsScreen(
    mangaId: Long,
    sourceId: Long,
    recommendationSourceName: String,
    isExternalSource: Boolean,
) {
    BrowseRecommendsScreen(
        BrowseRecommendsScreenArgs.SingleSourceManga(mangaId, sourceId, recommendationSourceName),
        isExternalSource,
    )
}

/** Paged pre-computed batch recommendations. */
@Composable
fun BrowseMergedRecommendsScreen(results: RankedSearchResultsDto, isExternalSource: Boolean) {
    BrowseRecommendsScreen(
        BrowseRecommendsScreenArgs.MergedSourceMangas(results.toRankedSearchResults()),
        isExternalSource,
    )
}

@Composable
private fun BrowseRecommendsScreen(args: BrowseRecommendsScreenArgs, isExternalSource: Boolean) {
    if (!ifSourcesLoaded()) {
        LoadingScreen()
        return
    }

    val context = LocalContext.current
    val backStack = LocalBackStack.current

    val screenModel = assistedMetroViewModel<BrowseRecommendsViewModel, BrowseRecommendsViewModel.Factory> {
        create(args)
    }

    // KMK -->
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteScreenModel.backHandler()
    }
    // KMK <--

    val snackbarHostState = remember { SnackbarHostState() }

    val onClickItem = { manga: Manga ->
        backStack.add(
            when (isExternalSource) {
                true -> SmartSearchSourcesRoute(manga.ogTitle)
                false -> MangaRoute(manga.id, true)
            },
        )
    }

    // KMK -->
    val onLongClickItem = { manga: Manga ->
        when (isExternalSource) {
            true -> WebViewActivity.newIntent(context, manga.url, title = manga.title).let(context::startActivity)
            false -> backStack.add(MangaRoute(manga.id, true))
        }
    }

    val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
    // KMK <--

    Scaffold(
        topBar = { scrollBehavior ->
            // KMK -->
            if (bulkFavoriteState.selectionMode) {
                BulkSelectionToolbar(
                    selectedCount = bulkFavoriteState.selection.size,
                    isRunning = bulkFavoriteState.isRunning,
                    onClickClearSelection = bulkFavoriteScreenModel::toggleSelectionMode,
                    onChangeCategoryClick = bulkFavoriteScreenModel::addFavorite,
                    onSelectAll = {
                        mangaList.itemSnapshotList.items
                            .map { it.value.first }
                            .forEach { bulkFavoriteScreenModel.select(it) }
                    },
                    onReverseSelection = {
                        mangaList.itemSnapshotList.items
                            .map { it.value.first }
                            .let { bulkFavoriteScreenModel.reverseSelection(it) }
                    },
                )
            } else {
                // KMK <--
                val title = remember {
                    val recSource = screenModel.recommendationPagingSource
                    "${recSource.name} (${recSource.category.getString(context)})"
                }

                BrowseSourceSimpleToolbar(
                    navigateUp = backStack::removeLastOrNull,
                    title = title,
                    displayMode = screenModel.displayMode,
                    onDisplayModeChange = { screenModel.displayMode = it },
                    scrollBehavior = scrollBehavior,
                    // KMK -->
                    toggleSelectionMode = {
                        bulkFavoriteScreenModel.toggleSelectionMode(true)
                    }.takeIf { !isExternalSource },
                    isRunning = bulkFavoriteState.isRunning,
                    // KMK <--
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        BrowseSourceContent(
            source = screenModel.source,
            mangaList = mangaList,
            columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
            // SY -->
            ehentaiBrowseDisplayMode = false,
            // SY <--
            displayMode = screenModel.displayMode,
            snackbarHostState = snackbarHostState,
            contentPadding = paddingValues,
            onWebViewClick = null,
            onHelpClick = null,
            onLocalSourceHelpClick = null,
            onMangaClick = { manga ->
                // KMK -->
                if (bulkFavoriteState.selectionMode) {
                    bulkFavoriteScreenModel.toggleSelection(manga)
                } else {
                    // KMK <--
                    onClickItem(manga)
                }
            },
            onMangaLongClick = { manga ->
                // KMK -->
                if (!bulkFavoriteState.selectionMode) {
                    bulkFavoriteScreenModel.addRemoveManga(manga, haptic)
                } else {
                    onLongClickItem(manga)
                }
                // KMK <--
            },
            // KMK -->
            selection = bulkFavoriteState.selection,
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
