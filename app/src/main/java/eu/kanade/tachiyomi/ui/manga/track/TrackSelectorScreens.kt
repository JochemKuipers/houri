package eu.kanade.tachiyomi.ui.manga.track


import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.presentation.track.TrackChapterSelector
import eu.kanade.presentation.track.TrackScoreSelector
import eu.kanade.presentation.track.TrackStatusSelector
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

// TrackInfoDialogRoute (an OverlaySheetScene destination in AppNavGraph), and everything the
// sheet itself navigates between lives in these local routes.
@Composable
fun TrackStatusSelectorScreen(
    track: Track,
    serviceId: Long,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackStatusSelectorViewModel, TrackStatusSelectorViewModel.Factory> {
        create(track = track, serviceId = serviceId)
    }
    val state by screenModel.state.collectAsState()
    TrackStatusSelector(
        selection = state.selection,
        onSelectionChange = screenModel::setSelection,
        selections = remember { screenModel.getSelections() },
        onConfirm = {
            screenModel.setStatus()
            backStack.removeLastOrNull()
        },
        onDismissRequest = backStack::removeLastOrNull,
    )
}

@AssistedInject
class TrackStatusSelectorViewModel(
    @Assisted private val track: Track,
    @Assisted private val serviceId: Long,
    trackerManager: TrackerManager,
) : StateViewModel<TrackStatusSelectorViewModel.State>(State(track.status)) {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(track: Track, serviceId: Long): TrackStatusSelectorViewModel
    }

    fun getSelections(): Map<Long, StringResource?> {
        return tracker.getStatusList().associateWith { tracker.getStatus(it) }
    }

    fun setSelection(selection: Long) {
        mutableState.update { it.copy(selection = selection) }
    }

    fun setStatus() {
        viewModelScope.launchNonCancellable {
            tracker.setRemoteStatus(track.toDbTrack(), state.value.selection)
        }
    }

    @Immutable
    data class State(
        val selection: Long,
    )
}

@Composable
fun TrackChapterSelectorScreen(
    track: Track,
    serviceId: Long,
    mangaId: Long = 0L,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackChapterSelectorViewModel, TrackChapterSelectorViewModel.Factory> {
        create(track = track, mangaId = mangaId, serviceId = serviceId)
    }
    val state by screenModel.state.collectAsState()

    TrackChapterSelector(
        selection = state.selection,
        onSelectionChange = screenModel::setSelection,
        range = remember { screenModel.getRange() },
        onConfirm = {
            screenModel.setChapter()
            backStack.removeLastOrNull()
        },
        onDismissRequest = backStack::removeLastOrNull,
    )
}

@AssistedInject
class TrackChapterSelectorViewModel(
    @Assisted private val track: Track,
    @Assisted private val mangaId: Long,
    @Assisted private val serviceId: Long,
    private val trackerManager: TrackerManager,
    private val getTracks: GetTracks,
) : StateViewModel<TrackChapterSelectorViewModel.State>(State(track.lastChapterRead.toInt())) {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(track: Track, mangaId: Long, serviceId: Long): TrackChapterSelectorViewModel
    }

    fun getRange(): Iterable<Int> {
        val endRange = if (track.totalChapters > 0) {
            track.totalChapters
        } else {
            10000
        }
        return 0..endRange.toInt()
    }

    fun setSelection(selection: Int) {
        mutableState.update { it.copy(selection = selection) }
    }

    fun setChapter() {
        viewModelScope.launchNonCancellable {
            val newChapter = state.value.selection
            tracker.setRemoteLastChapterRead(track.toDbTrack(), newChapter)
            if (mangaId != 0L) {
                try {
                    val allTracks = getTracks.await(mangaId)
                    for (other in allTracks) {
                        if (other.trackerId == serviceId) continue
                        val otherTracker = trackerManager.get(other.trackerId) ?: continue
                        if (!otherTracker.isLoggedIn) continue
                        if (other.lastChapterRead != newChapter.toDouble()) {
                            otherTracker.setRemoteLastChapterRead(other.toDbTrack(), newChapter)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    @Immutable
    data class State(
        val selection: Int,
    )
}

@Composable
fun TrackScoreSelectorScreen(
    track: Track,
    serviceId: Long,
    mangaId: Long = 0L,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackScoreSelectorViewModel, TrackScoreSelectorViewModel.Factory> {
        create(track = track, mangaId = mangaId, serviceId = serviceId)
    }
    val state by screenModel.state.collectAsState()

    TrackScoreSelector(
        selection = state.selection,
        onSelectionChange = screenModel::setSelection,
        selections = remember { screenModel.getSelections() },
        onConfirm = {
            screenModel.setScore()
            backStack.removeLastOrNull()
        },
        onDismissRequest = backStack::removeLastOrNull,
    )
}

@AssistedInject
class TrackScoreSelectorViewModel(
    @Assisted private val track: Track,
    @Assisted private val mangaId: Long,
    @Assisted private val serviceId: Long,
    private val trackerManager: TrackerManager,
    private val getTracks: GetTracks,
) : StateViewModel<TrackScoreSelectorViewModel.State>(
    State(trackerManager.get(serviceId)!!.displayScore(track)),
) {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(track: Track, mangaId: Long, serviceId: Long): TrackScoreSelectorViewModel
    }

    fun getSelections(): ImmutableList<String> {
        return tracker.getScoreList()
    }

    fun setSelection(selection: String) {
        mutableState.update { it.copy(selection = selection) }
    }

    fun setScore() {
        viewModelScope.launchNonCancellable {
            tracker.setRemoteScore(track.toDbTrack(), state.value.selection)
            // KMK --> universal tracker: fan the normalized 10-point rating out to
            // the other bound trackers (mirrors chapter/date fan-out), converting
            // across scales so MangaBaka (0-100) and MangaUpdates (0-10) stay in sync.
            if (mangaId != 0L) {
                try {
                    val primaryScore = tracker.indexToScore(
                        tracker.getScoreList().indexOf(state.value.selection).takeIf { it >= 0 } ?: 0,
                    )
                    val primary10 = tracker.get10PointScore(track.copy(score = primaryScore))
                    val allTracks = getTracks.await(mangaId)
                    for (other in allTracks) {
                        if (other.trackerId == serviceId) continue
                        val otherTracker = trackerManager.get(other.trackerId) ?: continue
                        if (!otherTracker.isLoggedIn) continue
                        if (otherTracker.getScoreList().isEmpty()) continue
                        try {
                            val targetSelection = closestScoreString(otherTracker, other, primary10) ?: continue
                            if (otherTracker.displayScore(other) == targetSelection) continue
                            otherTracker.setRemoteScore(other.toDbTrack(), targetSelection)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
            // KMK <--
        }
    }

    private fun closestScoreString(
        target: Tracker,
        other: Track,
        primary10: Double,
    ): String? {
        val options = target.getScoreList()
        if (options.isEmpty()) return null
        var best: String? = null
        var bestDelta = Double.MAX_VALUE
        for (i in options.indices) {
            val candidateScore = try {
                target.indexToScore(i)
            } catch (_: Exception) {
                continue
            }
            val candidate10 = try {
                target.get10PointScore(other.copy(score = candidateScore))
            } catch (_: Exception) {
                continue
            }
            val delta = kotlin.math.abs(candidate10 - primary10)
            if (delta < bestDelta) {
                bestDelta = delta
                best = options[i]
            }
        }
        return best
    }

    @Immutable
    data class State(
        val selection: String,
    )
}

