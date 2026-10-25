package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.more.ComingUpdatesScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import mihon.core.navigation.util.LocalBackStack

@Composable
fun ComingUpdatesScreen(
    versionName: String,
    changelogInfo: String,
    releaseLink: String,
    downloadLink: String,
) {
    val backStack = LocalBackStack.current
    val context = LocalContext.current
    val viewModel = assistedMetroViewModel<NewUpdateViewModel, NewUpdateViewModel.Factory> {
        create(
            changelogInfo = changelogInfo,
            downloadLink = downloadLink,
        )
    }

    val state by viewModel.state.collectAsState()

    ComingUpdatesScreen(
        versionName = versionName,
        stage = state.stage,
        downloadProgress = { state.downloadProgress },
        changelogInfo = state.changelogInfo,
        onOpenInBrowser = { context.openInBrowser(releaseLink) },
        onAcceptUpdate = {
            when (state.stage) {
                NewUpdateViewModel.Stage.Available, NewUpdateViewModel.Stage.Failed -> viewModel.startDownload()
                NewUpdateViewModel.Stage.Downloaded -> viewModel.installUpdate()
                else -> Unit
            }
        },
        onRejectUpdate = backStack::removeLastOrNull,
    )
}
