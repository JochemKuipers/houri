package eu.kanade.tachiyomi.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.ui.NavDisplay
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.model.NavigationRailAlignment
import eu.kanade.presentation.util.UiMotion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseSwitchToExtensionEventKey
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.library.LibraryTabSearchEventKey
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LibraryUpdateErrorRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.LocalTopLevelBackStack
import mihon.core.navigation.util.TabOptions
import mihon.presentation.core.util.isTabletUi
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.NavigationBar
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Serializable
sealed interface TopLevelRoute : NavKey {

    @Composable
    fun options(isSelected: Boolean): TabOptions

    // SY -->
    @Composable
    fun isEnabled(): Boolean = true
    // SY <--

    @Serializable
    data object Library : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object Updates : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

        // SY -->
        @Composable
        override fun isEnabled(): Boolean {
            val scope = rememberCoroutineScope()
            return remember {
                globalAppGraph.uiPreferences.showNavUpdates().asState(scope)
            }.value
        }
        // SY <--
    }

    @Serializable
    data object History : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

        // SY -->
        @Composable
        override fun isEnabled(): Boolean {
            val scope = rememberCoroutineScope()
            return remember {
                globalAppGraph.uiPreferences.showNavHistory().asState(scope)
            }.value
        }
        // SY <--
    }

    @Serializable
    data object Browse : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object More : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }
}

