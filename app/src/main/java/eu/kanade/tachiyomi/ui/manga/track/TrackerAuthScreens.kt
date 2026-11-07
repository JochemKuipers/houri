package eu.kanade.tachiyomi.ui.manga.track


import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.track.TrackerSearch
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// TrackInfoDialogRoute (an OverlaySheetScene destination in AppNavGraph), and everything the
// sheet itself navigates between lives in these local routes.
@Composable
fun TrackerSearchScreen(
    mangaId: Long,
    initialQuery: String,
    currentUrl: String?,
    serviceId: Long,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackerSearchViewModel, TrackerSearchViewModel.Factory> {
        create(mangaId = mangaId, currentUrl = currentUrl, initialQuery = initialQuery, serviceId = serviceId)
    }

    val state by screenModel.state.collectAsState()

    val textFieldState = rememberTextFieldState(initialQuery)
    TrackerSearch(
        state = textFieldState,
        onDispatchQuery = { screenModel.trackingSearch(textFieldState.text.toString()) },
        queryResult = state.queryResult,
        selected = state.selected,
        onSelectedChange = screenModel::updateSelection,
        onConfirmSelection = f@{ private: Boolean ->
            val selected = state.selected ?: return@f
            selected.private = private
            screenModel.registerTracking(selected)
            backStack.removeLastOrNull()
        },
        onDismissRequest = backStack::removeLastOrNull,
        supportsPrivateTracking = screenModel.supportsPrivateTracking,
    )
}

@AssistedInject
class TrackerSearchViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val currentUrl: String?,
    @Assisted initialQuery: String,
    @Assisted private val serviceId: Long,
    trackerManager: TrackerManager,
    private val context: Context,
) : StateViewModel<TrackerSearchViewModel.State>(State()) {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, currentUrl: String?, initialQuery: String, serviceId: Long): TrackerSearchViewModel
    }

    val supportsPrivateTracking = tracker.supportsPrivateTracking

    init {
        // Run search on first launch
        if (initialQuery.isNotBlank()) {
            trackingSearch(initialQuery)
        }
    }

    fun trackingSearch(query: String) {
        viewModelScope.launch {
            // To show loading state
            mutableState.update { it.copy(queryResult = null, selected = null) }

            val result = withIOContext {
                try {
                    val results = tracker.search(query.sanitize())
                    Result.success(results)
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            mutableState.update { oldState ->
                oldState.copy(
                    queryResult = result,
                    selected = result.getOrNull()?.find { it.tracking_url == currentUrl },
                )
            }
        }
    }

    fun registerTracking(item: TrackSearch) {
        viewModelScope.launchNonCancellable {
            // KMK --> an uncaught register (e.g. MangaBaka 409 on an already-added
            // entry) used to take down the app with a FATAL; surface it as a toast.
            // KMK <--
            try {
                tracker.register(item, mangaId)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to register tracking on ${tracker.name}" }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            tracker.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
        }
    }

    fun updateSelection(selected: TrackSearch) {
        mutableState.update { it.copy(selected = selected) }
    }

    @Immutable
    data class State(
        val queryResult: Result<List<TrackSearch>>? = null,
        val selected: TrackSearch? = null,
    )
}

@Composable
fun TrackerRemoveScreen(
    mangaId: Long,
    track: Track,
    serviceId: Long,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackerRemoveViewModel, TrackerRemoveViewModel.Factory> {
        create(mangaId = mangaId, track = track, serviceId = serviceId)
    }
    val serviceName = screenModel.getName()
    var removeRemoteTrack by remember { mutableStateOf(false) }
    AlertDialogContent(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = stringResource(MR.strings.track_delete_title, serviceName),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = stringResource(MR.strings.track_delete_text, serviceName),
                )

                if (screenModel.isDeletable()) {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.track_delete_remote_text, serviceName),
                        checked = removeRemoteTrack,
                        onCheckedChange = { removeRemoteTrack = it },
                    )
                }
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    MaterialTheme.padding.small,
                    Alignment.End,
                ),
            ) {
                TextButton(onClick = backStack::removeLastOrNull) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                FilledTonalButton(
                    onClick = {
                        screenModel.unregisterTracking(serviceId)
                        if (removeRemoteTrack) screenModel.deleteMangaFromService()
                        backStack.removeLastOrNull()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            }
        },
    )
}

