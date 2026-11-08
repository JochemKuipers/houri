package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.model.RemovedCoverRetention
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceDependency
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.extension.ExtensionAutoUpdateWorker
import eu.kanade.tachiyomi.network.interceptor.FlareSolverr
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.isShizukuInstalled
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.ClearDatabaseRoute
import mihon.core.navigation.DebugInfoRoute
import mihon.core.navigation.OnboardingRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.release.service.AppUpdatePolicy
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.preference.Preference as BasePreference

@Serializable
data object SettingsAdvancedRoute : NavKey, SearchableRoute {
    @Suppress("unused")
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val backStack = LocalBackStack.current

        val basePreferences = remember { globalAppGraph.basePreferences }
        val networkPreferences = remember { globalAppGraph.networkPreferences }
        val libraryPreferences = remember { globalAppGraph.libraryPreferences }
        // SY -->
        val downloadPreferences = remember { globalAppGraph.downloadPreferences }
        val exhPreferences = remember { globalAppGraph.exhPreferences }
        // SY <--

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_dump_crash_logs),
                subtitle = stringResource(MR.strings.pref_dump_crash_logs_summary),
                onClick = {
                    scope.launch {
                        CrashLogUtil(context).dumpLogs()
                    }
                },
            ),
            /* SY --> Preference.PreferenceItem.SwitchPreference(
                preference = networkPreferences.verboseLogging(),
                title = stringResource(MR.strings.pref_verbose_logging),
                subtitle = stringResource(MR.strings.pref_verbose_logging_summary),
                onValueChanged = {
                    context.toast(MR.strings.requires_app_restart)
                    true
                },
            ), SY <-- */
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_debug_info),
                onClick = { backStack.add(DebugInfoRoute) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_onboarding_guide),
                onClick = { backStack.add(OnboardingRoute) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_manage_notifications),
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
            ),
            // KMK -->
            Preference.PreferenceItem.MultiSelectListPreference(
                preference = exhPreferences.appShouldAutoUpdate(),
                entries = persistentMapOf(
                    AppUpdatePolicy.DEVICE_ONLY_ON_WIFI to stringResource(MR.strings.connected_to_wifi),
                    AppUpdatePolicy.DEVICE_NETWORK_NOT_METERED to stringResource(MR.strings.network_not_metered),
                    AppUpdatePolicy.DEVICE_CHARGING to stringResource(MR.strings.charging),
                    AppUpdatePolicy.DISABLE_AUTO_DOWNLOAD to stringResource(KMR.strings.auto_update_app_disable_auto_download),
                ),
                title = stringResource(KMR.strings.auto_update_app),
                subtitle = stringResource(MR.strings.restrictions),
                onValueChanged = {
                    true
                },
            ),
            // KMK <--
            getBackgroundActivityGroup(),
            getDataGroup(),
            getNetworkGroup(networkPreferences = networkPreferences),
            getLibraryGroup(libraryPreferences = libraryPreferences),
            // SY -->
            getDownloadsGroup(downloadPreferences = downloadPreferences),
            // SY <--
            getReaderGroup(
                basePreferences = basePreferences,
                // KMK -->
                readerPreferences = globalAppGraph.readerPreferences,
                // KMK <--
            ),
            getExtensionsGroup(basePreferences = basePreferences),
            // SY -->
            // getDownloaderGroup(),
            getDataSaverGroup(),
            getDeveloperToolsGroup(),
            // SY <--
        )
    }

    @Composable
    private fun getBackgroundActivityGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_background_activity),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_disable_battery_optimization),
                    subtitle = stringResource(MR.strings.pref_disable_battery_optimization_summary),
                    onClick = {
                        val packageName: String = context.packageName
                        if (!context.powerManager.isIgnoringBatteryOptimizations(packageName)) {
                            try {
                                @SuppressLint("BatteryLife")
                                val intent = Intent().apply {
                                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                    data = "package:$packageName".toUri()
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                context.toast(MR.strings.battery_optimization_setting_activity_not_found)
                            }
                        } else {
                            context.toast(MR.strings.battery_optimization_disabled)
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Don't kill my app!",
                    subtitle = stringResource(MR.strings.about_dont_kill_my_app),
                    onClick = { uriHandler.openUri("https://dontkillmyapp.com/") },
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val backStack = LocalBackStack.current
        // KMK -->
        val uiPreferences = globalAppGraph.uiPreferences
        // KMK <--

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_data),
            preferenceItems = persistentListOf(
                // KMK --> Retention is a window, not a size, so it reads better as a list of periods
                // than as a raw day count the user has to interpret.
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.removedCoverRetention(),
                    title = stringResource(KMR.strings.pref_removed_cover_retention),
                    subtitle = stringResource(KMR.strings.pref_removed_cover_retention_summary),
                    entries = RemovedCoverRetention.entries.associateWith { retention ->
                        stringResource(
                            when (retention) {
                                RemovedCoverRetention.IMMEDIATE -> KMR.strings.pref_removed_cover_retention_immediate
                                RemovedCoverRetention.ONE_DAY -> KMR.strings.pref_removed_cover_retention_1_day
                                RemovedCoverRetention.THREE_DAYS -> KMR.strings.pref_removed_cover_retention_3_days
                                RemovedCoverRetention.SEVEN_DAYS -> KMR.strings.pref_removed_cover_retention_7_days
                                RemovedCoverRetention.THIRTY_DAYS -> KMR.strings.pref_removed_cover_retention_30_days
                            },
                        )
                    }
                        .toImmutableMap(),
                ),
                // KMK <-->
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_invalidate_download_cache),
                    subtitle = stringResource(MR.strings.pref_invalidate_download_cache_summary),
                    onClick = {
                        globalAppGraph.downloadCache.invalidateCache()
                        context.toast(MR.strings.download_cache_invalidated)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_database),
                    subtitle = stringResource(MR.strings.pref_clear_database_summary),
                    onClick = { backStack.add(ClearDatabaseRoute) },
                ),
            ),
        )
    }

    @Composable
    private fun getLibraryGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        // KMK -->
        val uiPreferences = remember { globalAppGraph.uiPreferences }
        // KMK <--

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_library),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_refresh_library_covers),
                    onClick = { MetadataUpdateJob.startNow(context) },
                ),
                // KMK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.preloadLibraryColor(),
                    title = stringResource(KMR.strings.preload_library_cover_color),
                ),
                // KMK <--
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_reset_viewer_flags),
                    subtitle = stringResource(MR.strings.pref_reset_viewer_flags_summary),
                    onClick = {
                        scope.launchNonCancellable {
                            val success = globalAppGraph.resetViewerFlags.await()
                            withUIContext {
                                val message = if (success) {
                                    MR.strings.pref_reset_viewer_flags_success
                                } else {
                                    MR.strings.pref_reset_viewer_flags_error
                                }
                                context.toast(message)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.updateMangaTitles(),
                    title = stringResource(MR.strings.pref_update_library_manga_titles),
                    subtitle = stringResource(MR.strings.pref_update_library_manga_titles_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.disallowNonAsciiFilenames(),
                    title = stringResource(MR.strings.pref_disallow_non_ascii_filenames),
                    subtitle = stringResource(MR.strings.pref_disallow_non_ascii_filenames_details),
                ),
                // KMK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.smartScanlatorMerge(),
                    title = stringResource(KMR.strings.pref_smart_scanlator_merge),
                    subtitle = stringResource(KMR.strings.pref_smart_scanlator_merge_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.showSmartScanlatorInDetails(),
                    title = stringResource(KMR.strings.pref_show_smart_scanlator_details),
                    subtitle = stringResource(KMR.strings.pref_show_smart_scanlator_details_summary),
                    dependsOn = PreferenceDependency(libraryPreferences.smartScanlatorMerge()),
                ),
                // KMK <--
            ),
        )
    }

    // SY ->
    @Composable
    private fun getDownloadsGroup(
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_downloads),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.includeChapterUrlHash(),
                    title = stringResource(SYMR.strings.pref_include_chapter_url_hash),
                    subtitle = stringResource(SYMR.strings.pref_include_chapter_url_hash_desc),
                ),
            ),
        )
    }
    // <- SY

    @Composable
    private fun getReaderGroup(
        basePreferences: BasePreferences,
        // KMK -->
        readerPreferences: ReaderPreferences,
        // KMK <--
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val chooseColorProfile = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                basePreferences.displayProfile().set(uri.toString())
            }
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reader),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = basePreferences.hardwareBitmapThreshold(),
                    entries = GLUtil.CUSTOM_TEXTURE_LIMIT_OPTIONS
                        .mapIndexed { index, option ->
                            val display = if (index == 0) {
                                stringResource(MR.strings.pref_hardware_bitmap_threshold_default, option)
                            } else {
                                option.toString()
                            }
                            option to display
                        }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_hardware_bitmap_threshold),
                    subtitleProvider = { value, options ->
                        stringResource(MR.strings.pref_hardware_bitmap_threshold_summary, options[value].orEmpty())
                    },
                    enabled = !ImageUtil.HARDWARE_BITMAP_UNSUPPORTED &&
                        GLUtil.DEVICE_TEXTURE_LIMIT > GLUtil.SAFE_TEXTURE_LIMIT,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = basePreferences.alwaysDecodeLongStripWithSSIV(),
                    title = stringResource(MR.strings.pref_always_decode_long_strip_with_ssiv_2),
                    subtitle = stringResource(MR.strings.pref_always_decode_long_strip_with_ssiv_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = basePreferences.highQualityRenderer(),
                    title = stringResource(MR.strings.pref_high_quality_renderer),
                    subtitle = stringResource(MR.strings.pref_high_quality_renderer_summary),
                ),
                // KMK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageMatchHeights(),
                    title = stringResource(KMR.strings.pref_dual_page_match_heights),
                    subtitle = stringResource(KMR.strings.pref_dual_page_match_heights_summary),
                ),
                // KMK <--
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_display_profile),
                    subtitle = basePreferences.displayProfile().get(),
                    onClick = {
                        chooseColorProfile.launch(arrayOf("*/*"))
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getExtensionsGroup(
        basePreferences: BasePreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val extensionInstallerPref = basePreferences.extensionInstaller()
        var shizukuMissing by rememberSaveable { mutableStateOf(false) }
        val trustExtension = remember { globalAppGraph.trustExtension }
        // KMK --> Auto extension updates. Resolved per composition rather than cached, because
        // both the toggle and the interval are also written from the extensions page, and the
        // schedule has to be reconciled whenever either changes.
        val sourcePreferences = globalAppGraph.sourcePreferences
        val autoUpdatePref = sourcePreferences.extensionAutoUpdate()
        val autoUpdateIntervalPref = sourcePreferences.extensionAutoUpdateInterval()
        val autoUpdateSupported = sourcePreferences.extensionAutoUpdateSupported()

        if (shizukuMissing) {
            val dismiss = { shizukuMissing = false }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(text = stringResource(MR.strings.ext_installer_shizuku)) },
                text = { Text(text = stringResource(MR.strings.ext_installer_shizuku_unavailable_dialog)) },
                dismissButton = {
                    TextButton(onClick = dismiss) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dismiss()
                            uriHandler.openUri("https://shizuku.rikka.app/download")
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_extensions),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = extensionInstallerPref,
                    entries = extensionInstallerPref.entries
                        // KMK --> PRIVATE is offered on release builds too. It was hidden while it
                        // made cold start and resume crawl on a large collection; ExtensionLoader now
                        // reads each private archive once instead of once per installed extension
                        // and does it on the extensions pool, so that cost is gone. It is also the
                        // only installer that needs no install permission, which matters while
                        // Google gates "install from unverified developers" behind a 24 hour wait
                        // that can lapse - see pref_private_installer_warning.
                        // KMK <--
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.ext_installer_pref),
                    onValueChanged = {
                        if (it == BasePreferences.ExtensionInstaller.SHIZUKU &&
                            !context.isShizukuInstalled
                        ) {
                            shizukuMissing = true
                            false
                        } else {
                            true
                        }
                    },
                ),
                // KMK -->
                Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.pref_private_installer_warning)),
                // KMK <--
                // KMK --> Automatic extension updates. The interval row is kept enabled when the
                // toggle is off on purpose: turning auto-update off and back on should restore the
                // interval the user picked, not reset it. Grayed when the selected installer
                // cannot finish an install in the background, with the reason stated, instead of
                // silently doing nothing.
                Preference.PreferenceItem.SwitchPreference(
                    preference = autoUpdatePref,
                    title = stringResource(KMR.strings.pref_auto_update_extensions),
                    subtitle = stringResource(
                        if (autoUpdateSupported) {
                            KMR.strings.pref_auto_update_extensions_summary
                        } else {
                            KMR.strings.pref_auto_update_extensions_unsupported
                        },
                    ),
                    enabled = autoUpdateSupported,
                    onValueChanged = {
                        ExtensionAutoUpdateWorker.schedule(context)
                        true
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = autoUpdateIntervalPref,
                    entries = persistentMapOf(
                        6 to stringResource(KMR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_library_update_interval),
                    enabled = autoUpdateSupported,
                    onValueChanged = {
                        ExtensionAutoUpdateWorker.schedule(context)
                        true
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.ext_revoke_trust),
                    onClick = {
                        trustExtension.revokeAll()
                        context.toast(MR.strings.requires_app_restart)
                    },
                ),
            ),
        )
    }
}