@Composable
fun HomeScreen() {
    val topLevelBackStack = LocalTopLevelBackStack.current
    val backStack = LocalBackStack.current
    val resultEventBus = LocalResultEventBus.current
    val tabletUi = isTabletUi()

    // SY -->
    val scope = rememberCoroutineScope()
    val alwaysShowLabel by remember {
        globalAppGraph.uiPreferences.bottomBarLabels().asState(scope)
    }
    // SY <--
    // KMK -->
    val railAlignment by remember {
        globalAppGraph.uiPreferences.railButtonAlignment().asState(scope)
    }

    // Replaces the Voyager-era showBottomNavEvent channel.
    var bottomNavVisible by remember { mutableStateOf(true) }
    ResultEffect<ShowBottomNavEvent> {
        bottomNavVisible = it.visible
    }
    // KMK <--

    Scaffold(
        startBar = {
            if (tabletUi) {
                // KMK -->
                val alignment = when (railAlignment) {
                    NavigationRailAlignment.TOP -> Alignment.Top
                    NavigationRailAlignment.BOTTOM -> Alignment.Bottom
                    NavigationRailAlignment.CENTER -> Alignment.CenterVertically
                }
                // KMK <--
                NavigationRail(alignment = alignment) {
                    TABS
                        // SY -->
                        .fastFilter { it.isEnabled() }
                        // SY <--
                        .fastForEach {
                            HomeNavigationRailItem(it/* SY --> */, alwaysShowLabel/* SY <-- */)
                        }
                }
            }
        },
        bottomBar = {
            if (!tabletUi) {
                AnimatedVisibility(
                    visible = bottomNavVisible,
                    // KMK -->
                    enter = expandVertically(tween(UiMotion.NAV_BAR_DURATION, easing = UiMotion.EMPHASIZED)),
                    exit = shrinkVertically(tween(UiMotion.NAV_BAR_DURATION, easing = UiMotion.EMPHASIZED)),
                    // KMK <--
                ) {
                    NavigationBar {
                        TABS
                            // SY -->
                            .fastFilter { it.isEnabled() }
                            // SY <--
                            .fastForEach {
                                HomeNavigationBarItem(it/* SY --> */, alwaysShowLabel/* SY <-- */)
                            }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding),
        ) {
            NavDisplay(
                backStack = topLevelBackStack.backStack,
                onBack = {
                    // KMK --> While a pushed route's transition still composes both displays,
                    // this inner handler outranks the outer one - a back press then belongs to
                    // the pushed route, not to the tab stack, mirroring the guard the Voyager
                    // TabNavigator had (`!navigator.canPop`).
                    if (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    } else if (topLevelBackStack.backStack.size > 1) {
                        // Same stale-enabled race MainActivity's outer handler guards against:
                        // this handler's own tab stack can already have been popped to its last
                        // tab by the previous press, and TopLevelBackStack's start key is the
                        // floor here. removeLast() re-checks the size itself.
                        topLevelBackStack.removeLast()
                    }
                    // KMK <--
                },
                transitionSpec = { tabContentSpec },
                popTransitionSpec = { tabContentSpec },
                predictivePopTransitionSpec = { tabContentSpec },
                entryProvider = entryProvider {
                    entry<TopLevelRoute.Library> {
                        LibraryTab()
                    }

                    entry<TopLevelRoute.Updates> {
                        UpdatesTab()
                    }

                    entry<TopLevelRoute.History> {
                        HistoryTab()
                    }

                    entry<TopLevelRoute.Browse> {
                        BrowseTab()
                    }

                    entry<TopLevelRoute.More> {
                        MoreTab()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    ResultEffect<String>(resultKey = LibrarySearchEventKey) {
        topLevelBackStack.setTopLevel(TopLevelRoute.Library)
        resultEventBus.sendResult(
            resultKey = LibraryTabSearchEventKey,
            result = it,
        )
    }

    ResultEffect<TabEvent> {
        val route = when (it) {
            is TabEvent.Library -> TopLevelRoute.Library
            TabEvent.Updates -> TopLevelRoute.Updates
            TabEvent.History -> TopLevelRoute.History
            is TabEvent.Browse -> {
                if (it.toExtensions) {
                    resultEventBus.sendResult(
                        resultKey = BrowseSwitchToExtensionEventKey,
                        result = Unit,
                    )
                }
                TopLevelRoute.Browse
            }
            is TabEvent.More -> TopLevelRoute.More
        }
        topLevelBackStack.setTopLevel(route)

        if (it is TabEvent.Library && it.mangaIdToOpen != null) {
            backStack.add(MangaRoute(it.mangaIdToOpen))
        }
        if (it is TabEvent.More) {
            if (it.toDownloads) {
                backStack.add(DownloadQueueRoute)
                // KMK -->
            } else if (it.toLibraryUpdateErrors) {
                backStack.add(LibraryUpdateErrorRoute)
                // KMK <--
            }
        }
    }
}

@Composable
private fun RowScope.HomeNavigationBarItem(
    tab: TopLevelRoute,
    // SY -->
    alwaysShowLabel: Boolean,
    // SY <--
) {
    val topLevelBackStack = LocalTopLevelBackStack.current
    val resultEventBus = LocalResultEventBus.current
    val selected = topLevelBackStack.topLevelKey == tab
    val lastClick = remember { longArrayOf(0L) }
    NavigationBarItem(
        selected = selected,
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastClick[0] < 160) return@NavigationBarItem
            lastClick[0] = now
            if (!selected) {
                topLevelBackStack.setTopLevel(tab)
            } else {
                resultEventBus.sendResult(
                    resultKey = TabReselectEventKey,
                    result = Unit,
                )
            }
        },
        icon = { NavigationIconItem(tab, selected) },
        label = {
            Text(
                text = tab.options(selected).title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        alwaysShowLabel = /* SY --> */alwaysShowLabel, /* SY <-- */
    )
}

@Composable
private fun HomeNavigationRailItem(
    tab: TopLevelRoute,
    // SY -->
    alwaysShowLabel: Boolean,
    // SY <--
) {
    val topLevelBackStack = LocalTopLevelBackStack.current
    val resultEventBus = LocalResultEventBus.current
    val selected = topLevelBackStack.topLevelKey == tab
    val lastClick = remember { longArrayOf(0L) }
    NavigationRailItem(
        selected = selected,
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastClick[0] < 160) return@NavigationRailItem
            lastClick[0] = now
            if (!selected) {
                topLevelBackStack.setTopLevel(tab)
            } else {
                resultEventBus.sendResult(
                    resultKey = TabReselectEventKey,
                    result = Unit,
                )
            }
        },
        icon = { NavigationIconItem(tab, selected) },
        label = {
            Text(
                text = tab.options(selected).title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        alwaysShowLabel = /* SY --> */alwaysShowLabel, /* SY <-- */
    )
}

@Composable
private fun NavigationIconItem(tab: TopLevelRoute, selected: Boolean) {
    val options = tab.options(selected)
    BadgedBox(
        badge = {
            when {
                tab is TopLevelRoute.Updates -> {
                    val count by produceState(initialValue = 0) {
                        val pref = globalAppGraph.libraryPreferences
                        combine(
                            pref.newShowUpdatesCount().changes(),
                            pref.newUpdatesCount().changes(),
                        ) { show, count -> if (show) count else 0 }
                            .collectLatest { value = it }
                    }
                    if (count > 0) {
                        Badge {
                            val desc = pluralStringResource(
                                MR.plurals.notification_chapters_generic,
                                count = count,
                                count,
                            )
                            Text(
                                text = count.toString(),
                                modifier = Modifier.semantics { contentDescription = desc },
                            )
                        }
                    }
                }
                tab is TopLevelRoute.Browse -> {
                    val count by produceState(initialValue = 0) {
                        globalAppGraph.sourcePreferences.extensionUpdatesCount().changes()
                            .collectLatest { value = it }
                    }
                    if (count > 0) {
                        Badge {
                            val desc = pluralStringResource(
                                MR.plurals.update_check_notification_ext_updates,
                                count = count,
                                count,
                            )
                            Text(
                                text = count.toString(),
                                modifier = Modifier.semantics { contentDescription = desc },
                            )
                        }
                    }
                }
            }
        },
    ) {
        Icon(
            painter = options.icon!!,
            contentDescription = options.title,
            // TODO: https://issuetracker.google.com/u/0/issues/316327367
            tint = LocalContentColor.current,
        )
    }
}

sealed interface TabEvent {
    data class Library(val mangaIdToOpen: Long? = null) : TabEvent
    data object Updates : TabEvent
    data object History : TabEvent
    data class Browse(val toExtensions: Boolean = false) : TabEvent
    data class More(
        val toDownloads: Boolean,
        // KMK -->
        val toLibraryUpdateErrors: Boolean = false,
        // KMK <--
    ) : TabEvent
}

@Suppress("ConstPropertyName")
const val TabReselectEventKey = "TabReselectEventKey"

@Suppress("ConstPropertyName")
const val LibrarySearchEventKey = "LibrarySearchEventKey"

data class ShowBottomNavEvent(val visible: Boolean)

// KMK --> Same motion design the Voyager TabNavigator used: fade-through with the fork's
// UiMotion duration.
private val tabContentSpec = materialFadeThroughIn(
    initialScale = 1f,
    durationMillis = UiMotion.TAB_DURATION,
) togetherWith materialFadeThroughOut(durationMillis = UiMotion.TAB_DURATION)
// KMK <--

// Home screen
private val TABS: List<TopLevelRoute> = listOf(
    TopLevelRoute.Library,
    TopLevelRoute.Updates,
    TopLevelRoute.History,
    TopLevelRoute.Browse,
    TopLevelRoute.More,
)
