package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import kotlinx.collections.immutable.persistentListOf
import mihon.core.navigation.ExtensionStoresRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.model.KOMIKKU_SIGNATURE
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header

@Composable
fun ExtensionScreen(
    state: ExtensionsViewModel.State,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onOpenWebView: (Extension.Available) -> Unit,
    onInstallExtension: (Extension.Available) -> Unit,
    onUninstallExtension: (Extension) -> Unit,
    onUpdateExtension: (Extension.Installed) -> Unit,
    onTrustExtension: (Extension.Untrusted) -> Unit,
    onOpenExtension: (Extension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
    onRefresh: () -> Unit,
) {
    val backStack = LocalBackStack.current

    PullRefresh(
        refreshing = state.isRefreshing,
        onRefresh = onRefresh,
        enabled = !state.isLoading,
    ) {
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.isEmpty -> {
                val msg = if (!searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.empty_screen
                }
                EmptyScreen(
                    msg,
                    modifier = Modifier.padding(contentPadding),
                    actions = persistentListOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.extensionStores,
                            icon = Icons.Outlined.Settings,
                            onClick = { backStack.add(ExtensionStoresRoute()) },
                        ),
                    ),
                )
            }
            else -> {
                ExtensionContent(
                    state = state,
                    contentPadding = contentPadding,
                    onLongClickItem = onLongClickItem,
                    onClickItemCancel = onClickItemCancel,
                    onOpenWebView = onOpenWebView,
                    onInstallExtension = onInstallExtension,
                    onUninstallExtension = onUninstallExtension,
                    onUpdateExtension = onUpdateExtension,
                    onTrustExtension = onTrustExtension,
                    onOpenExtension = onOpenExtension,
                    onClickUpdateAll = onClickUpdateAll,
                )
            }
        }
    }
}

@Composable
private fun ExtensionContent(
    state: ExtensionsViewModel.State,
    contentPadding: PaddingValues,
    onLongClickItem: (Extension) -> Unit,
    onClickItemCancel: (Extension) -> Unit,
    onOpenWebView: (Extension.Available) -> Unit,
    onInstallExtension: (Extension.Available) -> Unit,
    onUninstallExtension: (Extension) -> Unit,
    onUpdateExtension: (Extension.Installed) -> Unit,
    onTrustExtension: (Extension.Untrusted) -> Unit,
    onOpenExtension: (Extension.Installed) -> Unit,
    onClickUpdateAll: () -> Unit,
) {
    val context = LocalContext.current
    var trustState by remember { mutableStateOf<Extension.Untrusted?>(null) }
    val installGranted = rememberRequestPackageInstallsPermissionState(initialValue = true)
    // KMK -->
    val backStack = LocalBackStack.current
    // KMK <--

    FastScrollLazyColumn(
        contentPadding = contentPadding + topSmallPaddingValues,
    ) {
        if (!installGranted && state.installer?.requiresSystemPermission == true) {
            item(key = "extension-permissions-warning") {
                WarningBanner(
                    textRes = MR.strings.ext_permission_install_apps_warning,
                    modifier = Modifier.clickable {
                        context.launchRequestPackageInstallsPermission()
                    },
                )
            }
        }

        state.items.forEach { (header, items) ->
            item(
                contentType = "header",
                key = "extensionHeader-${header.hashCode()}",
            ) {
                when (header) {
                    is ExtensionUiModel.Header.Resource -> {
                        val action: @Composable RowScope.() -> Unit =
                            when (header.textRes) {
                                MR.strings.ext_updates_pending -> {
                                    {
                                        Button(onClick = { onClickUpdateAll() }) {
                                            Text(
                                                text = stringResource(MR.strings.ext_update_all),
                                                style = LocalTextStyle.current.copy(
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                ),
                                            )
                                        }
                                    }
                                }
                                // KMK -->
                                KMR.strings.extensions_page_more -> {
                                    {
                                        Button(onClick = { backStack.add(ExtensionStoresRoute()) }) {
                                            Text(
                                                text = stringResource(MR.strings.action_addExtensionStore),
                                                style = LocalTextStyle.current.copy(
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                ),
                                            )
                                        }
                                    }
                                }
                                // KMK <--
                                else -> {
                                    {}
                                }
                            }
                        ExtensionHeader(
                            textRes = header.textRes,
                            modifier = Modifier
                                // KMK -->
                                .padding(end = MaterialTheme.padding.small)
                                // KMK <--
                                .animateItemFastScroll(),
                            action = action,
                        )
                    }
                    is ExtensionUiModel.Header.Text -> {
                        ExtensionHeader(
                            text = header.text,
                            modifier = Modifier.animateItemFastScroll(),
                        )
                    }
                }
            }

            items(
                items = items,
                contentType = { "item" },
                // KMK --> Package + signature identify a row across list rebuilds and cost
                // nothing to build; the previous data-class hashCode keys walked every source
                // of every item for ~a thousand rows on each rebuild. Available rows are
                // flatMapped to unique "pkg-sourceId" names, and the same package shipped
                // under a different signature still gets its own row and key.
                key = { item ->
                    val extension = item.extension
                    val prefix = when (extension) {
                        is Extension.Untrusted -> "extension-untrusted-"
                        is Extension.Installed -> "extension-installed-"
                        is Extension.Available -> "extension-available-"
                    }
                    prefix + extension.pkgName + "-" + extension.signatureHash
                },
                // KMK <--
            ) { item ->
                // KMK --> The click handlers and the animateItem modifier are remembered per
                // slot so every parameter of [ExtensionItem] is stable across the list rebuilds
                // that follow every download/error/search emission; together with the
                // @Immutable [ExtensionUiModel.Item], a rebuild that produces a value-equal row
                // then skips it instead of re-composing and re-measuring it. Freezing the
                // first instances is behaviour-preserving because everything they capture is
                // stable for the composition's lifetime - the ViewModel, the back stack, the
                // activity context, and snapshot State objects read at click time.
                val itemModifier = remember { Modifier.animateItemFastScroll() }
                val clickItemHandler = remember {
                    { clicked: Extension ->
                        when (clicked) {
                            is Extension.Available -> onInstallExtension(clicked)
                            is Extension.Installed -> onOpenExtension(clicked)
                            is Extension.Untrusted -> {
                                trustState = clicked
                            }
                        }
                    }
                }
                val longClickItemHandler = remember { onLongClickItem }
                val secondaryActionHandler = remember {
                    { clicked: Extension ->
                        when (clicked) {
                            is Extension.Available -> onOpenWebView(clicked)
                            is Extension.Installed -> onOpenExtension(clicked)
                            else -> {}
                        }
                    }
                }
                val actionHandler = remember {
                    { clicked: Extension ->
                        when (clicked) {
                            is Extension.Available -> onInstallExtension(clicked)
                            is Extension.Installed -> {
                                if (clicked.hasUpdate) {
                                    onUpdateExtension(clicked)
                                } else {
                                    onOpenExtension(clicked)
                                }
                            }
                            is Extension.Untrusted -> {
                                trustState = clicked
                            }
                        }
                    }
                }
                ExtensionItem(
                    modifier = itemModifier,
                    item = item,
                    onClickItem = clickItemHandler,
                    onLongClickItem = longClickItemHandler,
                    onClickItemSecondaryAction = secondaryActionHandler,
                    onClickItemCancel = remember { onClickItemCancel },
                    onClickItemAction = actionHandler,
                )
                // KMK <--
            }
        }
    }
    if (trustState != null) {
        ExtensionTrustDialog(
            onClickConfirm = {
                onTrustExtension(trustState!!)
                trustState = null
            },
            onClickDismiss = {
                onUninstallExtension(trustState!!)
                trustState = null
            },
            onDismissRequest = {
                trustState = null
            },
        )
    }
}

