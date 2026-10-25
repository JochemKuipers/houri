package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CreateBackupScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val model = metroViewModel<CreateBackupViewModel>()
    val state by model.state.collectAsState()

    val chooseBackupDir = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/*"),
    ) {
        if (it != null) {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            model.createBackup(context, it)
            backStack.removeLastOrNull()
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.pref_create_backup),
                navigateUp = backStack::removeLastOrNull,
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        LazyColumnWithAction(
            contentPadding = contentPadding,
            actionLabel = stringResource(MR.strings.action_create),
            actionEnabled = state.options.canCreate(),
            onClickAction = {
                if (!BackupCreateJob.isManualJobRunning(context)) {
                    try {
                        chooseBackupDir.launch(BackupCreator.getFilename())
                    } catch (e: ActivityNotFoundException) {
                        context.toast(MR.strings.file_picker_error)
                    }
                } else {
                    context.toast(MR.strings.backup_in_progress)
                }
            },
        ) {
            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                item {
                    WarningBanner(MR.strings.restore_miui_warning)
                }
            }

            item {
                SectionCard(MR.strings.label_library) {
                    Options(BackupOptions.libraryOptions, state, model)
                }
            }

            // KMK -->
            if (state.options.libraryEntries && state.allCategories.isNotEmpty()) {
                item {
                    SectionCard(KMR.strings.backup_included_categories) {
                        state.allCategories.forEach { category ->
                            LabeledCheckbox(
                                label = category.name,
                                checked = state.options.includedCategoryIds?.contains(category.id) ?: true,
                                onCheckedChange = { model.toggleCategory(category.id, it) },
                            )
                        }
                    }
                }
            }
            // KMK <--

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
    state: CreateBackupViewModel.State,
    model: CreateBackupViewModel,
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
class CreateBackupViewModel(
    // KMK -->
    private val getCategories: GetCategories,
    // KMK <--
) : StateViewModel<CreateBackupViewModel.State>(State()) {

    // KMK -->
    init {
        viewModelScope.launch {
            getCategories.subscribe()
                .map { categories -> categories.filterNot { it.isSystemCategory }.toImmutableList() }
                .collect { categories -> mutableState.update { it.copy(allCategories = categories) } }
        }
    }
    // KMK <--

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        mutableState.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    // KMK -->
    fun toggleCategory(categoryId: Long, enabled: Boolean) {
        mutableState.update { state ->
            val allIds = state.allCategories.map { it.id }.toSet()
            val current = state.options.includedCategoryIds ?: allIds
            val updated = if (enabled) current + categoryId else current - categoryId
            state.copy(
                options = state.options.copy(
                    includedCategoryIds = updated.takeUnless { it == allIds },
                ),
            )
        }
    }
    // KMK <--

    fun createBackup(context: Context, uri: Uri) {
        BackupCreateJob.startNow(context, uri, state.value.options)
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
        // KMK -->
        val allCategories: ImmutableList<Category> = persistentListOf(),
        // KMK <--
    )
}
