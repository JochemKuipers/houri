package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsSecurityRoute.PasswordDialog
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelectorRoute
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsRoute
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TrailingWidgetBuffer
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.io.IOException

// SY -->
@Composable
internal fun getSyncPreferences(syncPreferences: SyncPreferences, syncService: Int): List<Preference> {
    val context = LocalContext.current
    return listOf(
        Preference.PreferenceGroup(
            title = stringResource(SYMR.strings.pref_sync_service_category),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = syncPreferences.syncService(),
                    entries = persistentMapOf(
                        SyncManager.SyncService.NONE.value to stringResource(MR.strings.off),
                        SyncManager.SyncService.SYNCYOMI.value to stringResource(SYMR.strings.syncyomi),
                        SyncManager.SyncService.GOOGLE_DRIVE.value to stringResource(SYMR.strings.google_drive),
                        // KMK -->
                        SyncManager.SyncService.WEB_DAV.value to stringResource(KMR.strings.web_dav),
                        // KMK <--
                    ),
                    title = stringResource(SYMR.strings.pref_sync_service),
                    onValueChanged = {
                        // KMK -->
                        if (it != SyncManager.SyncService.NONE.value) {
                            SyncDataJob.setupTask(context)
                        } else {
                            SyncDataJob.setupTask(context, prefInterval = 0)
                        }
                        // KMK <--
                        true
                    },
                ),
            ),
        ),
    ) + getSyncServicePreferences(syncPreferences, syncService)
}

@Composable
private fun getSyncServicePreferences(syncPreferences: SyncPreferences, syncService: Int): List<Preference> {
    val syncServiceType = SyncManager.SyncService.fromInt(syncService)

    val basePreferences = getBasePreferences(syncServiceType, syncPreferences)

    return if (syncServiceType != SyncManager.SyncService.NONE) {
        basePreferences + getAdditionalPreferences(syncPreferences)
    } else {
        basePreferences
    }
}

@Composable
private fun getBasePreferences(
    syncServiceType: SyncManager.SyncService,
    syncPreferences: SyncPreferences,
): List<Preference> {
    val backStack = LocalBackStack.current
    val preferences = when (syncServiceType) {
        SyncManager.SyncService.NONE -> emptyList()
        SyncManager.SyncService.SYNCYOMI -> getSelfHostPreferences(syncPreferences)
        SyncManager.SyncService.GOOGLE_DRIVE -> getGoogleDrivePreferences()
        // KMK -->
        SyncManager.SyncService.WEB_DAV -> getWebDavPreferences(syncPreferences)
        // KMK <--
    }

    return if (syncServiceType != SyncManager.SyncService.NONE) {
        preferences + Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.pref_choose_what_to_sync),
            onClick = {
                backStack.add(SyncSettingsSelectorRoute)
            },
        )
    } else {
        preferences
    }
}

@Composable
internal fun getAdditionalPreferences(syncPreferences: SyncPreferences): List<Preference> {
    return listOf(
        getSyncNowPref(),
        getAutomaticSyncGroup(syncPreferences),
        // KMK -->
        Preference.PreferenceItem.SwitchPreference(
            preference = syncPreferences.showSyncingProgressBanner(),
            title = stringResource(KMR.strings.pref_show_syncing_progress_banner),
        ),
        // KMK <--
    )
}

