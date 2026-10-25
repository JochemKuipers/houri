package mihon.feature.upcoming

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metrox.viewmodel.metroViewModel
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD

@Composable
fun UpcomingScreen() {
    val backStack = LocalBackStack.current

    val screenModel = metroViewModel<UpcomingViewModel>()
    val state by screenModel.state.collectAsState()

    UpcomingScreenContent(
        state = state,
        setSelectedYearMonth = screenModel::setSelectedYearMonth,
        onClickUpcoming = { backStack.add(MangaRoute(it.id)) },
        // KMK -->
        showUpdatingMangas = screenModel::showUpdatingMangas,
        hideUpdatingMangas = screenModel::hideUpdatingMangas,
        isPredictReleaseDate = MANGA_OUTSIDE_RELEASE_PERIOD in screenModel.restriction,
        // KMK <--
    )
}
