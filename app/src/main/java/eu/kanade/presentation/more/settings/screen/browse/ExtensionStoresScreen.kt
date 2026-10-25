package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreConfirmDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreCreateDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreDeleteDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun ExtensionStoresScreen(url: String?) {
    val context = LocalContext.current
    val backStack = LocalBackStack.current

    val screenModel = metroViewModel<ExtensionStoresViewModel>()
    val state by screenModel.state.collectAsState()

    LaunchedEffect(url) {
        url?.let { screenModel.addFromDeeplink(url) }
    }

    if (state is ExtensionStoreScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as ExtensionStoreScreenState.Success

    ExtensionStoresScreen(
        state = successState,
        onClickCreate = { screenModel.showDialog(ExtensionStoreDialog.Create()) },
        onCopy = { context.copyToClipboard(it.indexUrl, it.indexUrl) },
        onOpenWebsite = { it.contact.website.let(context::openInBrowser) },
        onOpenDiscord = { it.contact.discord?.let(context::openInBrowser) },
        // KMK -->
        onClickEnable = {
            screenModel.enableStore(it.indexUrl)
            screenModel.refreshExtensionList()
            context.toast(KMR.strings.extensions_page_need_refresh)
        },
        onClickDisable = {
            screenModel.disableStore(it.indexUrl)
            screenModel.refreshExtensionList()
            context.toast(KMR.strings.extensions_page_need_refresh)
        },
        // KMK <--
        onClickDelete = { screenModel.showDialog(ExtensionStoreDialog.Delete(it)) },
        onClickRefresh = { screenModel.refreshRepos() },
        navigateUp = backStack::removeLastOrNull,
    )

    when (val dialog = successState.dialog) {
        null -> {}
        is ExtensionStoreDialog.Create -> {
            ExtensionStoreCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = { screenModel.createRepo(it) },
                storeIndexUrls = successState.stores.map { it.indexUrl }.toSet(),
                processing = dialog.processing,
                errorMessage = dialog.errorMessage,
            )
        }
        is ExtensionStoreDialog.Delete -> {
            ExtensionStoreDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.deleteRepo(dialog.store.indexUrl) },
                storeName = dialog.store.name,
                storeIndexUrl = dialog.store.indexUrl,
            )
        }
        is ExtensionStoreDialog.Confirm -> {
            ExtensionStoreConfirmDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = { screenModel.createRepo(dialog.url) },
                storeIndexUrl = dialog.url,
                storeAlreadyExists = dialog.alreadyExists,
                processing = dialog.processing,
                errorMessage = dialog.errorMessage,
            )
        }
    }
}