@Composable
internal fun getGoogleDrivePreferences(): List<Preference> {
    val context = LocalContext.current
    val googleDriveSync = globalAppGraph.googleDriveService
    return listOf(
        Preference.PreferenceItem.TextPreference(
            title = stringResource(SYMR.strings.pref_google_drive_sign_in),
            onClick = {
                // KMK --> a build without the client_secrets.json asset (CI injects it)
                // used to crash the screen; surface it as a toast instead.
                try {
                    context.startActivity(googleDriveSync.getSignInIntent())
                } catch (e: IOException) {
                    context.toast(
                        context.stringResource(SYMR.strings.google_drive_login_failed, e.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    )
                } catch (e: ActivityNotFoundException) {
                    // no browser on the device: startActivity throws before the
                    // OAuth code even runs, and an uncaught ActivityNotFoundException
                    // crashes here. Same handling as the asset failure, through the same
                    // login-failed message (SY #1372).
                    context.toast(
                        context.stringResource(SYMR.strings.google_drive_login_failed, e.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    )
                }
                // KMK <--
            },
        ),
        getGoogleDrivePurge(),
    )
}

@Composable
internal fun getGoogleDrivePurge(): Preference.PreferenceItem.TextPreference {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val googleDriveSync = remember { GoogleDriveSyncService(context) }
    var showPurgeDialog by remember { mutableStateOf(false) }

    if (showPurgeDialog) {
        PurgeConfirmationDialog(
            onConfirm = {
                showPurgeDialog = false
                scope.launch {
                    val result = googleDriveSync.deleteSyncDataFromGoogleDrive()
                    when (result) {
                        GoogleDriveSyncService.DeleteSyncDataStatus.NOT_INITIALIZED -> context.toast(
                            SYMR.strings.google_drive_not_signed_in,
                            duration = 5000,
                        )
                        GoogleDriveSyncService.DeleteSyncDataStatus.NO_FILES -> context.toast(
                            SYMR.strings.google_drive_sync_data_not_found,
                            duration = 5000,
                        )
                        GoogleDriveSyncService.DeleteSyncDataStatus.SUCCESS -> context.toast(
                            SYMR.strings.google_drive_sync_data_purged,
                            duration = 5000,
                        )
                        GoogleDriveSyncService.DeleteSyncDataStatus.ERROR -> context.toast(
                            SYMR.strings.google_drive_sync_data_purge_error,
                            duration = 10000,
                        )
                    }
                }
            },
            onDismissRequest = { showPurgeDialog = false },
        )
    }

    return Preference.PreferenceItem.TextPreference(
        title = stringResource(SYMR.strings.pref_google_drive_purge_sync_data),
        onClick = { showPurgeDialog = true },
    )
}

@Composable
internal fun PurgeConfirmationDialog(
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(SYMR.strings.pref_purge_confirmation_title)) },
        text = { Text(text = stringResource(SYMR.strings.pref_purge_confirmation_message)) },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
internal fun getSelfHostPreferences(syncPreferences: SyncPreferences): List<Preference> {
    val scope = rememberCoroutineScope()

    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) {
        if (it.contents != null && it.contents.isNotEmpty()) {
            syncPreferences.clientAPIKey().set(it.contents)
        }
    }
    val context = LocalContext.current
    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setOrientationLocked(false)
            setPrompt(SYMR.strings.scan_qr_code.getString(context))
            addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
        }
    }

    return listOf(
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.clientHost(),
            title = stringResource(SYMR.strings.pref_sync_host),
            subtitle = stringResource(SYMR.strings.pref_sync_host_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    // Trim spaces at the beginning and end, then remove trailing slash if present
                    val trimmedValue = newValue.trim()
                    val modifiedValue = trimmedValue.trimEnd { it == '/' }
                    syncPreferences.clientHost().set(modifiedValue)
                    // Force a capabilities re-probe on the next SyncYomi sync.
                    syncPreferences.v2ProbedHost().set("")
                }
                true
            },
        ),
        Preference.PreferenceItem.SwitchPreference(
            preference = syncPreferences.useProtocolV2(),
            title = stringResource(SYMR.strings.pref_sync_protocol_v2),
            subtitle = stringResource(SYMR.strings.pref_sync_protocol_v2_summ),
            onValueChanged = { enabled ->
                // Re-probe (or forget) on the next sync so the cached answer cannot stick.
                syncPreferences.v2ProbedHost().set("")
                if (!enabled) {
                    syncPreferences.serverSupportsV2().set(false)
                }
                true
            },
        ),
        Preference.PreferenceItem.CustomPreference(
            title = stringResource(SYMR.strings.pref_sync_api_key),
        ) {
            val values by syncPreferences.clientAPIKey().collectAsState()
            EditTextPreferenceWidget(
                title = stringResource(SYMR.strings.pref_sync_api_key),
                subtitle = stringResource(SYMR.strings.pref_sync_api_key_summ),
                onConfirm = {
                    scope.launch {
                        syncPreferences.clientAPIKey().set(it)
                    }
                    true
                },
                icon = null,
                value = values,
                widget = {
                    IconButton(
                        onClick = { qrScanLauncher.launch(scanOptions) },
                        modifier = Modifier.padding(start = TrailingWidgetBuffer),
                    ) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(SYMR.strings.scan_qr_code),
                        )
                    }
                },
            )
        },
    )
}