@Composable
private fun ExtensionHeader(
    textRes: StringResource,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    ExtensionHeader(
        text = stringResource(textRes),
        modifier = modifier,
        action = action,
    )
}

@Composable
private fun ExtensionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .weight(1f),
            style = MaterialTheme.typography.header,
        )
        action()
    }
}

@Composable
private fun ExtensionTrustDialog(
    onClickConfirm: () -> Unit,
    onClickDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(MR.strings.untrusted_extension))
        },
        text = {
            Text(text = stringResource(MR.strings.untrusted_extension_message))
        },
        confirmButton = {
            TextButton(onClick = onClickConfirm) {
                Text(text = stringResource(MR.strings.ext_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onClickDismiss) {
                Text(text = stringResource(MR.strings.ext_uninstall))
            }
        },
        onDismissRequest = onDismissRequest,
    )
}

// KMK -->
@PreviewLightDark
@Composable
private fun ExtensionItemContentPreview() {
    val extAvail = Extension.Available(
        name = "Tachiyomi",
        pkgName = "com.tachiyomi.test",
        versionName = "1.2.3",
        lang = "en",
        versionCode = 1,
        libVersion = 1.0,
        isNsfw = true,
        signatureHash = "900000",
        storeName = "Houri",
        sources = emptyList(),
        apkUrl = "Test",
        iconUrl = "",
        store = ExtensionStore("https://houri", "Houri", "", KOMIKKU_SIGNATURE, ExtensionStore.Contact("", ""), false, null),
    )
    val extInstalled = Extension.Installed(
        name = "Tachiyomi",
        pkgName = "com.tachiyomi.test",
        versionName = "1.2.3",
        lang = "en",
        versionCode = 1,
        libVersion = 1.0,
        isNsfw = true,
        signatureHash = "900000",
        storeName = "Houri",
        sources = emptyList(),
        store = ExtensionStore("https://houri", "Houri", "", KOMIKKU_SIGNATURE, ExtensionStore.Contact("", ""), false, null),
        pkgFactory = null,
        icon = null,
        hasUpdate = false,
        isObsolete = false,
        isShared = false,
        isRedundant = false,
    )
    val extUntrusted = Extension.Untrusted(
        name = "Tachiyomi",
        pkgName = "com.tachiyomi.test",
        versionName = "1.2.3",
        lang = "en",
        versionCode = 1,
        libVersion = 1.0,
        isNsfw = true,
        signatureHash = "900000",
        storeName = "Repository",
    )

    TachiyomiPreviewTheme {
        Surface {
            Column {
                ExtensionItemContent(
                    extension = extAvail.copy(
                        storeName = "Repository extensions minion multiple languages various sources",
                    ),
                    installStep = InstallStep.Idle,
                )
                ExtensionItemContent(extension = extAvail, installStep = InstallStep.Installing)
                ExtensionItemContent(extension = extInstalled, installStep = InstallStep.Idle)
                ExtensionItemContent(
                    extension = extInstalled.copy(
                        isObsolete = true,
                    ),
                    installStep = InstallStep.Idle,
                )
                ExtensionItemContent(
                    extension = extInstalled.copy(
                        isRedundant = true,
                    ),
                    installStep = InstallStep.Idle,
                )
                ExtensionItemContent(extension = extUntrusted, installStep = InstallStep.Idle)
            }
        }
    }
}
// KMK <--
