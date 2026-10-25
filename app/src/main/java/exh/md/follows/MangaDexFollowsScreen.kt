package exh.md.follows

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.components.BrowseSourceSimpleToolbar
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun MangaDexFollowsScreen(sourceId: Long) {
    if (!ifSourcesLoaded()) {
        LoadingScreen()
        return
    }

    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val screenModel = assistedMetroViewModel<MangaDexFollowsViewModel, MangaDexFollowsViewModel.Factory> {
        create(sourceId = sourceId)
    }
    val state by screenModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteScreenModel.backHandler()
    }
    // KMK <--

    val snackbarHostState = remember { SnackbarHostState() }

    // KMK -->
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
                BrowseSourceSimpleToolbar(
                    title = stringResource(SYMR.strings.mangadex_follows),
                    displayMode = screenModel.displayMode,
                    onDisplayModeChange = { screenModel.displayMode = it },
                    navigateUp = backStack::removeLastOrNull,
                    scrollBehavior = scrollBehavior,
                    // KMK -->
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                    isRunning = bulkFavoriteState.isRunning,
                    // KMK <--
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { paddingValues ->
        BrowseSourceContent(
            source = screenModel.source,
            mangaList = mangaList,
            columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
            // SY -->
            ehentaiBrowseDisplayMode = screenModel.ehentaiBrowseDisplayMode,
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
                    backStack.add(MangaRoute(manga.id, true))
                }
            },
            onMangaLongClick = { manga ->
                // KMK -->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (bulkFavoriteState.selectionMode) {
                    backStack.add(MangaRoute(manga.id, true))
                } else {
                    // KMK <--
                    scope.launchIO {
                        val duplicates = screenModel.getDuplicateLibraryManga(manga)
                        when {
                            manga.favorite -> screenModel.setDialog(BrowseSourceViewModel.Dialog.RemoveManga(manga))
                            duplicates.isNotEmpty() -> screenModel.setDialog(
                                BrowseSourceViewModel.Dialog.AddDuplicateManga(manga, duplicates),
                            )
                            else -> screenModel.addFavorite(manga)
                        }
                    }
                }
            },
            // KMK -->
            selection = bulkFavoriteState.selection,
            // KMK <--
        )
    }

    val onDismissRequest = { screenModel.setDialog(null) }
    when (val dialog = state.dialog) {
        is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
            DuplicateMangaDialog(
                duplicates = dialog.duplicates,
                onDismissRequest = onDismissRequest,
                onConfirm = { screenModel.addFavorite(dialog.manga) },
                onOpenManga = { backStack.add(MangaRoute(it.id)) },
                // KMK -->
                targetManga = dialog.manga,
                onMigrate = { screenModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it)) },
                // KMK <--
            )
        }
        // KMK -->
        is BrowseSourceViewModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.target] so we show [dialog.current].
                onClickTitle = { backStack.add(MangaRoute(dialog.current.id)) },
                onDismissRequest = onDismissRequest,
            )
        }
        // KMK <--
        is BrowseSourceViewModel.Dialog.RemoveManga -> {
            RemoveMangaDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = {
                    screenModel.changeMangaFavorite(dialog.manga)
                },
                mangaToRemove = dialog.manga,
            )
        }
        is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = {
                    backStack.add(CategoryRoute)
                },
                onConfirm = { include, _ ->
                    screenModel.changeMangaFavorite(dialog.manga)
                    screenModel.moveMangaToCategories(dialog.manga, include)
                },
            )
        }
        else -> {}
    }

    // KMK -->
    // Bulk-favorite actions only
    BulkFavoriteDialogs(
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        dialog = bulkFavoriteState.dialog,
    )
    // KMK <--
}
