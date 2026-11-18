package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import com.hippo.unifile.UniFile
import eu.kanade.presentation.category.hierarchicalVisualName
import eu.kanade.presentation.category.sortedByHierarchy
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.database.DatabaseExporter
import eu.kanade.tachiyomi.data.download.DownloadBulkDelete
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.navigation.CreateBackupRoute
import mihon.core.navigation.DownloadedContentRoute
import mihon.core.navigation.RestoreBackupRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.Category.Companion.UNCATEGORIZED_ID
import tachiyomi.domain.storage.service.StorageManager.Companion.allowAccessStorage
import tachiyomi.domain.storage.service.StorageManager.Companion.directoryAccessible
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object SettingsDataRoute : NavKey, SearchableRoute {
    @Suppress("unused")
    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://komikku-app.github.io/docs/faq/storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri(HELP_URL) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val backupPreferences = globalAppGraph.backupPreferences
        val storagePreferences = globalAppGraph.storagePreferences

        val syncPreferences = remember { globalAppGraph.syncPreferences }
        val syncService by syncPreferences.syncService().collectAsState()

        return persistentListOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(),
            getExportGroup(),
        ) +
            // SY -->
            getSyncPreferences(syncPreferences = syncPreferences, syncService = syncService)
        // SY <--
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                // For some reason InkBook devices do not implement the SAF properly. Persistable URI grants do not
                // work. However, simply retrieving the URI and using it works fine for these devices. Access is not
                // revoked after the app is closed or the device is restarted.
                // This also holds for some Samsung devices. Thus, we simply execute inside of a try-catch block and
                // ignore the exception if it is thrown.
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                    context.toast(MR.strings.file_picker_uri_permission_unsupported)
                }

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set("") // Trigger recompose
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        // KMK -->
        var locationValid by remember(storageDir) {
            mutableStateOf(directoryAccessible(context, storageDir))
        }

        LaunchedEffect(storageDir) {
            storageDirPref.changes()
                .collectLatest {
                    locationValid = directoryAccessible(context, storageDir)
                }
        }

        if (!locationValid) {
            // KMK <--
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val file = UniFile.fromUri(context, storageDir.toUri())
            file?.displayablePath
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory())

        // KMK -->
        val storagePref = storagePreferences.baseStorageDirectory()
        // KMK <--

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = storageLocationText(/* KMK --> */storagePref/* KMK <-- */),
            onClick = {
                try {
                    // KMK -->
                    allowAccessStorage(context, storagePref) {
                        // KMK <--
                        pickStorageLocation.launch(null)
                    }
                } catch (_: Exception) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val backStack = LocalBackStack.current

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp().collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            object : ActivityResultContracts.GetContent() {
                override fun createIntent(context: Context, input: String): Intent {
                    val intent = super.createIntent(context, input)
                    return Intent.createChooser(intent, context.stringResource(MR.strings.file_select_backup))
                }
            },
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            backStack.add(RestoreBackupRoute(it.toString()))
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = persistentListOf(
                // Manual actions
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(restorePreferenceKeyString),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            MultiChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(intrinsicSize = IntrinsicSize.Min)
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = { backStack.add(CreateBackupRoute) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        if (!BackupRestoreJob.isRunning(context)) {
                                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                context.toast(MR.strings.restore_miui_warning)
                                            }

                                            // no need to catch because it's wrapped with a chooser
                                            chooseBackup.launch("*/*")
                                        } else {
                                            context.toast(MR.strings.restore_in_progress)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_restore_backup))
                                }
                            }
                        },
                    )
                },

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    preference = backupPreferences.backupInterval(),
                    entries = persistentMapOf(
                        0 to stringResource(MR.strings.off),
                        1 to stringResource(MR.strings.update_1hour),
                        3 to stringResource(MR.strings.update_3hour),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
                // KMK -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = backupPreferences.showRestoringProgressBanner(),
                    title = stringResource(KMR.strings.pref_show_restoring_progress_banner),
                ),
                // KMK <--
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { globalAppGraph.libraryPreferences }

        // KMK -->
        val backStack = LocalBackStack.current
        // KMK <--

        val chapterCache = remember { globalAppGraph.chapterCache }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }

        // KMK -->
        // Bulk download deletion (issue #82). Built here rather than injected so the settings
        // screen owns the whole action, and so the deletion rules stay a plain class that can be
        // asserted without the Android framework.
        var bulkDeleteScope by remember { mutableStateOf<BulkDeleteScope?>(null) }
        val bulkDelete = remember {
            DownloadBulkDelete(
                downloadManager = globalAppGraph.downloadManager,
                downloadCache = globalAppGraph.downloadCache,
                downloadProvider = globalAppGraph.downloadProvider,
                sourceManager = globalAppGraph.sourceManager,
                getFavorites = globalAppGraph.getFavorites,
                getNonLibraryManga = globalAppGraph.getNonLibraryManga,
            )
        }

        bulkDeleteScope?.let { pending ->
            AlertDialog(
                onDismissRequest = { bulkDeleteScope = null },
                title = {
                    Text(
                        text = stringResource(
                            if (pending == BulkDeleteScope.All) {
                                KMR.strings.pref_delete_all_downloads
                            } else {
                                KMR.strings.pref_delete_not_in_library_downloads
                            },
                        ),
                    )
                },
                text = {
                    Text(
                        text = stringResource(
                            if (pending == BulkDeleteScope.All) {
                                KMR.strings.pref_delete_all_downloads_confirm
                            } else {
                                KMR.strings.pref_delete_not_in_library_downloads_confirm
                            },
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            bulkDeleteScope = null
                            scope.launchNonCancellable {
                                val result = if (pending == BulkDeleteScope.All) {
                                    bulkDelete.deleteAll()
                                } else {
                                    bulkDelete.deleteNotInLibrary()
                                }
                                withUIContext {
                                    context.toast(
                                        if (result.failed) {
                                            context.stringResource(KMR.strings.downloads_delete_error)
                                        } else {
                                            context.stringResource(KMR.strings.downloads_deleted, result.deletedEntries)
                                        },
                                    )
                                }
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { bulkDeleteScope = null }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        // KMK <--

        // SY -->
        val pagePreviewCache = remember { globalAppGraph.pagePreviewCache }
        var pagePreviewReadableSizeSema by remember { mutableIntStateOf(0) }
        val pagePreviewReadableSize = remember(pagePreviewReadableSizeSema) { pagePreviewCache.readableSize }
        // SY <--

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                // SY -->
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(SYMR.strings.pref_clear_page_preview_cache),
                    subtitle = stringResource(MR.strings.used_cache, pagePreviewReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = pagePreviewCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    pagePreviewReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                // SY <--
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache(),
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
                // KMK -->
                Preference.PreferenceItem.ListPreference(
                    preference = libraryPreferences.chapterCacheRetention(),
                    entries = persistentMapOf(
                        0 to stringResource(KMR.strings.chapter_cache_retention_off),
                        12 to stringResource(KMR.strings.chapter_cache_retention_12h),
                        24 to stringResource(KMR.strings.chapter_cache_retention_24h),
                        72 to stringResource(KMR.strings.chapter_cache_retention_3d),
                        168 to stringResource(KMR.strings.chapter_cache_retention_7d),
                        720 to stringResource(KMR.strings.chapter_cache_retention_30d),
                    ),
                    title = stringResource(KMR.strings.chapter_cache_retention_title),
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(KMR.strings.chapter_cache_retention_support),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_downloaded_content),
                    subtitle = stringResource(KMR.strings.pref_downloaded_content_support),
                    onClick = { backStack.add(DownloadedContentRoute) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_delete_not_in_library_downloads),
                    subtitle = stringResource(KMR.strings.pref_delete_not_in_library_downloads_summary),
                    onClick = { bulkDeleteScope = BulkDeleteScope.NotInLibrary },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_delete_all_downloads),
                    subtitle = stringResource(KMR.strings.pref_delete_all_downloads_summary),
                    onClick = { bulkDeleteScope = BulkDeleteScope.All },
                ),
                // KMK <--
            ),
        )
    }

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val getFavorites = remember { globalAppGraph.getFavorites }
        val getCategories = remember { globalAppGraph.getCategories }
        val mangaRepository = remember { globalAppGraph.mangaRepository }
        var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
        // KMK --> Loaded when the dialog opens, so the list reflects the current categories
        // without paying for a query while the settings screen merely sits here.
        LaunchedEffect(showDialog) {
            if (showDialog) categories = withIOContext { getCategories.await() }
        }
        // KMK <--

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                // KMK --> Favorites and memberships are fetched here rather than when the
                // settings screen opened, so the file reflects the library as of the moment
                // the user confirmed the export. An entry with no membership is "Default";
                // selecting Default exports those entries alongside the named categories.
                val favorites = withIOContext { getFavorites.await() }
                val scopedFavorites = exportOptions.includedCategoryIds
                    ?.let { selectedIds ->
                        val memberships = withIOContext { mangaRepository.getFavoriteCategoryPairs() }
                        favorites.filter { manga ->
                            val mangaCategories = memberships[manga.id].orEmpty()
                            (UNCATEGORIZED_ID in selectedIds && mangaCategories.isEmpty()) ||
                                mangaCategories.any(selectedIds::contains)
                        }
                    }
                    ?: favorites
                // KMK <--
                LibraryExporter.exportToCsv(
                    context = context,
                    uri = uri,
                    favorites = scopedFavorites,
                    options = exportOptions,
                    onExportComplete = {
                        scope.launch(Dispatchers.Main) {
                            context.toast(MR.strings.library_exported)
                        }
                    },
                )
            }
        }

        val databaseExporter = remember { globalAppGraph.databaseExporter }
        val exportDbLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                // Subject bound to a val so the failure branch smart-casts: ktlint rejects the typed
                // branch-lambda alternative, and `when` subjects have no implicit `it`.
                when (val result = databaseExporter.export(uri)) {
                    DatabaseExporter.Result.Success -> context.toast(KMR.strings.database_export_success)
                    // The checkpoint is what keeps recent writes out of the exported file, so
                    // refusing is the only honest outcome here.
                    DatabaseExporter.Result.SnapshotUnavailable ->
                        context.toast(KMR.strings.database_export_checkpoint_failed)

                    is DatabaseExporter.Result.Failed -> {
                        xLogE("Database export failed", result.cause)
                        context.toast(KMR.strings.database_export_failed)
                    }
                }
            }
        }

        if (showDialog) {
            ExportOptionsDialog(
                options = exportOptions,
                categories = categories,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("houri_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
                // KMK --> Snapshot export: DatabaseExporter checkpoints the WAL on the live
                // connection first, so the file carries every commit rather than whatever
                // happened to be folded into the main file at that instant.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_export_database),
                    subtitle = stringResource(KMR.strings.pref_export_database_summary),
                    onClick = { exportDbLauncher.launch(databaseExporter.suggestedFileName) },
                ),
                // KMK <--
            ),
        )
    }

    // KMK --> Extended from the column-only picker: it also scopes the export to categories.
    // Default (uncategorized) is listed alongside the named categories and selects the
    // entries with no category membership, matching how the library groups them.
    @Composable
    private fun ExportOptionsDialog(
        options: ExportOptions,
        categories: List<Category>,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }
        val categoryIds = remember(categories) { categories.map { it.id } }
        var selectedCategories by remember(categories) {
            mutableStateOf(options.includedCategoryIds ?: categoryIds.toSet())
        }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }

                    if (categories.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(MR.strings.categories),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        categories.sortedByHierarchy().forEach { category ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = category.id in selectedCategories,
                                    onCheckedChange = { checked ->
                                        selectedCategories = if (checked) {
                                            selectedCategories + category.id
                                        } else {
                                            selectedCategories - category.id
                                        }
                                    },
                                )
                                Text(text = category.hierarchicalVisualName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = categories.isEmpty() || selectedCategories.isNotEmpty(),
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                                // All categories checked means no scoping: the file matches
                                // the pre-scoping export and stays reload-compatible.
                                includedCategoryIds = selectedCategories
                                    .takeUnless { categoryIds.isEmpty() || it.size == categoryIds.size },
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
    // KMK <--
}

// KMK -->
/**
 * Which bulk download deletion the settings screen is confirming. Both are ask-then-work: the
 * dialog is the only place the scope is chosen, and the work runs off the main thread.
 */
private enum class BulkDeleteScope {
    /** Every downloaded chapter, library entries included. */
    All,

    /** Only the entries that are not in the library. */
    NotInLibrary,
}
// KMK <--
