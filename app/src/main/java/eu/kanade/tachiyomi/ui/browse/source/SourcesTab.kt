package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined._18UpRating
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.source.model.installedExtension
import eu.kanade.presentation.browse.SourceCategoriesDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.ExtensionDetailsRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.SmartSearchRoute
import mihon.core.navigation.SourceFeedRoute
import mihon.core.navigation.SourcesFilterRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun sourcesTab(
    smartSearchConfig: SmartSearchConfig? = null,
): TabContent {
    val backStack = LocalBackStack.current
    val viewModel = assistedMetroViewModel<SourcesViewModel, SourcesViewModel.Factory> {
        create(smartSearchConfig = smartSearchConfig)
    }
    val state by viewModel.state.collectAsState()

    return TabContent(
        // SY -->
        titleRes = when (smartSearchConfig == null) {
            true -> MR.strings.label_sources
            false -> SYMR.strings.find_in_another_source
        },
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { backStack.add(GlobalSearchRoute(smartSearchConfig?.origTitle ?: "")) },
            ),
            // KMK -->
            AppBar.Action(
                title = stringResource(KMR.strings.action_toggle_nsfw_only),
                icon = Icons.Outlined._18UpRating,
                iconTint = if (state.nsfwOnly) MaterialTheme.colorScheme.error else LocalContentColor.current,
                onClick = { viewModel.toggleNsfwOnly() },
            ),
            // KMK <--
        ).let {
            when (smartSearchConfig) {
                null -> {
                    it.adding(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_filter),
                            icon = Icons.Outlined.FilterList,
                            onClick = { backStack.add(SourcesFilterRoute) },
                        ),
                    )
                }
                // Merge: find in another source
                else -> it
            }
        },
        // SY <--
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    // SY -->
                    val route = when {
                        // Search selected source for entries to merge or for the recommending entry
                        smartSearchConfig != null -> SmartSearchRoute(
                            sourceId = source.id,
                            origTitle = smartSearchConfig.origTitle,
                            origMangaId = smartSearchConfig.origMangaId,
                        )
                        listing == BrowseSourceViewModel.Listing.Popular && viewModel.useNewSourceNavigation ->
                            SourceFeedRoute(source.id)
                        else -> BrowseSourceRoute(source.id, listing.query)
                    }
                    backStack.add(route)
                    // SY <--
                },
                onClickPin = viewModel::togglePin,
                onLongClickItem = viewModel::showSourceDialog,
                // KMK -->
                onChangeSearchQuery = viewModel::search,
                // KMK <--
            )

            when (val dialog = state.dialog) {
                is SourcesViewModel.Dialog.SourceLongClick -> {
                    val source = dialog.source
                    SourceOptionsDialog(
                        source = source,
                        onClickPin = {
                            viewModel.togglePin(source)
                            viewModel.closeDialog()
                        },
                        onClickDisable = {
                            viewModel.toggleSource(source)
                            viewModel.closeDialog()
                        },
                        // SY -->
                        onClickSetCategories = {
                            viewModel.showSourceCategoriesDialog(source)
                        }.takeIf { state.categories.isNotEmpty() },
                        onClickToggleDataSaver = {
                            viewModel.toggleExcludeFromDataSaver(source)
                            viewModel.closeDialog()
                        }.takeIf { state.dataSaverEnabled },
                        // SY <--
                        onDismiss = viewModel::closeDialog,
                        // KMK -->
                        onClickSettings = {
                            source.installedExtension?.let { ext ->
                                backStack.add(ExtensionDetailsRoute(ext.pkgName))
                            }
                            viewModel.closeDialog()
                        },
                        // KMK <--
                    )
                }
                is SourcesViewModel.Dialog.SourceCategories -> {
                    val source = dialog.source
                    SourceCategoriesDialog(
                        source = source,
                        categories = state.categories,
                        onClickCategories = { categories ->
                            viewModel.setSourceCategories(source, categories)
                            viewModel.closeDialog()
                        },
                        onDismissRequest = viewModel::closeDialog,
                    )
                }
                null -> Unit
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        SourcesViewModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
