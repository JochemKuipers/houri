package eu.kanade.presentation.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.lang.toLocalDate
import mihon.presentation.core.util.isTabletUi
import tachiyomi.domain.track.service.TrackerProgressSync
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.format.DateTimeFormatter

@Composable
fun TrackInfoDialogHome(
    trackItems: List<TrackItem>,
    seriesTitle: String,
    dateFormat: DateTimeFormatter,
    onStatusClick: (TrackItem) -> Unit,
    onChapterClick: (TrackItem) -> Unit,
    onScoreClick: (TrackItem) -> Unit,
    onStartDateEdit: (TrackItem) -> Unit,
    onEndDateEdit: (TrackItem) -> Unit,
    onNewSearch: (TrackItem) -> Unit,
    onOpenInBrowser: (TrackItem) -> Unit,
    onRemoved: (TrackItem) -> Unit,
    onRemoveAll: (List<TrackItem>) -> Unit = {},
    onCopyLink: (TrackItem) -> Unit,
    onTogglePrivate: (TrackItem) -> Unit,
    // KMK -->
    onStepChapter: (Int) -> Unit = {},
    onManageServices: () -> Unit = {},
    // KMK <--
    errorTrackerIds: Set<Long> = emptySet(),
) {
    val isTablet = isTabletUi()
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isTablet) Alignment.Center else Alignment.TopStart,
    ) {
        Column(
            // KMK --> bounded + scrollable: heightIn must cap the scroll container directly.
            // No animateContentSize/wrapContentHeight here: they remeasure the scroll child
            // unbounded and defeat the max-height cap, letting the sheet grow full-length.
            modifier = Modifier
                .then(if (isTablet) Modifier.widthIn(max = 560.dp) else Modifier.fillMaxWidth())
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // KMK <--
        ) {
            // KMK -->
            val scoredTracks = trackItems
                .mapNotNull { item -> item.track?.let { item.tracker to it } }
                .filter { it.second.score != 0.0 }
            val trackedItems = remember(trackItems) { trackItems.filter { it.track != null } }
            val domainTracks = remember(trackItems) { trackItems.mapNotNull { it.track } }
            val mismatchIds = remember(domainTracks) {
                TrackerProgressSync.mismatchedIds(domainTracks)
            }
            val visibleErrorIds = remember(trackItems, errorTrackerIds) {
                trackedItems.map { it.tracker.id }.toSet() intersect errorTrackerIds
            }
            val legend = listOfNotNull(
                if (scoredTracks.size > 1) {
                    stringResource(
                        KMR.strings.track_normalized_score_summary,
                        scoredTracks.map { (tracker, track) -> tracker.get10PointScore(track) }.average().toFloat(),
                        scoredTracks.size,
                    )
                } else {
                    null
                },
                if (mismatchIds.isNotEmpty()) stringResource(KMR.strings.track_unsynced_hint) else null,
                if (visibleErrorIds.isNotEmpty()) stringResource(KMR.strings.track_sync_error_hint) else null,
            )
            if (legend.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    legend.forEach { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            // KMK <--
            // Unified card only when 2+ actually tracked entries; otherwise show per-tracker rows.
            // Fixes: untracked manga being shown as tracked, and empty space below card when only 1 tracked.
            // KMK --> pass ALL trackers (incl. untracked) so a 3rd+ service can still bind from the card.
            if (trackedItems.size > 1) {
                UnifiedTrackerCard(
                    trackItems = trackItems,
                    seriesTitle = seriesTitle,
                    dateFormat = dateFormat,
                    onStatusClick = onStatusClick,
                    onChapterClick = onChapterClick,
                    onScoreClick = onScoreClick,
                    onStartDateEdit = onStartDateEdit,
                    onEndDateEdit = onEndDateEdit,
                    onNewSearch = onNewSearch,
                    onRemoved = onRemoved,
                    onRemoveAll = onRemoveAll,
                    onOpenInBrowser = onOpenInBrowser,
                    onCopyLink = onCopyLink,
                    onTogglePrivate = onTogglePrivate,
                    onStepChapter = onStepChapter,
                    errorTrackerIds = errorTrackerIds,
                )
                // KMK <--
            } else {
                trackItems.forEach { item ->
                    if (item.track != null) {
                        val isMismatched = item.tracker.id in mismatchIds
                        val supportsScoring = item.tracker.getScoreList().isNotEmpty()
                        val supportsReadingDates = item.tracker.supportsReadingDates
                        val supportsPrivate = item.tracker.supportsPrivateTracking
                        TrackInfoItem(
                            title = item.track.title,
                            tracker = item.tracker,
                            status = item.tracker.getStatus(item.track.status),
                            onStatusClick = { onStatusClick(item) },
                            chapters = "${item.track.lastChapterRead.toInt()}".let {
                                val totalChapters = item.track.totalChapters
                                if (totalChapters > 0) {
                                    // Add known total chapter count
                                    "$it / $totalChapters"
                                } else {
                                    it
                                }
                            },
                            onChaptersClick = { onChapterClick(item) },
                            score = item.tracker.displayScore(item.track)
                                .takeIf { supportsScoring && item.track.score != 0.0 },
                            onScoreClick = { onScoreClick(item) }
                                .takeIf { supportsScoring },
                            startDate = remember(item.track.startDate) { dateFormat.format(item.track.startDate.toLocalDate()) }
                                .takeIf { supportsReadingDates && item.track.startDate != 0L },
                            onStartDateClick = { onStartDateEdit(item) }
                                .takeIf { supportsReadingDates },
                            endDate = dateFormat.format(item.track.finishDate.toLocalDate())
                                .takeIf { supportsReadingDates && item.track.finishDate != 0L },
                            onEndDateClick = { onEndDateEdit(item) }
                                .takeIf { supportsReadingDates },
                            onNewSearch = { onNewSearch(item) },
                            onOpenInBrowser = { onOpenInBrowser(item) },
                            onRemoved = { onRemoved(item) },
                            onCopyLink = { onCopyLink(item) },
                            private = item.track.private,
                            onTogglePrivate = { onTogglePrivate(item) }
                                .takeIf { supportsPrivate },
                            isMismatched = isMismatched,
                            hasError = item.tracker.id in errorTrackerIds,
                        )
                    }
                }
                // KMK --> collapse untracked trackers into one compact add-tracking section
                // instead of one full row per service, so N logged-in trackers cost ~2 rows.
                // KMK --> EnhancedTrackers are auto-discovered and never manually bound, so
                // offering them a tap target can only fail with a bare "no match" toast.
                val bindableUntracked = remember(trackItems) {
                    trackItems.filter { it.track == null && it.tracker !is EnhancedTracker }
                }
                if (bindableUntracked.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(MR.strings.add_tracking),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            bindableUntracked.forEach { item ->
                                TrackLogoIcon(
                                    tracker = item.tracker,
                                    onClick = { onNewSearch(item) },
                                )
                            }
                        }
                    }
                }
                if (trackItems.isNotEmpty()) {
                    TextButton(
                        onClick = onManageServices,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(KMR.strings.track_manage_services))
                    }
                }
                // KMK <--
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun TrackInfoDialogHomePreviews(
    @PreviewParameter(TrackInfoDialogHomePreviewProvider::class)
    content: @Composable () -> Unit,
) {
    TachiyomiPreviewTheme {
        Surface {
            content()
        }
    }
}
