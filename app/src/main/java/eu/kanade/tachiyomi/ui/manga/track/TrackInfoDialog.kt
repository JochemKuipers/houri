package eu.kanade.tachiyomi.ui.manga.track

import android.content.Context
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.track.TrackChapterSelector
import eu.kanade.presentation.track.TrackDateSelector
import eu.kanade.presentation.track.TrackInfoDialogHome
import eu.kanade.presentation.track.TrackScoreSelector
import eu.kanade.presentation.track.TrackStatusSelector
import eu.kanade.presentation.track.TrackerSearch
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import exh.metadata.metadata.base.TrackerIdMetadata
import exh.source.getMainSource
import exh.source.isMergedSourceId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.domain.SettingsDestination
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.popUntil
import mihon.core.viewmodel.StateViewModel
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackerProgressSync
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// KMK --> The track sheet hosts its own nested Nav3 stack: the main back stack pushes
// TrackInfoDialogRoute (an OverlaySheetScene destination in AppNavGraph), and everything the
// sheet itself navigates between lives in these local routes.
private val LocalTrackMainBackStack = androidx.compose.runtime.compositionLocalOf<NavBackStack<NavKey>> {
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
                onBack = { sheetBackStack.removeLastOrNull() },
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

@Composable
fun TrackInfoDialogHomeScreen(
    mangaId: Long,
    mangaTitle: String,
    sourceId: Long,
) {
    val backStack = LocalBackStack.current
    val mainBackStack = LocalTrackMainBackStack.current
    val isTabletUi = isTabletUi()
    val context = LocalContext.current
    val screenModel = assistedMetroViewModel<TrackInfoDialogHomeViewModel, TrackInfoDialogHomeViewModel.Factory> {
        create(mangaId = mangaId, sourceId = sourceId)
    }

    val dateFormat = remember { UiPreferences.dateFormat(globalAppGraph.uiPreferences.dateFormat().get()) }
    val state by screenModel.state.collectAsState()

    // SY -->
    Column(modifier = Modifier.animateContentSize()) {
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
                    .windowInsetsPadding(WindowInsets.systemBars),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(MR.strings.loading),
                    fontSize = 14.sp,
                )
            }
        }
        // SY <--
        else {
            TrackInfoDialogHome(
                trackItems = state.trackItems,
                // KMK -->
                seriesTitle = mangaTitle,
                // KMK <--
                dateFormat = dateFormat,
                onStatusClick = {
                    backStack.add(
                        TrackStatusSelectorRoute(
                            track = it.track!!,
                            serviceId = it.tracker.id,
                        ),
                    )
                },
                onChapterClick = { item ->
                    backStack.add(
                        TrackChapterSelectorRoute(
                            track = item.track!!,
                            serviceId = item.tracker.id,
                            mangaId = mangaId,
                        ),
                    )
                },
                onScoreClick = {
                    backStack.add(
                        TrackScoreSelectorRoute(
                            track = it.track!!,
                            serviceId = it.tracker.id,
                            mangaId = mangaId,
                        ),
                    )
                },
                onStartDateEdit = {
                    backStack.add(
                        TrackDateSelectorRoute(
                            track = it.track!!,
                            serviceId = it.tracker.id,
                            mangaId = mangaId,
                            start = true,
                        ),
                    )
                },
                onEndDateEdit = {
                    backStack.add(
                        TrackDateSelectorRoute(
                            track = it.track!!,
                            serviceId = it.tracker.id,
                            mangaId = mangaId,
                            start = false,
                        ),
                    )
                },
                onNewSearch = {
                    if (it.tracker is EnhancedTracker) {
                        screenModel.registerEnhancedTracking(it)
                    } else {
                        // SY -->
                        screenModel.newSearch(backStack, it, mangaTitle)
                        // SY <--
                    }
                },
                onOpenInBrowser = { openTrackerInBrowser(context, it) },
                onRemoved = {
                    backStack.add(
                        TrackerRemoveRoute(
                            mangaId = mangaId,
                            track = it.track!!,
                            serviceId = it.tracker.id,
                        ),
                    )
                },
                // KMK --> one confirmation for every selected tracker instead of
                // one TrackerRemoveScreen per service
                onRemoveAll = { items ->
                    backStack.add(
                        TrackerBulkRemoveRoute(
                            mangaId = mangaId,
                            tracks = items.mapNotNull { it.track },
                        ),
                    )
                },
                // KMK <--
                onCopyLink = { context.copyTrackerLink(it) },
                onTogglePrivate = screenModel::togglePrivate,
                // KMK -->
                onStepChapter = screenModel::stepChapter,
                onManageServices = {
                    mainBackStack.addSettingsRoute(SettingsRoute(SettingsDestination.Tracking), isTabletUi)
                },
                // KMK <--
                // KMK --> badge trackers whose last refresh failed (e.g. a 404 entry)
                errorTrackerIds = state.errorTrackerIds,
                // KMK <--
            )
        }
    }
}

