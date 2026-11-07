package eu.kanade.tachiyomi.ui.manga.track


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.presentation.track.TrackDateSelector
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.flow.catch
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.popUntil
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// TrackInfoDialogRoute (an OverlaySheetScene destination in AppNavGraph), and everything the
// sheet itself navigates between lives in these local routes.
@Composable
fun TrackDateSelectorScreen(
    track: Track,
    serviceId: Long,
    start: Boolean,
    mangaId: Long = 0L,
) {
    val selectableDates = remember {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val targetDate = Instant.ofEpochMilli(utcTimeMillis).toLocalDate(ZoneOffset.UTC)

                // Disallow future dates
                if (targetDate > LocalDate.now(ZoneOffset.UTC)) return false

                return when {
                    // Disallow setting start date after finish date
                    start && track.finishDate > 0 -> {
                        val finishDate = Instant.ofEpochMilli(track.finishDate).toLocalDate(ZoneOffset.UTC)
                        targetDate <= finishDate
                    }
                    // Disallow setting finish date before start date
                    !start && track.startDate > 0 -> {
                        val startDate = Instant.ofEpochMilli(track.startDate).toLocalDate(ZoneOffset.UTC)
                        startDate <= targetDate
                    }
                    else -> {
                        true
                    }
                }
            }

            override fun isSelectableYear(year: Int): Boolean {
                // Disallow future years
                if (year > LocalDate.now(ZoneOffset.UTC).year) return false

                return when {
                    // Disallow setting start year after finish year
                    start && track.finishDate > 0 -> {
                        val finishDate = Instant.ofEpochMilli(track.finishDate).toLocalDate(ZoneOffset.UTC)
                        year <= finishDate.year
                    }
                    // Disallow setting finish year before start year
                    !start && track.startDate > 0 -> {
                        val startDate = Instant.ofEpochMilli(track.startDate).toLocalDate(ZoneOffset.UTC)
                        startDate.year <= year
                    }
                    else -> {
                        true
                    }
                }
            }
        }
    }

    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackDateSelectorViewModel, TrackDateSelectorViewModel.Factory> {
        create(track = track, start = start, mangaId = mangaId, serviceId = serviceId)
    }

    val canRemove = if (start) {
        track.startDate > 0
    } else {
        track.finishDate > 0
    }
    TrackDateSelector(
        title = if (start) {
            stringResource(MR.strings.track_started_reading_date)
        } else {
            stringResource(MR.strings.track_finished_reading_date)
        },
        initialSelectedDateMillis = screenModel.initialSelection,
        selectableDates = selectableDates,
        onConfirm = {
            screenModel.setDate(it)
            backStack.removeLastOrNull()
        },
        onRemove = { screenModel.confirmRemoveDate(backStack) }.takeIf { canRemove },
        onDismissRequest = backStack::removeLastOrNull,
    )
}

@AssistedInject
class TrackDateSelectorViewModel(
    @Assisted private val track: Track,
    @Assisted private val start: Boolean,
    @Assisted private val mangaId: Long,
    @Assisted private val serviceId: Long,
    private val trackerManager: TrackerManager,
    private val getTracks: GetTracks,
) : ViewModel() {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(track: Track, start: Boolean, mangaId: Long, serviceId: Long): TrackDateSelectorViewModel
    }

    // In UTC
    val initialSelection: Long
        get() {
            val millis = (if (start) track.startDate else track.finishDate)
                .takeIf { it != 0L }
                ?: Instant.now().toEpochMilli()
            return millis.convertEpochMillisZone(ZoneOffset.systemDefault(), ZoneOffset.UTC)
        }

    // In UTC
    fun setDate(millis: Long) {
        // Convert to local time
        val localMillis = millis.convertEpochMillisZone(ZoneOffset.UTC, ZoneOffset.systemDefault())
        viewModelScope.launchNonCancellable {
            if (start) {
                tracker.setRemoteStartDate(track.toDbTrack(), localMillis)
            } else {
                tracker.setRemoteFinishDate(track.toDbTrack(), localMillis)
            }
            // KMK --> fill the same date on other trackers that have none set yet,
            // so a manual edit isn't silently missing everywhere else
            if (mangaId != 0L) {
                try {
                    val allTracks = getTracks.await(mangaId)
                    for (other in allTracks) {
                        if (other.trackerId == tracker.id) continue
                        val otherTracker = trackerManager.get(other.trackerId) ?: continue
                        if (!otherTracker.isLoggedIn || !otherTracker.supportsReadingDates) continue
                        val otherDate = if (start) other.startDate else other.finishDate
                        if (otherDate > 0L) continue
                        try {
                            if (start) {
                                otherTracker.setRemoteStartDate(other.toDbTrack(), localMillis)
                            } else {
                                otherTracker.setRemoteFinishDate(other.toDbTrack(), localMillis)
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
            // KMK <--
        }
    }

    fun confirmRemoveDate(backStack: NavBackStack<NavKey>) {
        backStack.add(TrackDateRemoverRoute(track, serviceId, start))
    }
}

@Composable
fun TrackDateRemoverScreen(
    track: Track,
    serviceId: Long,
    start: Boolean,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<TrackDateRemoverViewModel, TrackDateRemoverViewModel.Factory> {
        create(track = track, start = start, serviceId = serviceId)
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
                text = stringResource(MR.strings.track_remove_date_conf_title),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            val serviceName = screenModel.getServiceName()
            Text(
                text = if (start) {
                    stringResource(MR.strings.track_remove_start_date_conf_text, serviceName)
                } else {
                    stringResource(MR.strings.track_remove_finish_date_conf_text, serviceName)
                },
            )
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
            ) {
                TextButton(onClick = backStack::removeLastOrNull) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                FilledTonalButton(
                    onClick = {
                        screenModel.removeDate()
                        backStack.popUntil { it is TrackInfoDialogHomeRoute }
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
class TrackDateRemoverViewModel(
    @Assisted private val track: Track,
    @Assisted private val start: Boolean,
    @Assisted private val serviceId: Long,
    trackerManager: TrackerManager,
) : ViewModel() {

    private val tracker: Tracker = trackerManager.get(serviceId)!!

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(track: Track, start: Boolean, serviceId: Long): TrackDateRemoverViewModel
    }

    fun getServiceName() = tracker.name

    fun removeDate() {
        viewModelScope.launchNonCancellable {
            if (start) {
                tracker.setRemoteStartDate(track.toDbTrack(), 0)
            } else {
                tracker.setRemoteFinishDate(track.toDbTrack(), 0)
            }
        }
    }
}

