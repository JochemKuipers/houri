package eu.kanade.tachiyomi.ui.duplicates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.duplicates.DuplicateMangaScreen
import eu.kanade.presentation.manga.DuplicateMangaDialog
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrationConfigRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.domain.manga.interactor.DuplicateEntry
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DuplicateMangaScreen() {
    val backStack = LocalBackStack.current
    val screenModel = metroViewModel<DuplicateMangaViewModel>()
    val state by screenModel.state.collectAsState()
    var selected by remember { mutableStateOf<DuplicateEntry?>(null) }

    // KMK --> The bar and the list used to be emitted as bare siblings, so under Navigation 3's
    // entry container they stacked on top of each other in the top-left corner. A Scaffold gives
    // the bar its slot and the list the padding to sit under it.
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.find_duplicates),
                navigateUp = backStack::removeLastOrNull,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            DuplicateMangaScreen(
                state = state,
                onClickEntry = { selected = it },
            )
        }
    }
    // KMK <--

    // Reuses the existing duplicate review dialog rather than restating it: it already handles
    // opening either side and reporting how many chapters the duplicate carries.
    selected?.let { entry ->
        DuplicateMangaDialog(
            duplicates = entry.duplicates,
            targetManga = entry.manga,
            onDismissRequest = { selected = null },
            // Nothing is being added here, so confirming simply closes the review.
            onConfirm = { selected = null },
            onOpenManga = { backStack.add(MangaRoute(it.id)) },
            onMigrate = { backStack.add(MigrationConfigRoute(it.id)) },
        )
    }
}
