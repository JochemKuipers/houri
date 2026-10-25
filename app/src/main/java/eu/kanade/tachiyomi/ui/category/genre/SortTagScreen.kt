package eu.kanade.tachiyomi.ui.category.genre

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun SortTagScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val screenModel = metroViewModel<SortTagViewModel>()

    val state by screenModel.state.collectAsState()

    if (state is SortTagScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as SortTagScreenState.Success

    eu.kanade.presentation.category.SortTagScreen(
        state = successState,
        onClickCreate = { screenModel.showDialog(SortTagDialog.Create) },
        onClickDelete = { screenModel.showDialog(SortTagDialog.Delete(it)) },
        onClickMoveUp = screenModel::moveUp,
        onClickMoveDown = screenModel::moveDown,
        navigateUp = backStack::removeLastOrNull,
    )

    when (val dialog = successState.dialog) {
        null -> {}
        SortTagDialog.Create -> {
            CategoryCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = { screenModel.createTag(it) },
                categories = successState.tags,
                title = stringResource(SYMR.strings.add_tag),
                extraMessage = stringResource(SYMR.strings.action_add_tags_message),
                alreadyExistsError = SYMR.strings.error_tag_exists,
            )
        }
        is SortTagDialog.Delete -> {
            CategoryDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.delete(dialog.tag) },
                title = stringResource(SYMR.strings.delete_tag),
                text = stringResource(SYMR.strings.delete_tag_confirmation, dialog.tag),
            )
        }
    }

    LaunchedEffect(Unit) {
        screenModel.events.collectLatest { event ->
            if (event is SortTagEvent.LocalizedMessage) {
                context.toast(event.stringRes)
            }
        }
    }
}
