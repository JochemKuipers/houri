package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.library.components.SyncFavoritesWarningDialog
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceDependency
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.toast
import exh.eh.EHentaiUpdateWorker
import exh.eh.EHentaiUpdateWorkerConstants
import exh.eh.EHentaiUpdaterStats
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.EH_PACKAGE
import exh.source.ExhPreferences
import exh.ui.login.EhLoginActivity
import exh.util.nullIfBlank
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import tachiyomi.core.common.preference.Preference as DataPreference

@Serializable
data object SettingsEhRoute : NavKey, SearchableRoute {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = SYMR.strings.pref_category_eh

    override fun isEnabled(): Boolean = globalAppGraph.exhPreferences.isHentaiEnabled().get()

    @Composable
    fun Reconfigure(
        exhPreferences: ExhPreferences,
        openWarnConfigureDialogController: () -> Unit,
    ) {
        var initialLoadGuard by remember { mutableStateOf(false) }
        val useHentaiAtHome by exhPreferences.useHentaiAtHome().collectAsState()
        val useJapaneseTitle by exhPreferences.useJapaneseTitle().collectAsState()
        val useOriginalImages by exhPreferences.exhUseOriginalImages().collectAsState()
        val ehTagFilterValue by exhPreferences.ehTagFilterValue().collectAsState()
        val ehTagWatchingValue by exhPreferences.ehTagWatchingValue().collectAsState()
        val settingsLanguages by exhPreferences.exhSettingsLanguages().collectAsState()
        val enabledCategories by exhPreferences.exhEnabledCategories().collectAsState()
        val imageQuality by exhPreferences.imageQuality().collectAsState()
        DisposableEffect(
            useHentaiAtHome,
            useJapaneseTitle,
            useOriginalImages,
            ehTagFilterValue,
            ehTagWatchingValue,
            settingsLanguages,
            enabledCategories,
            imageQuality,
        ) {
            if (initialLoadGuard) {
                openWarnConfigureDialogController()
            }
            initialLoadGuard = true
            onDispose {}
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val exhPreferences: ExhPreferences = remember { globalAppGraph.exhPreferences }
        val sourcePreferences: SourcePreferences = remember { globalAppGraph.sourcePreferences }
        val getFlatMetadataById: GetFlatMetadataById = remember { globalAppGraph.getFlatMetadataById }
        val deleteFavoriteEntries: DeleteFavoriteEntries = remember { globalAppGraph.deleteFavoriteEntries }
        val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata = remember { globalAppGraph.getExhFavoriteMangaWithMetadata }
        var runConfigureDialog by remember { mutableStateOf(false) }
        val openWarnConfigureDialogController = { runConfigureDialog = true }

        Reconfigure(exhPreferences, openWarnConfigureDialogController)

        ConfigureExhDialog(run = runConfigureDialog, onRunning = { runConfigureDialog = false })

        return listOf(
            // KMK -->
            Preference.PreferenceGroup(
                stringResource(MR.strings.source_settings),
                preferenceItems = persistentListOf(
                    incognitoMode(sourcePreferences),
                ),
            ),
            // KMK <--
            Preference.PreferenceGroup(
                stringResource(SYMR.strings.ehentai_prefs_account_settings),
                preferenceItems = persistentListOf(
                    getLoginPreference(exhPreferences, openWarnConfigureDialogController),
                    useHentaiAtHome(exhPreferences),
                    useJapaneseTitle(exhPreferences),
                    useOriginalImages(exhPreferences),
                    watchedTags(exhPreferences),
                    tagFilterThreshold(exhPreferences),
                    tagWatchingThreshold(exhPreferences),
                    settingsLanguages(exhPreferences),
                    enabledCategories(exhPreferences),
                    watchedListDefaultState(exhPreferences),
                    imageQuality(exhPreferences),
                    enhancedEhentaiView(exhPreferences),
                ),
            ),
            Preference.PreferenceGroup(
                stringResource(SYMR.strings.favorites_sync),
                preferenceItems = persistentListOf(
                    readOnlySync(exhPreferences),
                    syncFavoriteNotes(),
                    lenientSync(exhPreferences),
                    forceSyncReset(deleteFavoriteEntries),
                ),
            ),
            Preference.PreferenceGroup(
                stringResource(SYMR.strings.gallery_update_checker),
                preferenceItems = persistentListOf(
                    updateCheckerFrequency(exhPreferences),
                    autoUpdateRequirements(exhPreferences),
                    updaterStatistics(
                        exhPreferences,
                        getExhFavoriteMangaWithMetadata,
                        getFlatMetadataById,
                    ),
                ),
            ),
        )
    }

    // KMK -->
    @Composable
    fun incognitoMode(
        sourcePreferences: SourcePreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        // EH-scoped incognito is membership of EH_PACKAGE in the shared
        // incognito-extensions set, so the row always reflects behavior.
        val ehIncognito = remember(sourcePreferences) {
            object : DataPreference<Boolean> {
                override fun key(): String = sourcePreferences.incognitoExtensions().key()
                override fun get(): Boolean = EH_PACKAGE in sourcePreferences.incognitoExtensions().get()
                override fun set(value: Boolean) {
                    sourcePreferences.incognitoExtensions().getAndSet { current ->
                        if (value) current + EH_PACKAGE else current - EH_PACKAGE
                    }
                }
                override fun isSet(): Boolean = sourcePreferences.incognitoExtensions().isSet()
                override fun delete() = set(false)
                override fun defaultValue(): Boolean = false
                override fun changes(): Flow<Boolean> =
                    sourcePreferences.incognitoExtensions().changes().map { EH_PACKAGE in it }
                override fun stateIn(scope: CoroutineScope): StateFlow<Boolean> =
                    changes().stateIn(scope, SharingStarted.Eagerly, get())
            }
        }
        return Preference.PreferenceItem.SwitchPreference(
            preference = ehIncognito,
            title = stringResource(MR.strings.pref_incognito_mode),
            subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
        )
    }
    // KMK <--

    @Composable
    fun getLoginPreference(
        exhPreferences: ExhPreferences,
        openWarnConfigureDialogController: () -> Unit,
    ): Preference.PreferenceItem.SwitchPreference {
        val activityResultContract =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    // Upload settings
                    openWarnConfigureDialogController()
                }
            }
        val context = LocalContext.current
        val value by exhPreferences.enableExhentai().collectAsState()
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.enableExhentai(),
            title = stringResource(SYMR.strings.enable_exhentai),
            subtitle = if (!value) {
                stringResource(SYMR.strings.requires_login)
            } else {
                null
            },
            onValueChanged = { newVal ->
                if (!newVal) {
                    exhPreferences.enableExhentai().set(false)
                    true
                } else {
                    activityResultContract.launch(EhLoginActivity.newIntent(context))
                    false
                }
            },
        )
    }

    @Composable
    fun useHentaiAtHome(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.ListPreference<Int> {
        return Preference.PreferenceItem.ListPreference(
            preference = exhPreferences.useHentaiAtHome(),
            entries = persistentMapOf(
                0 to stringResource(SYMR.strings.use_hentai_at_home_option_1),
                1 to stringResource(SYMR.strings.use_hentai_at_home_option_2),
            ),
            title = stringResource(SYMR.strings.use_hentai_at_home),
            subtitle = stringResource(SYMR.strings.use_hentai_at_home_summary),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
        )
    }

    @Composable
    fun useJapaneseTitle(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        val value by exhPreferences.useJapaneseTitle().collectAsState()
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.useJapaneseTitle(),
            title = stringResource(SYMR.strings.show_japanese_titles),
            subtitle = if (value) {
                stringResource(SYMR.strings.show_japanese_titles_option_1)
            } else {
                stringResource(SYMR.strings.show_japanese_titles_option_2)
            },
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
        )
    }

    @Composable
    fun useOriginalImages(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        val value by exhPreferences.exhUseOriginalImages().collectAsState()
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.exhUseOriginalImages(),
            title = stringResource(SYMR.strings.use_original_images),
            subtitle = if (value) {
                stringResource(SYMR.strings.use_original_images_on)
            } else {
                stringResource(SYMR.strings.use_original_images_off)
            },
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
        )
    }

    @Composable
    fun watchedTags(exhPreferences: ExhPreferences): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.watched_tags),
            subtitle = stringResource(SYMR.strings.watched_tags_summary),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
            onClick = {
                context.startActivity(
                    WebViewActivity.newIntent(
                        context,
                        url = "https://exhentai.org/mytags",
                        title = context.stringResource(SYMR.strings.watched_tags_exh),
                    ),
                )
            },
        )
    }

    @Composable
    fun TagThresholdDialog(
        onDismissRequest: () -> Unit,
        title: String,
        initialValue: Int,
        valueRange: IntRange,
        outsideRangeError: String,
        onValueChange: (Int) -> Unit,
    ) {
        var value by remember(initialValue) {
            mutableStateOf(initialValue.toString())
        }
        val isValid = remember(value) { value.toIntOrNull().let { it != null && it in valueRange } }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(
                    onClick = { onValueChange(value.toIntOrNull() ?: return@TextButton) },
                    enabled = isValid,
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
            title = {
                Text(text = title)
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        maxLines = 1,
                        singleLine = true,
                        isError = !isValid,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = if (!isValid) {
                            { Icon(Icons.Outlined.Error, outsideRangeError) }
                        } else {
                            null
                        },
                    )
                    if (!isValid) {
                        Text(
                            text = outsideRangeError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            },
        )
    }

    @Composable
    fun tagFilterThreshold(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val value by exhPreferences.ehTagFilterValue().collectAsState()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            TagThresholdDialog(
                onDismissRequest = { dialogOpen = false },
                title = stringResource(SYMR.strings.tag_filtering_threshold),
                initialValue = value,
                valueRange = -9999..0,
                outsideRangeError = stringResource(SYMR.strings.tag_filtering_threshhold_error),
                onValueChange = {
                    dialogOpen = false
                    exhPreferences.ehTagFilterValue().set(it)
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.tag_filtering_threshold),
            subtitle = stringResource(SYMR.strings.tag_filtering_threshhold_summary, value),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    fun tagWatchingThreshold(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val value by exhPreferences.ehTagWatchingValue().collectAsState()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            TagThresholdDialog(
                onDismissRequest = { dialogOpen = false },
                title = stringResource(SYMR.strings.tag_watching_threshhold),
                initialValue = value,
                valueRange = 0..9999,
                outsideRangeError = stringResource(SYMR.strings.tag_watching_threshhold_error),
                onValueChange = {
                    dialogOpen = false
                    exhPreferences.ehTagWatchingValue().set(it)
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.tag_watching_threshhold),
            subtitle = stringResource(SYMR.strings.tag_watching_threshhold_summary, value),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    fun settingsLanguages(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val value by exhPreferences.exhSettingsLanguages().collectAsState()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            LanguagesDialog(
                onDismissRequest = { dialogOpen = false },
                initialValue = value,
                onValueChange = {
                    dialogOpen = false
                    exhPreferences.exhSettingsLanguages().set(it)
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.language_filtering),
            subtitle = stringResource(SYMR.strings.language_filtering_summary),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    fun enabledCategories(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val value by exhPreferences.exhEnabledCategories().collectAsState()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            FrontPageCategoriesDialog(
                onDismissRequest = { dialogOpen = false },
                initialValue = value,
                onValueChange = {
                    dialogOpen = false
                    exhPreferences.exhEnabledCategories().set(it)
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.frong_page_categories),
            subtitle = stringResource(SYMR.strings.fromt_page_categories_summary),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    fun watchedListDefaultState(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.exhWatchedListDefaultState(),
            title = stringResource(SYMR.strings.watched_list_default),
            subtitle = stringResource(SYMR.strings.watched_list_state_summary),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
        )
    }

    @Composable
    fun imageQuality(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.ListPreference<String> {
        return Preference.PreferenceItem.ListPreference(
            preference = exhPreferences.imageQuality(),
            entries = persistentMapOf(
                "auto" to stringResource(SYMR.strings.eh_image_quality_auto),
                "ovrs_2400" to stringResource(SYMR.strings.eh_image_quality_2400),
                "ovrs_1600" to stringResource(SYMR.strings.eh_image_quality_1600),
                "high" to stringResource(SYMR.strings.eh_image_quality_1280),
                "med" to stringResource(SYMR.strings.eh_image_quality_980),
                "low" to stringResource(SYMR.strings.eh_image_quality_780),
            ),
            title = stringResource(SYMR.strings.eh_image_quality_summary),
            subtitle = stringResource(SYMR.strings.eh_image_quality),
            dependsOn = PreferenceDependency(exhPreferences.enableExhentai()),
        )
    }

    @Composable
    fun enhancedEhentaiView(exhPreferences: ExhPreferences): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.enhancedEHentaiView(),
            title = stringResource(SYMR.strings.pref_enhanced_e_hentai_view),
            subtitle = stringResource(SYMR.strings.pref_enhanced_e_hentai_view_summary),
        )
    }

    @Composable
    fun readOnlySync(exhPreferences: ExhPreferences): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.exhReadOnlySync(),
            title = stringResource(SYMR.strings.disable_favorites_uploading),
            subtitle = stringResource(SYMR.strings.disable_favorites_uploading_summary),
        )
    }

    @Composable
    fun syncFavoriteNotes(): Preference.PreferenceItem.TextPreference {
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            SyncFavoritesWarningDialog(
                onDismissRequest = { dialogOpen = false },
                onAccept = { dialogOpen = false },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.show_favorite_sync_notes),
            subtitle = stringResource(SYMR.strings.show_favorite_sync_notes_summary),
            onClick = { dialogOpen = true },
        )
    }

    @Composable
    fun lenientSync(exhPreferences: ExhPreferences): Preference.PreferenceItem.SwitchPreference {
        return Preference.PreferenceItem.SwitchPreference(
            preference = exhPreferences.exhLenientSync(),
            title = stringResource(SYMR.strings.ignore_sync_errors),
            subtitle = stringResource(SYMR.strings.ignore_sync_errors_summary),
        )
    }

    @Composable
    fun forceSyncReset(deleteFavoriteEntries: DeleteFavoriteEntries): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            SyncResetDialog(
                onDismissRequest = { dialogOpen = false },
                onStartReset = {
                    dialogOpen = false
                    scope.launchNonCancellable {
                        try {
                            deleteFavoriteEntries.await()
                            withUIContext {
                                context.toast(context.stringResource(SYMR.strings.sync_state_reset), Toast.LENGTH_LONG)
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e)
                        }
                    }
                },
            )
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.force_sync_state_reset),
            subtitle = stringResource(SYMR.strings.force_sync_state_reset_summary),
            onClick = {
                dialogOpen = true
            },
        )
    }

    @Composable
    fun updateCheckerFrequency(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.ListPreference<Int> {
        val value by exhPreferences.exhAutoUpdateFrequency().collectAsState()
        val context = LocalContext.current
        return Preference.PreferenceItem.ListPreference(
            preference = exhPreferences.exhAutoUpdateFrequency(),
            entries = persistentMapOf(
                0 to stringResource(SYMR.strings.time_between_batches_never),
                1 to stringResource(SYMR.strings.time_between_batches_1_hour),
                2 to stringResource(SYMR.strings.time_between_batches_2_hours),
                3 to stringResource(SYMR.strings.time_between_batches_3_hours),
                6 to stringResource(SYMR.strings.time_between_batches_6_hours),
                12 to stringResource(SYMR.strings.time_between_batches_12_hours),
                24 to stringResource(SYMR.strings.time_between_batches_24_hours),
                48 to stringResource(SYMR.strings.time_between_batches_48_hours),
            ),
            title = stringResource(SYMR.strings.time_between_batches),
            subtitle = if (value == 0) {
                stringResource(SYMR.strings.time_between_batches_summary_1, stringResource(MR.strings.app_name))
            } else {
                stringResource(
                    SYMR.strings.time_between_batches_summary_2,
                    stringResource(MR.strings.app_name),
                    value,
                    EHentaiUpdateWorkerConstants.UPDATES_PER_ITERATION,
                )
            },
            onValueChanged = { interval ->
                EHentaiUpdateWorker.scheduleBackground(context, prefInterval = interval)
                true
            },
        )
    }

    @Composable
    fun autoUpdateRequirements(
        exhPreferences: ExhPreferences,
    ): Preference.PreferenceItem.MultiSelectListPreference<String> {
        val value by exhPreferences.exhAutoUpdateRequirements().collectAsState()
        val context = LocalContext.current
        return Preference.PreferenceItem.MultiSelectListPreference(
            preference = exhPreferences.exhAutoUpdateRequirements(),
            entries = persistentMapOf(
                DEVICE_ONLY_ON_WIFI to stringResource(MR.strings.connected_to_wifi),
                DEVICE_CHARGING to stringResource(MR.strings.charging),
            ),
            title = stringResource(SYMR.strings.auto_update_restrictions),
            subtitle = remember(value) {
                context.stringResource(
                    MR.strings.restrictions,
                    value.sorted()
                        .map {
                            when (it) {
                                DEVICE_ONLY_ON_WIFI -> context.stringResource(MR.strings.connected_to_wifi)
                                DEVICE_CHARGING -> context.stringResource(MR.strings.charging)
                                else -> it
                            }
                        }
                        .ifEmpty {
                            listOf(context.stringResource(MR.strings.none))
                        }
                        .joinToString(),
                )
            },
            onValueChanged = { restrictions ->
                EHentaiUpdateWorker.scheduleBackground(context, prefRestrictions = restrictions)
                true
            },
        )
    }

    @Composable
    fun updaterStatistics(
        exhPreferences: ExhPreferences,
        getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata,
        getFlatMetadataById: GetFlatMetadataById,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        var dialogOpen by remember { mutableStateOf(false) }
        if (dialogOpen) {
            val updateInfo by produceState<String?>(null) {
                value = withIOContext {
                    try {
                        val stats =
                            exhPreferences.exhAutoUpdateStats().get().nullIfBlank()?.let {
                                Json.decodeFromString<EHentaiUpdaterStats>(it)
                            }

                        val statsText = if (stats != null) {
                            context.stringResource(
                                SYMR.strings.gallery_updater_stats_text,
                                getRelativeTimeString(getRelativeTimeFromNow(stats.startTime.milliseconds), context),
                                stats.updateCount,
                                stats.possibleUpdates,
                            )
                        } else {
                            context.stringResource(SYMR.strings.gallery_updater_not_ran_yet)
                        }

                        val allMeta = getExhFavoriteMangaWithMetadata.await()
                            .mapNotNull {
                                getFlatMetadataById.await(it.id)
                                    ?.raise<EHentaiSearchMetadata>()
                            }

                        fun metaInRelativeDuration(duration: Duration): Int {
                            val durationMs = duration.inWholeMilliseconds
                            return allMeta.asSequence().filter {
                                System.currentTimeMillis() - it.lastUpdateCheck < durationMs
                            }.count()
                        }

                        statsText + "\n\n" + context.stringResource(
                            SYMR.strings.gallery_updater_stats_time,
                            metaInRelativeDuration(1.hours),
                            metaInRelativeDuration(6.hours),
                            metaInRelativeDuration(12.hours),
                            metaInRelativeDuration(1.days),
                            metaInRelativeDuration(2.days),
                            metaInRelativeDuration(7.days),
                            metaInRelativeDuration(30.days),
                            metaInRelativeDuration(365.days),
                        )
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Error loading gallery update info" }
                        ""
                    }
                }
            }
            if (updateInfo == null) {
                UpdaterStatisticsLoadingDialog()
            } else {
                UpdaterStatisticsDialog(
                    onDismissRequest = { dialogOpen = false },
                    updateInfo = updateInfo.orEmpty(),
                )
            }
        }
        return Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.show_updater_statistics),
            onClick = {
                dialogOpen = true
            },
        )
    }
}
