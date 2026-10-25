package eu.kanade.tachiyomi.ui.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.webview.WebViewScreenContent
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import mihon.core.navigation.util.LocalAssistContentManager
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.util.lang.launchIO

@Composable
fun WebViewScreen(
    url: String,
    initialTitle: String? = null,
    sourceId: Long? = null,
) {
    val backStack = LocalBackStack.current
    val context = LocalContext.current
    val assistContentManager = LocalAssistContentManager.current
    val screenModel = assistedMetroViewModel<WebViewViewModel, WebViewViewModel.Factory> {
        create(sourceId = sourceId)
    }

    WebViewScreenContent(
        onNavigateUp = { backStack.removeLastOrNull() },
        initialTitle = initialTitle,
        url = url,
        headers = screenModel.headers,
        onUrlChange = { assistContentManager.currentAssistUrl = it },
        onShare = { screenModel.shareWebpage(context, it) },
        onOpenInBrowser = { screenModel.openInBrowser(context, it) },
        onClearCookies = screenModel::clearCookies,
    )

    // KMK -->
    LaunchedEffect(Unit) {
        with(DiscordRPCService) {
            discordScope.launchIO { setScreen(context, DiscordScreen.WEBVIEW) }
        }
    }
    // <-- KMK
}