// KMK -->
@Composable
internal fun getWebDavPreferences(syncPreferences: SyncPreferences): List<Preference> {
    val scope = rememberCoroutineScope()

    return listOf(
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.webDavUrl(),
            title = stringResource(KMR.strings.pref_webdav_url),
            subtitle = stringResource(KMR.strings.pref_webdav_url_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    syncPreferences.webDavUrl().set(newValue.trim())
                }
                true
            },
        ),
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.webDavUsername(),
            title = stringResource(KMR.strings.pref_webdav_username),
            subtitle = stringResource(KMR.strings.pref_webdav_username_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    syncPreferences.webDavUsername().set(newValue.trim())
                }
                true
            },
        ),
        run {
            var dialogOpen by remember { mutableStateOf(false) }
            if (dialogOpen) {
                PasswordDialog(
                    onDismissRequest = { dialogOpen = false },
                    onReturnPassword = { password ->
                        dialogOpen = false
                        scope.launch {
                            syncPreferences.webDavPassword().set(password.replace("\n", ""))
                        }
                    },
                    title = KMR.strings.pref_webdav_password,
                )
            }
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.pref_webdav_password),
                subtitle = stringResource(KMR.strings.pref_webdav_password_summ),
                onClick = {
                    dialogOpen = true
                },
            )
        },
        Preference.PreferenceItem.EditTextPreference(
            preference = syncPreferences.webDavFolder(),
            title = stringResource(KMR.strings.pref_webdav_folder),
            subtitle = stringResource(KMR.strings.pref_webdav_folder_summ),
            onValueChanged = { newValue ->
                scope.launch {
                    syncPreferences.webDavFolder().set(newValue.trim())
                }
                true
            },
        ),
    )
}
// KMK <--

@Composable
internal fun getSyncNowPref(): Preference.PreferenceGroup {
    val context = LocalContext.current
    return Preference.PreferenceGroup(
        title = stringResource(SYMR.strings.pref_sync_now_group_title),
        preferenceItems = persistentListOf(
            getSyncOptionsPref(),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(SYMR.strings.pref_sync_now),
                subtitle = stringResource(SYMR.strings.pref_sync_now_subtitle),
                onClick = {
                    if (!SyncDataJob.isRunning(context)) {
                        SyncDataJob.startNow(context, manual = true)
                    } else {
                        context.toast(SYMR.strings.sync_in_progress)
                    }
                },
            ),
        ),
    )
}

@Composable
internal fun getSyncOptionsPref(): Preference.PreferenceItem.TextPreference {
    val backStack = LocalBackStack.current
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(SYMR.strings.pref_sync_options),
        subtitle = stringResource(SYMR.strings.pref_sync_options_summ),
        onClick = { backStack.add(SyncTriggerOptionsRoute) },
    )
}

@Composable
internal fun getAutomaticSyncGroup(syncPreferences: SyncPreferences): Preference.PreferenceGroup {
    val context = LocalContext.current
    val syncIntervalPref = syncPreferences.syncInterval()
    val lastSync by syncPreferences.lastSyncTimestamp().collectAsState()

    return Preference.PreferenceGroup(
        title = stringResource(SYMR.strings.pref_sync_automatic_category),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.ListPreference(
                preference = syncIntervalPref,
                entries = persistentMapOf(
                    0 to stringResource(MR.strings.off),
                    30 to stringResource(SYMR.strings.update_30min),
                    60 to stringResource(SYMR.strings.update_1hour),
                    180 to stringResource(SYMR.strings.update_3hour),
                    360 to stringResource(MR.strings.update_6hour),
                    720 to stringResource(MR.strings.update_12hour),
                    1440 to stringResource(MR.strings.update_24hour),
                    2880 to stringResource(MR.strings.update_48hour),
                    10080 to stringResource(MR.strings.update_weekly),
                ),
                title = stringResource(SYMR.strings.pref_sync_interval),
                onValueChanged = {
                    SyncDataJob.setupTask(context, prefInterval = it)
                    true
                },
            ),
            Preference.PreferenceItem.InfoPreference(
                stringResource(SYMR.strings.last_synchronization, relativeTimeSpanString(lastSync)),
            ),
        ),
    )
}
// SY <--
