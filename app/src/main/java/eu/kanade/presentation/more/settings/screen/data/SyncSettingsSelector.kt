package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Serializable
data object SyncSettingsSelectorRoute : NavKey

@Composable
fun SyncSettingsSelector() {
    val backStack = LocalBackStack.current
    val model = metroViewModel<SyncSettingsSelectorViewModel>()
    val state by model.state.collectAsState()

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(SYMR.strings.pref_choose_what_to_sync),
                navigateUp = backStack::removeLastOrNull,
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        LazyColumnWithAction(
            contentPadding = contentPadding,
            actionLabel = stringResource(MR.strings.action_save),
            actionEnabled = true,
            onClickAction = {
                backStack.removeLastOrNull()
            },
        ) {
            item {
                SectionCard(MR.strings.label_library) {
                    Options(BackupOptions.libraryOptions, state, model)
                }
            }

            item {
                SectionCard(MR.strings.label_settings) {
                    Options(BackupOptions.settingsOptions, state, model)
                }
            }
        }
    }
}

@Composable
private fun Options(
    options: ImmutableList<BackupOptions.Entry>,
    state: SyncSettingsSelectorViewModel.State,
    model: SyncSettingsSelectorViewModel,
) {
    options.forEach { option ->
        LabeledCheckbox(
            label = stringResource(option.label),
            checked = option.getter(state.options),
            onCheckedChange = {
                model.toggle(option.setter, it)
            },
            enabled = option.enabled(state.options),
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SyncSettingsSelectorViewModel(
    val syncPreferences: SyncPreferences,
) : StateViewModel<SyncSettingsSelectorViewModel.State>(
    State(syncOptionsToBackupOptions(syncPreferences.getSyncSettings())),
) {
    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            val updatedOptions = setter(it.options, enabled)
            syncPreferences.setSyncSettings(backupOptionsToSyncOptions(updatedOptions))
            it.copy(options = updatedOptions)
        }
    }

    fun syncNow(context: Context) {
        SyncDataJob.startNow(context)
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
    )

    companion object {
        private fun syncOptionsToBackupOptions(syncSettings: SyncSettings): BackupOptions {
            return BackupOptions(
                libraryEntries = syncSettings.libraryEntries,
                categories = syncSettings.categories,
                chapters = syncSettings.chapters,
                tracking = syncSettings.tracking,
                history = syncSettings.history,
                appSettings = syncSettings.appSettings,
                extensionStores = syncSettings.extensionStores,
                sourceSettings = syncSettings.sourceSettings,
                privateSettings = syncSettings.privateSettings,

                // SY -->
                customInfo = syncSettings.customInfo,
                readEntries = syncSettings.readEntries,
                savedSearchesFeeds = syncSettings.savedSearchesFeeds,
                // SY <--
            )
        }

        private fun backupOptionsToSyncOptions(backupOptions: BackupOptions): SyncSettings {
            return SyncSettings(
                libraryEntries = backupOptions.libraryEntries,
                categories = backupOptions.categories,
                chapters = backupOptions.chapters,
                tracking = backupOptions.tracking,
                history = backupOptions.history,
                appSettings = backupOptions.appSettings,
                extensionStores = backupOptions.extensionStores,
                sourceSettings = backupOptions.sourceSettings,
                privateSettings = backupOptions.privateSettings,

                // SY -->
                customInfo = backupOptions.customInfo,
                readEntries = backupOptions.readEntries,
                savedSearchesFeeds = backupOptions.savedSearchesFeeds,
                // SY <--
            )
        }
    }
}
