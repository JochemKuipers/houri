package exh.ui.smartsearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.util.system.toast
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.replace
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Search source for entries, either search for entries to merge or search for recommending entry.
 */
@Composable
fun SmartSearchScreen(
    sourceId: Long,
    origTitle: String,
    origMangaId: Long?,
) {
    val smartSearchConfig = SmartSearchConfig(origTitle, origMangaId)

    val screenModel = assistedMetroViewModel<SmartSearchViewModel, SmartSearchViewModel.Factory> {
        create(sourceId = sourceId, config = smartSearchConfig)
    }

    val backStack = LocalBackStack.current
    val context = LocalContext.current
    val state by screenModel.state.collectAsState()
    LaunchedEffect(state) {
        val results = state
        if (results != null) {
            if (results is SmartSearchViewModel.SearchResults.Found) {
                backStack.replace(
                    MangaRoute(
                        results.manga.id,
                        // KMK -->
                        // Finding the entry to be merged to, so we don't want to expand description
                        // so that user can see the `Merge to another` button
                        false,
                        // KMK <--
                        smartSearchOrigTitle = smartSearchConfig.origTitle,
                        smartSearchOrigMangaId = smartSearchConfig.origMangaId,
                    ),
                )
            } else {
                if (results is SmartSearchViewModel.SearchResults.NotFound) {
                    context.toast(SYMR.strings.could_not_find_entry)
                } else {
                    context.toast(SYMR.strings.automatic_search_error)
                }
                backStack.replace(
                    BrowseSourceRoute(
                        sourceId = screenModel.source.id,
                        listingQuery = smartSearchConfig.origTitle,
                        smartSearchOrigTitle = smartSearchConfig.origTitle,
                        smartSearchOrigMangaId = smartSearchConfig.origMangaId,
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = screenModel.source.name,
                navigateUp = backStack::removeLastOrNull,
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(SYMR.strings.searching_source),
                style = MaterialTheme.typography.titleLarge,
            )
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
        }
    }
}