/**
 * Opens registered tracker url in browser
 */
private fun openTrackerInBrowser(context: Context, trackItem: TrackItem) {
    val url = trackItem.track?.remoteUrl ?: return
    if (url.isNotBlank()) {
        context.openInBrowser(url)
    }
}

private fun Context.copyTrackerLink(trackItem: TrackItem) {
    val url = trackItem.track?.remoteUrl ?: return
    if (url.isNotBlank()) {
        copyToClipboard(url, url)
    }
}

@AssistedInject
class TrackInfoDialogHomeViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val sourceId: Long,
    private val getTracks: GetTracks,
    // SY -->
    private val trackerManager: TrackerManager,
    private val trackPreferences: TrackPreferences,
    // SY <--
    // KMK -->
    private val sourceManager: SourceManager,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val getMangaById: GetManga,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val refreshTracks: RefreshTracks,
    private val context: Context,
    // KMK <--
) : StateViewModel<TrackInfoDialogHomeViewModel.State>(State()) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, sourceId: Long): TrackInfoDialogHomeViewModel
    }

    init {
        // KMK --> Sequenced, not concurrent. These used to be two independent launches, so
        // the track flow emitted the stale local rows immediately while refreshTrackers()
        // was still talking to every tracker - the dialog rendered one set of information
        // and then swapped the whole thing for another a moment later. Refreshing first means
        // the first (and only) emission already carries the fetched values.
        // KMK <--
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true) }
            try {
                refreshTrackers()
            } finally {
                mutableState.update { it.copy(isLoading = false) }
            }

            getTracks.subscribe(mangaId)
                .catch { logcat(LogPriority.ERROR, it) }
                .distinctUntilChanged()
                .map { it.mapToTrackItem() }
                .collectLatest { trackItems -> mutableState.update { it.copy(trackItems = trackItems) } }
        }
    }

    // KMK -->
    private suspend fun getMangaForTracking(item: TrackItem): Manga? {
        if (!isMergedSourceId(sourceId)) {
            return getMangaById.await(mangaId)
        }
        item.tracker as EnhancedTracker
        val references = getMergedReferencesById.await(mangaId)
        return references.distinctBy { it.mangaSourceId }.firstNotNullOfOrNull { ref ->
            sourceManager.get(ref.mangaSourceId)
                ?.takeIf(item.tracker::accept)
                ?.let { ref.mangaId?.let { mangaId -> getMangaById.await(mangaId) } }
        }
    }
    // KMK <--

    fun registerEnhancedTracking(item: TrackItem) {
        item.tracker as EnhancedTracker
        viewModelScope.launchNonCancellable {
            val manga = getMangaForTracking(item) ?: return@launchNonCancellable
            try {
                val matchResult = item.tracker.match(manga) ?: throw Exception()
                item.tracker.register(matchResult, mangaId)
            } catch (_: Exception) {
                withUIContext { context.toast(MR.strings.error_no_match) }
            }
        }
    }

    // SY -->
    fun newSearch(backStack: NavBackStack<NavKey>, item: TrackItem, mangaTitle: String) {
        viewModelScope.launchNonCancellable {
            if (trackPreferences.resolveUsingSourceMetadata().get()) {
                // Check if the tracker id is contained in the metadata
                val result = getTrackerIdFromMetadata(item.tracker.id)
                if (result != null) {
                    mutableState.update { it.copy(isLoading = true) }

                    // Try to register tracking by id
                    val success = registerTrackingById(item.tracker.id, result)

                    mutableState.update { it.copy(isLoading = false) }

                    if (success) {
                        // Return on success
                        return@launchNonCancellable
                    }
                }
            }

            // Open search screen
            backStack.add(
                TrackerSearchRoute(
                    mangaId = mangaId,
                    initialQuery = item.track?.title ?: mangaTitle,
                    currentUrl = item.track?.remoteUrl,
                    serviceId = item.tracker.id,
                ),
            )
        }
    }

    suspend fun getTrackerIdFromMetadata(trackerId: Long): String? {
        try {
            val metadataSource = sourceManager.get(sourceId)
                ?.getMainSource<MetadataSource<*, *>>() ?: return null

            return getFlatMetadataById.await(mangaId)?.run {
                // Use 'raise' to dynamically obtain the specific metadata type and then attempt to cast
                raise(metadataSource.metaClass) as? TrackerIdMetadata
            }?.let { metadata ->
                when (trackerId) {
                    trackerManager.aniList.id -> metadata.anilistId
                    trackerManager.kitsu.id -> metadata.kitsuId
                    trackerManager.myAnimeList.id -> metadata.myAnimeListId
                    trackerManager.mangaUpdates.id -> metadata.mangaUpdatesId
                    else -> null
                }
                    // KMK --> never auto-bind placeholder IDs (blank/"0"/non-positive numerics)
                    .takeUnless { id ->
                        val v = id?.trim().orEmpty()
                        v.isBlank() || v == "0" || v.equals("null", ignoreCase = true) ||
                            (v.toLongOrNull()?.let { it <= 0L } == true)
                    }
                // KMK <--
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get tracker ID from metadata" }
            return null
        }
    }

    suspend fun registerTrackingById(trackerId: Long, remoteId: String): Boolean {
        // KMK --> drop placeholder IDs before touching the network
        val id = remoteId.trim()
        if (id.isBlank() || id == "0" || id.equals("null", ignoreCase = true) ||
            (id.toLongOrNull()?.let { it <= 0L } == true)
        ) {
            return false
        }
        // KMK <--
        trackerManager.get(trackerId)?.let { tracker ->
            try {
                tracker.searchById(id)?.let { track ->
                    tracker.register(track, mangaId)
                    return true
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to register tracking by id" }
            }
        }
        return false
    }
    // SY <--

    private suspend fun refreshTrackers() {
        val failures = refreshTracks.await(mangaId)
            .filter { it.first != null }
        failures.forEach { (track, e) ->
            logcat(LogPriority.ERROR, e) {
                "Failed to refresh track data mangaId=$mangaId for service ${track?.id}"
            }
            withUIContext {
                context.toast(
                    context.stringResource(
                        MR.strings.track_error,
                        track?.name ?: "Unknown",
                        e.message ?: "",
                    ),
                )
            }
        }
        // KMK --> remember which trackers failed so the dialog can badge them
        // instead of leaving e.g. a 404 MAL entry looking fine
        mutableState.update {
            it.copy(errorTrackerIds = failures.mapNotNull { (tracker, _) -> tracker?.id }.toSet())
        }
        // KMK <--
    }

    fun togglePrivate(item: TrackItem) {
        val track = item.track ?: return
        viewModelScope.launchNonCancellable {
            item.tracker.setRemotePrivate(track.toDbTrack(), !track.private)
        }
    }

    // KMK -->
    fun stepChapter(delta: Int) {
        viewModelScope.launchNonCancellable {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@launchNonCancellable
            val target = maxOf(0, TrackerProgressSync.maxProgress(tracks).toInt() + delta)
            val failed = tracks.mapNotNull { track ->
                val tracker = trackerManager.get(track.trackerId)
                if (tracker == null) {
                    track.trackerId.toString()
                } else {
                    try {
                        tracker.setRemoteLastChapterRead(track.toDbTrack(), target)
                        null
                    } catch (e: Throwable) {
                        logcat(LogPriority.ERROR, e) {
                            "Failed to set chapter $target on ${tracker.name}"
                        }
                        tracker.name
                    }
                }
            }
            if (failed.isNotEmpty()) {
                withUIContext {
                    context.toast(
                        context.stringResource(KMR.strings.track_step_failed, failed.joinToString()),
                    )
                }
            }
        }
    }
    // KMK <--

    private suspend fun List<Track>.mapToTrackItem(): List<TrackItem> {
        val loggedInTrackers = trackerManager.loggedInTrackers()
        val source = sourceManager.getOrStub(sourceId)
        return loggedInTrackers
            // Map to TrackItem
            .map { service -> TrackItem(find { it.trackerId == service.id }, service) }
            // Show only if the service supports this manga's source
            // KMK -->
            .let { trackers ->
                val sources = if (source is MergedSource) {
                    sourceManager.getMergedSources(mangaId)
                } else {
                    listOf(source)
                }
                trackers.filter { (it.tracker as? EnhancedTracker)?.accept(sources) ?: true }
            }
        // KMK <--
    }

    @Immutable
    data class State(
        val trackItems: List<TrackItem> = emptyList(),
        // SY -->
        val isLoading: Boolean = false,
        // SY <--
        // KMK --> tracker ids whose last refresh failed (e.g. a 404 remote entry)
        val errorTrackerIds: Set<Long> = emptySet(),
        // KMK <--
    )
}

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
