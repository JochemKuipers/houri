package eu.kanade.tachiyomi.ui.browse.extension.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.ExtensionDetailsScreen
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.collectLatest
import mihon.core.navigation.ExtensionDetailsRoute
import mihon.core.navigation.SourcePreferencesRoute
import mihon.core.navigation.WebViewRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun ExtensionDetailsScreen(pkgName: String) {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel = assistedMetroViewModel<ExtensionDetailsViewModel, ExtensionDetailsViewModel.Factory> {
        create(pkgName = pkgName, context = context)
    }
    val state by viewModel.state.collectAsState()

    if (state.isLoading) {
        LoadingScreen()
        return
    }

    // KMK -->
    val source = state.extension?.sources?.getOrNull(0)
    // KMK <--

    ExtensionDetailsScreen(
        navigateUp = backStack::removeLastOrNull,
        state = state,
        onClickSourcePreferences = { backStack.add(SourcePreferencesRoute(it)) },
        // KMK -->
        onOpenWebView = if (source != null && source is HttpSource) {
            {
                backStack.add(
                    WebViewRoute(
                        url = source.baseUrl,
                        initialTitle = source.name,
                        sourceId = source.id,
                    ),
                )
            }
        } else {
            null
        },
        // KMK <--
        onClickEnableAll = { viewModel.toggleSources(true) },
        onClickDisableAll = { viewModel.toggleSources(false) },
        onClickClearCookies = viewModel::clearCookies,
        onClickUninstall = viewModel::uninstallExtension,
        onClickSource = viewModel::toggleSource,
        onClickIncognito = viewModel::toggleIncognito,
    )

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            // KMK --> Pop only this screen, and only while it is still the one on top:
            // an event pop racing a back-press used to remove whatever was underneath.
            if (event is ExtensionDetailsEvent.Uninstalled && backStack.lastOrNull() is ExtensionDetailsRoute) {
                backStack.removeLastOrNull()
            }
            // KMK <--
        }
    }
}
