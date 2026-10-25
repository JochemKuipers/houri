package exh.pagepreview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import exh.pagepreview.components.PagePreviewScreen
import mihon.core.navigation.util.LocalBackStack

@Composable
fun PagePreviewScreen(mangaId: Long) {
    val screenModel = assistedMetroViewModel<PagePreviewViewModel, PagePreviewViewModel.Factory> {
        create(mangaId = mangaId)
    }
    val context = LocalContext.current
    val state by screenModel.state.collectAsState()
    val backStack = LocalBackStack.current
    PagePreviewScreen(
        state = state,
        pageDialogOpen = screenModel.pageDialogOpen,
        onPageSelected = screenModel::moveToPage,
        onOpenPage = { openPage(context, state, it) },
        onOpenPageDialog = { screenModel.pageDialogOpen = true },
        onDismissPageDialog = { screenModel.pageDialogOpen = false },
        navigateUp = backStack::removeLastOrNull,
    )
}

fun openPage(context: Context, state: PagePreviewState, page: Int) {
    if (state !is PagePreviewState.Success) return
    context.run {
        startActivity(ReaderActivity.newIntent(this, state.manga.id, state.chapter.id, page))
    }
}
