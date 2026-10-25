package eu.kanade.tachiyomi.ui.libraryUpdateError

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.libraryUpdateError.LibraryUpdateErrorScreen
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrationConfigRoute
import mihon.core.navigation.util.LocalBackStack

@Composable
fun LibraryUpdateErrorScreen() {
    val backStack = LocalBackStack.current
    val screenModel = metroViewModel<LibraryUpdateErrorViewModel>()
    val state by screenModel.state.collectAsState()

    LibraryUpdateErrorScreen(
        state = state,
        onClick = { item ->
            backStack.add(MigrationConfigRoute(item.error.mangaId))
        },
        onClickCover = { item -> backStack.add(MangaRoute(item.error.mangaId)) },
        onMultiMigrateClicked = {
            backStack.add(MigrationConfigRoute(state.selected.map { it.error.mangaId }))
        },
        onSelectAll = screenModel::toggleAllSelection,
        onInvertSelection = screenModel::invertSelection,
        onErrorsDelete = screenModel::deleteSelected,
        onErrorDelete = screenModel::delete,
        onErrorSelected = screenModel::toggleSelection,
        navigateUp = backStack::removeLastOrNull,
    )
}
