package eu.kanade.tachiyomi.ui.category.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.SourceCategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun SourceCategoryScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val screenModel = metroViewModel<SourceCategoryViewModel>()

    val state by screenModel.state.collectAsState()

    if (state is SourceCategoryScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as SourceCategoryScreenState.Success

    SourceCategoryScreen(
        state = successState,
        onClickCreate = { screenModel.showDialog(SourceCategoryDialog.Create) },
        onClickRename = { screenModel.showDialog(SourceCategoryDialog.Rename(it)) },
        onClickDelete = { screenModel.showDialog(SourceCategoryDialog.Delete(it)) },
        navigateUp = backStack::removeLastOrNull,
    )

    when (val dialog = successState.dialog) {
        null -> {}
        SourceCategoryDialog.Create -> {
            CategoryCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = { screenModel.createCategory(it) },
                // SY -->
                categories = successState.categories,
                title = stringResource(MR.strings.action_add_category),
                // SY <--
            )
        }
        is SourceCategoryDialog.Rename -> {
            CategoryRenameDialog(
                onDismissRequest = screenModel::dismissDialog,
                onRename = { screenModel.renameCategory(dialog.category, it) },
                // SY -->
                categories = successState.categories,
                category = dialog.category,
                // SY <--
            )
        }
        is SourceCategoryDialog.Delete -> {
            CategoryDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.deleteCategory(dialog.category) },
                // SY -->
                title = stringResource(MR.strings.delete_category),
                text = stringResource(MR.strings.delete_category_confirmation, dialog.category),
                // SY <--
            )
        }
    }

    LaunchedEffect(Unit) {
        screenModel.events.collectLatest { event ->
            if (event is SourceCategoryEvent.LocalizedMessage) {
                context.toast(event.stringRes)
            }
        }
    }
}
