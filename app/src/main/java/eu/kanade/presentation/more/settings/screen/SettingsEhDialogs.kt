package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class LanguageDialogState(preference: String) {
    class RowState(original: ColumnState, translated: ColumnState, rewrite: ColumnState) {
        var original by mutableStateOf(original)
        var translated by mutableStateOf(translated)
        var rewrite by mutableStateOf(rewrite)

        fun toPreference() = "${original.value}*${translated.value}*${rewrite.value}"
    }
    enum class ColumnState(val value: String) {
        Unavailable("false"),
        Enabled("true"),
        Disabled("false"),
    }
    private fun String.toRowState(disableFirst: Boolean = false) = split("*")
        .map {
            if (it.toBoolean()) {
                ColumnState.Enabled
            } else {
                ColumnState.Disabled
            }
        }
        .let {
            if (disableFirst) {
                RowState(ColumnState.Unavailable, it[1], it[2])
            } else {
                RowState(it[0], it[1], it[2])
            }
        }

    val japanese: RowState
    val english: RowState
    val chinese: RowState
    val dutch: RowState
    val french: RowState
    val german: RowState
    val hungarian: RowState
    val italian: RowState
    val korean: RowState
    val polish: RowState
    val portuguese: RowState
    val russian: RowState
    val spanish: RowState
    val thai: RowState
    val vietnamese: RowState
    val notAvailable: RowState
    val other: RowState

    init {
        val settingsLanguages = preference.split("\n")
        japanese = settingsLanguages[0].toRowState(true)
        english = settingsLanguages[1].toRowState()
        chinese = settingsLanguages[2].toRowState()
        dutch = settingsLanguages[3].toRowState()
        french = settingsLanguages[4].toRowState()
        german = settingsLanguages[5].toRowState()
        hungarian = settingsLanguages[6].toRowState()
        italian = settingsLanguages[7].toRowState()
        korean = settingsLanguages[8].toRowState()
        polish = settingsLanguages[9].toRowState()
        portuguese = settingsLanguages[10].toRowState()
        russian = settingsLanguages[11].toRowState()
        spanish = settingsLanguages[12].toRowState()
        thai = settingsLanguages[13].toRowState()
        vietnamese = settingsLanguages[14].toRowState()
        notAvailable = settingsLanguages[15].toRowState()
        other = settingsLanguages[16].toRowState()
    }

    fun toPreference() = listOf(
        japanese,
        english,
        chinese,
        dutch,
        french,
        german,
        hungarian,
        italian,
        korean,
        polish,
        portuguese,
        russian,
        spanish,
        thai,
        vietnamese,
        notAvailable,
        other,
    ).joinToString("\n") { it.toPreference() }
}

@Composable
internal fun LanguageDialogRowCheckbox(
    columnState: LanguageDialogState.ColumnState,
    onStateChange: (LanguageDialogState.ColumnState) -> Unit,
) {
    if (columnState != LanguageDialogState.ColumnState.Unavailable) {
        Checkbox(
            checked = columnState == LanguageDialogState.ColumnState.Enabled,
            onCheckedChange = {
                if (it) {
                    onStateChange(LanguageDialogState.ColumnState.Enabled)
                } else {
                    onStateChange(LanguageDialogState.ColumnState.Disabled)
                }
            },
        )
    } else {
        Box(modifier = Modifier.size(48.dp))
    }
}

@Composable
internal fun LanguageDialogRow(
    language: String,
    row: LanguageDialogState.RowState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language,
            modifier = Modifier
                .padding(4.dp)
                .width(80.dp),
            maxLines = 1,
        )
        LanguageDialogRowCheckbox(row.original, onStateChange = { row.original = it })
        LanguageDialogRowCheckbox(row.translated, onStateChange = { row.translated = it })
        LanguageDialogRowCheckbox(row.rewrite, onStateChange = { row.rewrite = it })
    }
}

