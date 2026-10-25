package mihon.feature.migration.list

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.util.system.toast
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrateSearchRoute
import mihon.core.navigation.MigrationConfigRoute
import mihon.core.navigation.MigrationListRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.feature.migration.config.MigrationConfigScreenSheet
import mihon.feature.migration.list.components.MigrationExitDialog
import mihon.feature.migration.list.components.MigrationMangaDialog
import mihon.feature.migration.list.components.MigrationProgressDialog
import mihon.feature.migration.list.models.MigratingManga
import tachiyomi.i18n.MR

/**
 * Event sent by the manual migration search screens when the user picks a target manga while a
 * migration list is already on the back stack. Replaces the Voyager-era `addMatchOverride` call
 * on the old Screen object.
 */
data class MatchOverrideEvent(val current: Long, val target: Long)

/**
 * Screen showing a list of pair of current-target manga entries being migrated.
 */
@Composable
fun MigrationListScreen(
    mangaIds: Collection<Long>,
    extraSearchQuery: String?,
) {
    val backStack = LocalBackStack.current
    val screenModel = assistedMetroViewModel<MigrationListViewModel, MigrationListViewModel.Factory> {
        create(mangaIds = mangaIds, extraSearchQuery = extraSearchQuery)
    }
    val state by screenModel.state.collectAsState()
    val context = LocalContext.current

    ResultEffect<MatchOverrideEvent> {
        screenModel.useMangaForMigration(
            current = it.current,
            target = it.target,
            onMissingChapters = {
                context.toast(MR.strings.migrationListScreen_matchWithoutChapterToast, Toast.LENGTH_LONG)
            },
        )
    }

    LaunchedEffect(screenModel) {
        screenModel.navigateBackEvent.collect {
            // KMK -->
            /* If this screen is called from single manga migration, replace the MangaScreen in the backstack
               with the newly migrated manga to reflect the changes properly.
               Otherwise, just pop normally. */
            if (mangaIds.size == 1 && backStack.any { it is MangaRoute }) {
                // Use lastMigratedTargetMangaId if available (set before item removal),
                // otherwise try to extract from remaining items
                val mangaId = state.lastMigratedTargetMangaId
                    ?: (state.items.firstOrNull()?.searchResult?.value as? MigratingManga.SearchResult.Success)?.manga?.id
                if (mangaId != null) {
                    val newStack = backStack.filter {
                        it !is MangaRoute &&
                            it !is MigrationListRoute &&
                            it !is MigrationConfigRoute
                    } + MangaRoute(mangaId)
                    backStack.clear()
                    backStack.addAll(newStack)
                } else {
                    backStack.removeLastOrNull()
                }
            } else {
                // KMK <--
                backStack.removeLastOrNull()
            }
        }
    }
    MigrationListScreenContent(
        items = state.items,
        migrationComplete = state.migrationComplete,
        finishedCount = state.finishedCount,
        onItemClick = {
            backStack.add(MangaRoute(it.id, true))
        },
        onSearchManually = { migrationItem ->
            backStack.add(MigrateSearchRoute(migrationItem.manga.id))
        },
        onSkip = { screenModel.removeManga(it) },
        onMigrate = { screenModel.migrateNow(mangaId = it, replace = true) },
        onCopy = { screenModel.migrateNow(mangaId = it, replace = false) },
        openMigrationDialog = screenModel::showMigrateDialog,
        // KMK -->
        onCancel = { screenModel.cancelManga(it) },
        openOptionsDialog = screenModel::openOptionsDialog,
        // KMK <--
    )

    when (val dialog = state.dialog) {
        is MigrationListViewModel.Dialog.Migrate -> {
            MigrationMangaDialog(
                onDismissRequest = screenModel::dismissDialog,
                copy = dialog.copy,
                totalCount = dialog.totalCount,
                skippedCount = dialog.skippedCount,
                onMigrate = {
                    if (dialog.copy) {
                        screenModel.copyMangas()
                    } else {
                        screenModel.migrateMangas()
                    }
                },
            )
        }
        is MigrationListViewModel.Dialog.Progress -> {
            MigrationProgressDialog(
                progress = dialog.progress,
                exitMigration = screenModel::cancelMigrate,
            )
        }
        MigrationListViewModel.Dialog.Exit -> {
            MigrationExitDialog(
                onDismissRequest = screenModel::dismissDialog,
                exitMigration = { backStack.removeLastOrNull() },
            )
        }
        // KMK -->
        MigrationListViewModel.Dialog.Options -> {
            MigrationConfigScreenSheet(
                preferences = screenModel.preferences,
                onDismissRequest = screenModel::dismissDialog,
                onStartMigration = { _ ->
                    screenModel.dismissDialog()
                    screenModel.updateOptions()
                },
                fullSettings = false,
            )
        }
        // KMK <--
        null -> Unit
    }

    BackHandler(true) {
        screenModel.showExitDialog()
    }
}
