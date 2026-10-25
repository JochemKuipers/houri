package eu.kanade.tachiyomi.ui.browse

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.feed.AnizenMultiFeedViewModel
import eu.kanade.tachiyomi.ui.browse.feed.FeedViewModel
import eu.kanade.tachiyomi.ui.browse.feed.feedTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.home.TabReselectEventKey
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.collections.immutable.persistentListOf
import mihon.app.di.globalAppGraph
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BrowseTab() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    // SY -->
    val hideFeedTab by remember { globalAppGraph.uiPreferences.hideFeedTab().asState(scope) }
    val feedTabInFront by remember { globalAppGraph.uiPreferences.feedTabInFront().asState(scope) }
    // SY <--

    // Hoisted for extensions tab's search bar
    val extensionsViewModel = metroViewModel<ExtensionsViewModel>()
    val extensionsState by extensionsViewModel.state.collectAsState()

    // KMK -->
    val feedViewModel = metroViewModel<FeedViewModel>()
    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    val anizenMultiFeedViewModel = metroViewModel<AnizenMultiFeedViewModel>()
    // KMK <--

    // SY -->
    val tabs = when {
        hideFeedTab ->
            persistentListOf(
                sourcesTab(),
                extensionsTab(extensionsViewModel),
                migrateSourceTab(),
            )

        feedTabInFront ->
            persistentListOf(
                feedTab(
                    // KMK -->
                    feedViewModel,
                    bulkFavoriteViewModel,
                    anizenMultiFeedViewModel,
                    // KMK <--
                ),
                sourcesTab(),
                extensionsTab(extensionsViewModel),
                migrateSourceTab(),
            )

        else ->
            persistentListOf(
                sourcesTab(),
                feedTab(
                    // KMK -->
                    feedViewModel,
                    bulkFavoriteViewModel,
                    anizenMultiFeedViewModel,
                    // KMK <--
                ),
                extensionsTab(extensionsViewModel),
                migrateSourceTab(),
            )
    }
    // SY <--

    val state = rememberPagerState { tabs.size }

    // KMK --> Multi-feed merged into the feed tab: header is shorter, so the
    // extensions index depends on the visible tabs.
    val extensionsIndex = if (hideFeedTab) 1 else 2

    TabbedScreen(
        titleRes = MR.strings.browse,
        tabs = tabs,
        state = state,
        searchQuery = extensionsState.searchQuery,
        onChangeSearchQuery = extensionsViewModel::search,
        // KMK -->
        feedScreenModel = feedViewModel,
        bulkFavoriteScreenModel = bulkFavoriteViewModel,
        // KMK <--
    )

    // Replaces the Voyager-era switchToExtensionTab channel; HomeScreen sends this event when
    // the Browse tab is opened with the extensions shortcut.
    ResultEffect<Unit>(resultKey = BrowseSwitchToExtensionEventKey) {
        state.scrollToPage(extensionsIndex)
    }

    // Voyager reselect (BrowseTab.onReselect) opened the global search screen
    ResultEffect<Unit>(resultKey = TabReselectEventKey) {
        backStack.add(GlobalSearchRoute())
    }

    LaunchedEffect(Unit) {
        (context as? MainActivity)?.ready = true

        // AM (DISCORD) -->
        with(DiscordRPCService) {
            discordScope.launchIO { setScreen(context, DiscordScreen.BROWSE) }
        }
        // <-- AM (DISCORD)
    }
}

@Suppress("ConstPropertyName")
const val BrowseSwitchToExtensionEventKey = "BrowseSwitchToExtensionEventKey"