@AssistedInject
class TrackerRemoveViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val track: Track,
    @Assisted private val serviceId: Long,
    trackerManager: TrackerManager,
    private val deleteTrack: DeleteTrack,
) : ViewModel() {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, track: Track, serviceId: Long): TrackerRemoveViewModel
    }

    fun getName() = tracker.name

    fun isDeletable() = tracker is DeletableTracker

    fun deleteMangaFromService() {
        viewModelScope.launchNonCancellable {
            try {
                (tracker as DeletableTracker).delete(track)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete entry from service" }
            }
        }
    }

    fun unregisterTracking(serviceId: Long) {
        viewModelScope.launchNonCancellable { deleteTrack.await(mangaId, serviceId) }
    }
}

// KMK --> one confirmation for every selected tracker instead of one
// TrackerRemoveScreen per service, with per-tracker checkmarks + tri-state select-all
@Composable
fun TrackerBulkRemoveScreen(
    mangaId: Long,
    tracks: List<Track>,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackerBulkRemoveViewModel, TrackerBulkRemoveViewModel.Factory> {
        create(mangaId = mangaId, tracks = tracks)
    }
    val state by screenModel.state.collectAsState()

    val allIds = remember(tracks) { tracks.map { it.trackerId }.toSet() }
    val triState = when {
        state.selectedIds.containsAll(allIds) -> ToggleableState.On
        state.selectedIds.isEmpty() -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    AlertDialogContent(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = stringResource(KMR.strings.track_bulk_remove_title),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = stringResource(KMR.strings.track_bulk_remove_text),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(
                        state = triState,
                        onClick = { screenModel.setAll(triState != ToggleableState.On) },
                    )
                    Text(text = stringResource(MR.strings.action_select_all))
                }

                tracks.forEach { track ->
                    LabeledCheckbox(
                        label = screenModel.trackerName(track.trackerId),
                        checked = track.trackerId in state.selectedIds,
                        onCheckedChange = { screenModel.toggle(track.trackerId) },
                    )
                }

                if (screenModel.anyDeletableSelected()) {
                    LabeledCheckbox(
                        label = stringResource(KMR.strings.track_bulk_remove_remote_text),
                        checked = state.removeRemote,
                        onCheckedChange = screenModel::setRemoveRemote,
                    )
                }
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    MaterialTheme.padding.small,
                    Alignment.End,
                ),
            ) {
                TextButton(onClick = backStack::removeLastOrNull) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                FilledTonalButton(
                    onClick = {
                        screenModel.confirm()
                        backStack.removeLastOrNull()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(text = stringResource(MR.strings.action_remove))
                }
            }
        },
    )
}

@AssistedInject
class TrackerBulkRemoveViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val tracks: List<Track>,
    private val trackerManager: TrackerManager,
    private val deleteTrack: DeleteTrack,
) : StateViewModel<TrackerBulkRemoveViewModel.State>(State(selectedIds = tracks.map { it.trackerId }.toSet())) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, tracks: List<Track>): TrackerBulkRemoveViewModel
    }

    fun trackerName(trackerId: Long): String =
        trackerManager.get(trackerId)?.name ?: trackerId.toString()

    fun anyDeletableSelected(): Boolean =
        state.value.selectedIds.any { trackerManager.get(it) is DeletableTracker }

    fun toggle(trackerId: Long) {
        mutableState.update {
            val selected = it.selectedIds.toMutableSet()
            if (!selected.add(trackerId)) selected.remove(trackerId)
            it.copy(selectedIds = selected)
        }
    }

    fun setAll(select: Boolean) {
        mutableState.update {
            it.copy(
                selectedIds = if (select) {
                    tracks.map { track -> track.trackerId }.toSet()
                } else {
                    emptySet()
                },
            )
        }
    }

    fun setRemoveRemote(remove: Boolean) {
        mutableState.update { it.copy(removeRemote = remove) }
    }

    fun confirm() {
        val selected = state.value.selectedIds
        val removeRemote = state.value.removeRemote
        viewModelScope.launchNonCancellable {
            tracks.filter { it.trackerId in selected }.forEach { track ->
                deleteTrack.await(mangaId, track.trackerId)
                if (removeRemote) {
                    try {
                        (trackerManager.get(track.trackerId) as? DeletableTracker)?.delete(track)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to delete entry from service" }
                    }
                }
            }
        }
    }

    @Immutable
    data class State(
        val selectedIds: Set<Long> = emptySet(),
        val removeRemote: Boolean = false,
    )
}
// KMK <--
