package eu.kanade.tachiyomi.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import kotlinx.collections.immutable.persistentListOf
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun StatsScreen() {
    val backStack = LocalBackStack.current

    val screenModel = metroViewModel<StatsViewModel>()
    val state by screenModel.state.collectAsState()

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_stats),
                navigateUp = backStack::removeLastOrNull,
                scrollBehavior = scrollBehavior,
                // SY -->
                actions = {
                    val allRead by screenModel.allRead.collectAsState()
                    AppBarActions(
                        persistentListOf(
                            AppBar.OverflowAction(
                                title = if (allRead) {
                                    stringResource(SYMR.strings.ignore_non_library_entries)
                                } else {
                                    stringResource(SYMR.strings.include_all_read_entries)
                                },
                                onClick = screenModel::toggleReadManga,
                            ),
                        ),
                    )
                },
                // SY <--
            )
        },
    ) { paddingValues ->
        if (state is StatsScreenState.Loading) {
            LoadingScreen()
            return@Scaffold
        }

        StatsScreenContent(
            state = state as? StatsScreenState.Success ?: return@Scaffold,
            paddingValues = paddingValues,
            // KMK -->
            onMangaClick = { mangaId ->
                backStack.add(MangaRoute(mangaId))
            },
            // KMK <--
        )
    }
}
