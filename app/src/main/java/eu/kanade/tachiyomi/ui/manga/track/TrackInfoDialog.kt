package eu.kanade.tachiyomi.ui.manga.track

import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.serialization.Serializable
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.domain.track.model.Track

// KMK --> The track sheet hosts its own nested Nav3 stack: the main back stack pushes
// TrackInfoDialogRoute (an OverlaySheetScene destination in AppNavGraph), and everything the
// sheet itself navigates between lives in these local routes.
internal val LocalTrackMainBackStack = androidx.compose.runtime.compositionLocalOf<NavBackStack<NavKey>> {
    error("LocalTrackMainBackStack not initialized!")
}

@Composable
fun TrackInfoDialog(
    mangaId: Long,
    mangaTitle: String,
    sourceId: Long,
    onDismissRequest: () -> Unit,
) {
    val mainBackStack = LocalBackStack.current
    val sheetBackStack = rememberNavBackStack(TrackInfoDialogHomeRoute(mangaId, mangaTitle, sourceId))

    androidx.compose.runtime.CompositionLocalProvider(
        LocalBackStack provides sheetBackStack,
        LocalTrackMainBackStack provides mainBackStack,
    ) {
        AdaptiveSheet(
            onDismissRequest = onDismissRequest,
            enableImplicitDismiss = sheetBackStack.size == 1,
        ) {
            NavDisplay(
                backStack = sheetBackStack,
                // KMK --> Same stale-enabled race as the two main NavDisplays: a press racing
                // the pop that emptied previousEntries can reach this with the sheet's home
                // route as the last entry. Popping it crashed NavDisplay with an empty
                // backstack; the sheet's home route is the floor.
                onBack = {
                    if (sheetBackStack.size > 1) sheetBackStack.removeLastOrNull()
                },
                // KMK <--
                sizeTransform = SizeTransform(),
                transitionSpec = { trackFade },
                popTransitionSpec = { trackFade },
                predictivePopTransitionSpec = { trackFade },
                entryProvider = entryProvider {
                    entry<TrackInfoDialogHomeRoute> { route ->
                        TrackInfoDialogHomeScreen(route.mangaId, route.mangaTitle, route.sourceId)
                    }
                    entry<TrackStatusSelectorRoute> { route ->
                        TrackStatusSelectorScreen(route.track, route.serviceId)
                    }
                    entry<TrackChapterSelectorRoute> { route ->
                        TrackChapterSelectorScreen(route.track, route.serviceId, route.mangaId)
                    }
                    entry<TrackScoreSelectorRoute> { route ->
                        TrackScoreSelectorScreen(route.track, route.serviceId, route.mangaId)
                    }
                    entry<TrackDateSelectorRoute> { route ->
                        TrackDateSelectorScreen(route.track, route.serviceId, route.start, route.mangaId)
                    }
                    entry<TrackDateRemoverRoute> { route ->
                        TrackDateRemoverScreen(route.track, route.serviceId, route.start)
                    }
                    entry<TrackerRemoveRoute> { route ->
                        TrackerRemoveScreen(route.mangaId, route.track, route.serviceId)
                    }
                    // KMK --> one confirmation for every selected tracker
                    entry<TrackerBulkRemoveRoute> { route ->
                        TrackerBulkRemoveScreen(route.mangaId, route.tracks)
                    }
                    // KMK <--
                    entry<TrackerSearchRoute> { route ->
                        TrackerSearchScreen(route.mangaId, route.initialQuery, route.currentUrl, route.serviceId)
                    }
                },
            )
        }
    }
}

private val trackFade = fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90))

@Serializable
data class TrackInfoDialogHomeRoute(
    val mangaId: Long,
    val mangaTitle: String,
    val sourceId: Long,
) : NavKey

@Serializable
data class TrackStatusSelectorRoute(
    val track: Track,
    val serviceId: Long,
) : NavKey

@Serializable
data class TrackChapterSelectorRoute(
    val track: Track,
    val serviceId: Long,
    val mangaId: Long = 0L,
) : NavKey

@Serializable
data class TrackScoreSelectorRoute(
    val track: Track,
    val serviceId: Long,
    val mangaId: Long = 0L,
) : NavKey

@Serializable
data class TrackDateSelectorRoute(
    val track: Track,
    val serviceId: Long,
    val start: Boolean,
    val mangaId: Long = 0L,
) : NavKey

@Serializable
data class TrackDateRemoverRoute(
    val track: Track,
    val serviceId: Long,
    val start: Boolean,
) : NavKey

@Serializable
data class TrackerRemoveRoute(
    val mangaId: Long,
    val track: Track,
    val serviceId: Long,
) : NavKey

// KMK -->
@Serializable
data class TrackerBulkRemoveRoute(
    val mangaId: Long,
    val tracks: List<Track>,
) : NavKey
// KMK <--

@Serializable
data class TrackerSearchRoute(
    val mangaId: Long,
    val initialQuery: String,
    val currentUrl: String?,
    val serviceId: Long,
) : NavKey
// KMK <--
