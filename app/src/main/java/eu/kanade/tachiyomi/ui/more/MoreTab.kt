package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.home.TabEvent
import eu.kanade.tachiyomi.ui.home.TabReselectEventKey
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import mihon.core.navigation.BatchAddRoute
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LibraryUpdateErrorRoute
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.StatsRoute
import mihon.core.navigation.domain.SettingsDestination
import mihon.core.navigation.util.LocalBackStack
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.util.lang.launchIO

@Composable
fun MoreTab() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val resultEventBus = LocalResultEventBus.current
    val isTabletUi = isTabletUi()
    val viewModel = metroViewModel<MoreViewModel>()
    val downloadQueueState by viewModel.downloadQueueState.collectAsState()
    MoreScreen(
        downloadQueueStateProvider = { downloadQueueState },
        downloadedOnly = viewModel.downloadedOnly,
        onDownloadedOnlyChange = { viewModel.downloadedOnly = it },
        incognitoMode = viewModel.incognitoMode,
        onIncognitoModeChange = { viewModel.incognitoMode = it },
        // SY -->
        showNavUpdates = viewModel.showNavUpdates,
        showNavHistory = viewModel.showNavHistory,
        // SY <--
        onClickDownloadQueue = { backStack.add(DownloadQueueRoute) },
        onClickCategories = { backStack.add(CategoryRoute) },
        onClickStats = { backStack.add(StatsRoute) },
        onClickDataAndStorage = {
            backStack.addSettingsRoute(SettingsRoute(SettingsDestination.DataAndStorage), isTabletUi)
        },
        onClickSettings = { backStack.addSettingsRoute(SettingsRoute(), isTabletUi) },
        onClickAbout = { backStack.addSettingsRoute(SettingsRoute(SettingsDestination.About), isTabletUi) },
        // SY -->
        onClickBatchAdd = { backStack.add(BatchAddRoute) },
        onClickUpdates = { resultEventBus.sendResult(TabEvent.Updates) },
        onClickHistory = { resultEventBus.sendResult(TabEvent.History) },
        // SY <--
        // KMK -->
        onClickLibraryUpdateErrors = { backStack.add(LibraryUpdateErrorRoute) },
        // KMK <--
    )

    ResultEffect<Unit>(resultKey = TabReselectEventKey) {
        backStack.addSettingsRoute(SettingsRoute(), isTabletUi)
    }

    // AM (DISCORD) -->
    LaunchedEffect(Unit) {
        with(DiscordRPCService) {
            discordScope.launchIO { setScreen(context, DiscordScreen.MORE) }
        }
    }
    // <-- AM (DISCORD)
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MoreViewModel(
    private val downloadManager: DownloadManager,
    preferences: BasePreferences,
    // SY -->
    uiPreferences: UiPreferences,
    // SY <--
) : ViewModel() {

    var downloadedOnly by preferences.downloadedOnly().asState(viewModelScope)
    var incognitoMode by preferences.incognitoMode().asState(viewModelScope)

    // SY -->
    val showNavUpdates by uiPreferences.showNavUpdates().asState(viewModelScope)
    val showNavHistory by uiPreferences.showNavHistory().asState(viewModelScope)
    // SY <--

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    init {
        // Handle running/paused status change and queue progress updating
        viewModelScope.launchIO {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
            ) { isRunning, downloadQueue -> Pair(isRunning, downloadQueue.size) }
                .collectLatest { (isDownloading, downloadQueueSize) ->
                    val pendingDownloadExists = downloadQueueSize != 0
                    _downloadQueueState.value = when {
                        !pendingDownloadExists -> DownloadQueueState.Stopped
                        !isDownloading -> DownloadQueueState.Paused(downloadQueueSize)
                        else -> DownloadQueueState.Downloading(downloadQueueSize)
                    }
                }
        }
    }
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}
