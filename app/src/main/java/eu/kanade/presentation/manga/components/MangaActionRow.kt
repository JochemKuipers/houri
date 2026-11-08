package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassDisabled
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.source.model.SManga
import mihon.app.di.globalAppGraph
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun MangaActionRow(
    favorite: Boolean,
    trackingCount: Int,
    nextUpdate: Instant?,
    isUserIntervalMode: Boolean,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onTrackingClicked: () -> Unit,
    onEditIntervalClicked: (() -> Unit)?,
    onEditCategory: (() -> Unit)?,
    // SY -->
    onMergeClicked: (() -> Unit)?,
    // SY <--
    // KMK -->
    status: Long,
    interval: Int,
    rereadCount: Int,
    isRereading: Boolean,
    onRereadClicked: (() -> Unit)?,
    // KMK --> Only non-null when the entry is an `.epub` in a local library, i.e. when the question
    // has an answer. A comic from an extension gets no such button, because there is nothing to
    // decide for it.
    onToggleEntryTypeClicked: (() -> Unit)?,
    // KMK <--
    modifier: Modifier = Modifier,
) {
    // KMK -->
    val libraryPreferences: LibraryPreferences = globalAppGraph.libraryPreferences
    val restrictions = libraryPreferences.autoUpdateMangaRestrictions().get()
    val notSkipCompleted = MANGA_NON_COMPLETED !in restrictions || status != SManga.COMPLETED.toLong()
    val selectedInterval by remember(interval) { mutableIntStateOf(if (interval < 0) -interval else 0) }
    // KMK <--
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

    val nextUpdateDays = remember(nextUpdate, selectedInterval, notSkipCompleted) {
        return@remember if (nextUpdate != null &&
            // KMK -->
            notSkipCompleted
            // KMK <--
        ) {
            val now = Instant.now()
            now.until(nextUpdate, ChronoUnit.DAYS).toInt().coerceAtLeast(0)
                // KMK -->
                .takeIf { selectedInterval != FetchInterval.MANUAL_DISABLE }
                ?: FetchInterval.MANUAL_DISABLE
            // KMK <--
        } else {
            null
        }
    }

    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        MangaActionButton(
            title = if (favorite) {
                stringResource(MR.strings.in_library)
            } else {
                stringResource(MR.strings.add_to_library)
            },
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategory,
        )
        MangaActionButton(
            title = when (nextUpdateDays) {
                null -> stringResource(MR.strings.not_applicable)
                0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                // KMK -->
                FetchInterval.MANUAL_DISABLE -> stringResource(MR.strings.disabled)
                // Custom cadence: surface the user-set frequency instead of its countdown
                else -> if (isUserIntervalMode && selectedInterval in 1..FetchInterval.MAX_INTERVAL) {
                    stringResource(
                        KMR.strings.manga_interval_every,
                        pluralStringResource(MR.plurals.day, count = selectedInterval, selectedInterval),
                    )
                } else {
                    pluralStringResource(
                        MR.plurals.day,
                        count = nextUpdateDays,
                        nextUpdateDays,
                    )
                }
                // KMK <--
            },
            icon = Icons.Default.HourglassEmpty
                // KMK -->
                .takeIf { nextUpdateDays != FetchInterval.MANUAL_DISABLE }
                ?: Icons.Default.HourglassDisabled,
            // KMK <--
            color = if (isUserIntervalMode ||
                // KMK -->
                nextUpdateDays?.let { it <= 1 } == true
                // KMK <--
            ) {
                MaterialTheme.colorScheme.primary
            } else {
                defaultActionButtonColor
            },
            onClick = { onEditIntervalClicked?.invoke() },
        )
        MangaActionButton(
            title = if (trackingCount == 0) {
                stringResource(MR.strings.manga_tracking_tab)
            } else {
                pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
            },
            icon = if (trackingCount == 0) Icons.Outlined.Sync else Icons.Outlined.Done,
            color = if (trackingCount == 0) defaultActionButtonColor else MaterialTheme.colorScheme.primary,
            onClick = onTrackingClicked,
        )
        // KMK --> Render this as a comic instead. Offered *before* the reader has been asked which it is:
        // detection is a heuristic and art-book imports are exactly the case it is likeliest to get
        // wrong, so the override has to be reachable without first opening the book and seeing the
        // wrong thing.
        if (onToggleEntryTypeClicked != null) {
            MangaActionButton(
                title = stringResource(KMR.strings.entry_type_render_as_comic),
                icon = Icons.Outlined.MenuBook,
                color = defaultActionButtonColor,
                onClick = onToggleEntryTypeClicked,
            )
        }
        // KMK <--
        if (onRereadClicked != null && (isRereading || rereadCount > 0)) {
            MangaActionButton(
                title = if (isRereading) {
                    stringResource(KMR.strings.rereading_label)
                } else {
                    stringResource(KMR.strings.manga_reread_count_button, rereadCount)
                },
                icon = Icons.Outlined.Refresh,
                color = if (isRereading) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
                onClick = onRereadClicked,
            )
        }
        if (onWebViewClicked != null) {
            MangaActionButton(
                title = stringResource(MR.strings.action_web_view),
                icon = Icons.Outlined.Public,
                color = MaterialTheme.colorScheme.primary, // KMK: defaultActionButtonColor
                onClick = onWebViewClicked,
                onLongClick = onWebViewLongClicked,
            )
        }
        // SY -->
        if (onMergeClicked != null) {
            MangaActionButton(
                title = stringResource(SYMR.strings.merge),
                icon = Icons.AutoMirrored.Outlined.CallMerge,
                color = MaterialTheme.colorScheme.primary, // KMK: defaultActionButtonColor
                onClick = onMergeClicked,
            )
        }
        // SY <--
    }
}

@Composable
private fun RowScope.MangaActionButton(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        onLongClick = onLongClick,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                color = color,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