@Composable
internal fun LanguagesDialog(
    onDismissRequest: () -> Unit,
    initialValue: String,
    onValueChange: (String) -> Unit,
) {
    val state = remember(initialValue) { LanguageDialogState(initialValue) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(SYMR.strings.language_filtering)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(SYMR.strings.language_filtering_summary))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Language", modifier = Modifier.padding(4.dp))
                    Text(text = "Original", modifier = Modifier.padding(4.dp))
                    Text(text = "Translated", modifier = Modifier.padding(4.dp))
                    Text(text = "Rewrite", modifier = Modifier.padding(4.dp))
                }
                LanguageDialogRow(language = "Japanese", row = state.japanese)
                LanguageDialogRow(language = "English", row = state.english)
                LanguageDialogRow(language = "Chinese", row = state.chinese)
                LanguageDialogRow(language = "Dutch", row = state.dutch)
                LanguageDialogRow(language = "French", row = state.french)
                LanguageDialogRow(language = "German", row = state.german)
                LanguageDialogRow(language = "Hungarian", row = state.hungarian)
                LanguageDialogRow(language = "Italian", row = state.italian)
                LanguageDialogRow(language = "Korean", row = state.korean)
                LanguageDialogRow(language = "Polish", row = state.polish)
                LanguageDialogRow(language = "Portuguese", row = state.portuguese)
                LanguageDialogRow(language = "Russian", row = state.russian)
                LanguageDialogRow(language = "Spanish", row = state.spanish)
                LanguageDialogRow(language = "Thai", row = state.thai)
                LanguageDialogRow(language = "Vietnamese", row = state.vietnamese)
                LanguageDialogRow(language = "N/A", row = state.notAvailable)
                LanguageDialogRow(language = "Other", row = state.other)
            }
        },
        confirmButton = {
            TextButton(onClick = { onValueChange(state.toPreference()) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
internal fun FrontPageCategoriesDialogRow(
    title: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onValueChange(!value) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title)
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
internal fun FrontPageCategoriesDialog(
    onDismissRequest: () -> Unit,
    initialValue: String,
    onValueChange: (String) -> Unit,
) {
    val state = remember(initialValue) { FrontPageCategoriesDialogState(initialValue) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(SYMR.strings.frong_page_categories)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(SYMR.strings.fromt_page_categories_summary))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Category", modifier = Modifier.padding(4.dp))
                    Text(text = "Enabled", modifier = Modifier.padding(4.dp))
                }
                FrontPageCategoriesDialogRow(
                    title = "Doujinshi",
                    value = state.doujinshi,
                    onValueChange = { state.doujinshi = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Manga",
                    value = state.manga,
                    onValueChange = { state.manga = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Artist CG",
                    value = state.artistCg,
                    onValueChange = { state.artistCg = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Game CG",
                    value = state.gameCg,
                    onValueChange = { state.gameCg = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Western",
                    value = state.western,
                    onValueChange = { state.western = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Non-H",
                    value = state.nonH,
                    onValueChange = { state.nonH = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Image Set",
                    value = state.imageSet,
                    onValueChange = { state.imageSet = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Cosplay",
                    value = state.cosplay,
                    onValueChange = { state.cosplay = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Asian Porn",
                    value = state.asianPorn,
                    onValueChange = { state.asianPorn = it },
                )
                FrontPageCategoriesDialogRow(
                    title = "Misc",
                    value = state.misc,
                    onValueChange = { state.misc = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onValueChange(state.toPreference()) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
internal fun SyncResetDialog(
    onDismissRequest: () -> Unit,
    onStartReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(SYMR.strings.favorites_sync_reset))
        },
        text = {
            Text(text = stringResource(SYMR.strings.favorites_sync_reset_message))
        },
        confirmButton = {
            TextButton(onClick = onStartReset) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

@Composable
internal fun UpdaterStatisticsLoadingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(SYMR.strings.gallery_updater_statistics_collection),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(40.dp),
            )
        }
    }
}

@Composable
internal fun UpdaterStatisticsDialog(
    onDismissRequest: () -> Unit,
    updateInfo: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(SYMR.strings.gallery_updater_statistics))
        },
        text = {
            Text(text = updateInfo)
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

internal fun getRelativeTimeFromNow(then: Duration): RelativeTime {
    val now = System.currentTimeMillis().milliseconds
    var period: Duration = now - then
    val relativeTime = RelativeTime()
    while (period > 0.milliseconds) {
        when {
            period >= 365.days -> {
                (period.inWholeDays / 365).let {
                    relativeTime.years = it
                    period -= (it * 365).days
                }
                continue
            }
            period >= 30.days -> {
                (period.inWholeDays / 30).let {
                    relativeTime.months = it
                    period -= (it * 30).days
                }
            }
            period >= 7.days -> {
                (period.inWholeDays / 7).let {
                    relativeTime.weeks = it
                    period -= (it * 7).days
                }
            }
            period >= 1.days -> {
                period.inWholeDays.let {
                    relativeTime.days = it
                    period -= it.days
                }
            }
            period >= 1.hours -> {
                period.inWholeHours.let {
                    relativeTime.hours = it
                    period -= it.hours
                }
            }
            period >= 1.minutes -> {
                period.inWholeMinutes.let {
                    relativeTime.minutes = it
                    period -= it.minutes
                }
            }
            period >= 1.seconds -> {
                period.inWholeSeconds.let {
                    relativeTime.seconds = it
                    period -= it.seconds
                }
            }
            period >= 1.milliseconds -> {
                period.inWholeMilliseconds.let {
                    relativeTime.milliseconds = it
                }
                period = Duration.ZERO
            }
        }
    }
    return relativeTime
}

internal fun getRelativeTimeString(relativeTime: RelativeTime, context: Context): String {
    return relativeTime.years?.let { context.pluralStringResource(SYMR.plurals.humanize_year, it.toInt(), it) }
        ?: relativeTime.months?.let {
            context.pluralStringResource(SYMR.plurals.humanize_month, it.toInt(), it)
        }
        ?: relativeTime.weeks?.let { context.pluralStringResource(SYMR.plurals.humanize_week, it.toInt(), it) }
        ?: relativeTime.days?.let { context.pluralStringResource(SYMR.plurals.humanize_day, it.toInt(), it) }
        ?: relativeTime.hours?.let { context.pluralStringResource(SYMR.plurals.humanize_hour, it.toInt(), it) }
        ?: relativeTime.minutes?.let {
            context.pluralStringResource(SYMR.plurals.humanize_minute, it.toInt(), it)
        }
        ?: relativeTime.seconds?.let {
            context.pluralStringResource(SYMR.plurals.humanize_second, it.toInt(), it)
        }
        ?: context.stringResource(SYMR.strings.humanize_fallback)
}

internal data class RelativeTime(
    var years: Long? = null,
    var months: Long? = null,
    var weeks: Long? = null,
    var days: Long? = null,
    var hours: Long? = null,
    var minutes: Long? = null,
    var seconds: Long? = null,
    var milliseconds: Long? = null,
)

