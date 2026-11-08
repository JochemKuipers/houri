package eu.kanade.tachiyomi.ui.manga.track

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.track.TrackInfoDialogHome
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import exh.metadata.metadata.base.TrackerIdMetadata
import exh.source.getMainSource
import exh.source.isMergedSourceId
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.domain.SettingsDestination
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackerProgressSync
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// TrackInfoDialogRoute (an OverlaySheetScene destination in AppNavGraph), and everything the
// sheet itself navigates between lives in these local routes.
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
