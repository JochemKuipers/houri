package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.result.LocalResultEventBus
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrateSourceSearchRoute
import mihon.core.navigation.MigrationListRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.popUntil
import mihon.core.navigation.util.replace
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.feature.migration.list.MatchOverrideEvent

@Composable
fun MigrateSearchScreen(mangaId: Long) {
    val backStack = LocalBackStack.current
    val resultEventBus = LocalResultEventBus.current

    val screenModel = assistedMetroViewModel<MigrateSearchViewModel, MigrateSearchViewModel.Factory> { create(mangaId = mangaId) }
    val state by screenModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteScreenModel.backHandler()
    }
    // KMK <--

    MigrateSearchScreen(
        state = state,
        fromSourceId = state.from?.source,
        navigateUp = backStack::removeLastOrNull,
        onChangeSearchQuery = screenModel::updateSearchQuery,
        onSearch = { screenModel.search() },
        getManga = { screenModel.getManga(it) },
        onChangeSearchFilter = screenModel::setSourceFilter,
        onToggleResults = screenModel::toggleFilterResults,
        onClickSource = { backStack.add(MigrateSourceSearchRoute(state.from!!, it.id, state.searchQuery)) },
        onClickItem = {
            // KMK -->
            if (bulkFavoriteState.selectionMode) {
                bulkFavoriteScreenModel.toggleSelection(it)
            } else {
                // KMK <--
                val migrateListRoute = backStack
                    .filterIsInstance<MigrationListRoute>()
                    .lastOrNull()

                if (migrateListRoute == null) {
                    screenModel.setMigrateDialog(mangaId, it)
                } else {
                    resultEventBus.sendResult(
                        result = MatchOverrideEvent(current = mangaId, target = it.id),
                    )
                    backStack.popUntil { screen -> screen is MigrationListRoute }
                }
            }
        },
        onLongClickItem = { backStack.add(MangaRoute(it.id, true)) },
        // KMK -->
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        hasPinnedSources = screenModel.hasPinnedSources(),
        // KMK <--
    )

    when (val dialog = state.dialog) {
        is SearchViewModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.current] so we show [dialog.target].
                onClickTitle = { backStack.add(MangaRoute(dialog.target.id, true)) },
                onDismissRequest = { screenModel.clearDialog() },
                onComplete = {
                    if (backStack.lastOrNull() is MangaRoute) {
                        val lastItem = backStack.lastOrNull()
                        backStack.popUntil { backStack.contains(lastItem) }
                        backStack.add(MangaRoute(dialog.target.id))
                    } else {
                        backStack.replace(MangaRoute(dialog.target.id))
                    }
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
