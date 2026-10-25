package eu.kanade.tachiyomi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.SettingsAchievementsRoute
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedRoute
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceRoute
import eu.kanade.presentation.more.settings.screen.SettingsBookReadingRoute
import eu.kanade.presentation.more.settings.screen.SettingsBrowseRoute
import eu.kanade.presentation.more.settings.screen.SettingsConnectionRoute
import eu.kanade.presentation.more.settings.screen.SettingsDataRoute
import eu.kanade.presentation.more.settings.screen.SettingsDownloadRoute
import eu.kanade.presentation.more.settings.screen.SettingsEhRoute
import eu.kanade.presentation.more.settings.screen.SettingsLibraryRoute
import eu.kanade.presentation.more.settings.screen.SettingsMangadexRoute
import eu.kanade.presentation.more.settings.screen.SettingsReaderRoute
import eu.kanade.presentation.more.settings.screen.SettingsSecurityRoute
import eu.kanade.presentation.more.settings.screen.SettingsTrackingRoute
import eu.kanade.presentation.more.settings.screen.SettingsUpscalerRoute
import eu.kanade.presentation.more.settings.screen.SettingsYakuyomiRoute
import eu.kanade.presentation.more.settings.screen.about.getVersionName
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import exh.assets.EhAssets
import exh.assets.ehassets.EhLogo
import exh.assets.ehassets.MangadexLogo
import kotlinx.collections.immutable.persistentListOf
import mihon.core.navigation.AboutRoute
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.SettingsSearchRoute
import mihon.core.navigation.domain.SettingsDestination
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.replace
import mihon.presentation.core.util.isTabletUi
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

// KMK --> Replaces the Voyager-era nested Navigator wrapper: with Navigation 3 the settings
// screens live on the main back stack, and the TwoPaneSettingsScene renders this screen as
// the list pane next to whichever settings route is on top on tablets.
fun NavBackStack<NavKey>.addSettingsRoute(settingsRoute: SettingsRoute, isTabletUi: Boolean) {
    val route = when (settingsRoute.settingsDestination) {
        SettingsDestination.About -> AboutRoute
        SettingsDestination.DataAndStorage -> SettingsDataRoute
        SettingsDestination.Tracking -> SettingsTrackingRoute
        null if isTabletUi -> SettingsAppearanceRoute
        null -> null
    }

    if (route != null) {
        if (isTabletUi) {
            add(SettingsRoute())
        }
        add(route)
    } else {
        add(SettingsRoute())
    }
}

// KMK <-- A settings row that is a whole sub-hub keeps its own back-press handling, so route
// navigation between them goes through the same throttle the old nested navigator had.
private fun NavBackStack<NavKey>.navigate(route: NavKey, twoPane: Boolean) {
    if (any { it == route }) return
    if (twoPane) replace(route) else add(route)
}

@Composable
fun SettingsScreen() {
    val backStack = LocalBackStack.current
    val isTabletUi = isTabletUi()

    val containerColor = if (isTabletUi) getPalerSurface() else MaterialTheme.colorScheme.surface
    val topBarState = rememberTopAppBarState()

    Scaffold(
        topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_settings),
                navigateUp = {
                    if (isTabletUi) backStack.removeLastOrNull()
                    backStack.removeLastOrNull()
                },
                actions = {
                    AppBarActions(
                        persistentListOf<AppBar.AppBarAction>(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_search),
                                icon = Icons.Outlined.Search,
                                onClick = { backStack.navigate(SettingsSearchRoute, isTabletUi) },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = containerColor,
        content = { contentPadding ->
            val state = rememberLazyListState()
            // SY -->
            val items = items.filter { it.route !is SearchableSettings || (it.route as SearchableSettings).isEnabled() }
            // SY <--
            val indexSelected = if (isTabletUi) {
                items.indexOfFirst { it.route == backStack.lastOrNull() }
                    .takeIf { it > -1 } // Don't trigger if route for right side hasn't been added yet
                    ?.also {
                        LaunchedEffect(Unit) {
                            state.animateScrollToItem(it)
                            if (it > 0) {
                                // Lift scroll
                                topBarState.contentOffset = topBarState.heightOffsetLimit
                            }
                        }
                    }
            } else {
                null
            }

            LazyColumn(
                state = state,
                contentPadding = contentPadding,
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.route.hashCode() },
                ) { index, item ->
                    val selected = indexSelected == index
                    var modifier: Modifier = Modifier
                    var contentColor = LocalContentColor.current
                    if (isTabletUi) {
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .then(
                                if (selected) {
                                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                } else {
                                    Modifier
                                },
                            )
                        if (selected) {
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    }
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        TextPreferenceWidget(
                            modifier = modifier,
                            title = stringResource(item.titleRes),
                            subtitle = item.formatSubtitle(),
                            icon = item.icon,
                            onPreferenceClick = { backStack.navigate(item.route, isTabletUi) },
                        )
                    }
                }
            }
        },
    )
}

// KMK --> Upstream keeps this private inside their SettingsScreen; ours is shared with the
// settings-search path so both panes agree on the list color.
@Composable
internal fun getPalerSurface(): Color {
    val surface = MaterialTheme.colorScheme.surface
    val dark = isSystemInDarkTheme()
    return remember(surface, dark) {
        val arr = FloatArray(3)
        ColorUtils.colorToHSL(surface.toArgb(), arr)
        arr[2] = if (dark) {
            arr[2] - 0.05f
        } else {
            arr[2] + 0.02f
        }.coerceIn(0f, 1f)
        Color.hsl(arr[0], arr[1], arr[2])
    }
}
// KMK <--

private data class Item(
    val titleRes: StringResource,
    val subtitleRes: StringResource,
    val formatSubtitle: @Composable () -> String = { stringResource(subtitleRes) },
    val icon: ImageVector,
    val route: NavKey,
    // KMK -->
    /** Hidden on the no-MTL build, which ships without the AI engine behind this screen. */
    val mtlOnly: Boolean = false,
    // KMK <--
)

private val items = listOfNotNull(
    Item(
        titleRes = MR.strings.pref_category_appearance,
        subtitleRes = MR.strings.pref_appearance_summary,
        icon = Icons.Outlined.Palette,
        route = SettingsAppearanceRoute,
    ),
    Item(
        titleRes = MR.strings.pref_category_library,
        subtitleRes = MR.strings.pref_library_summary,
        icon = Icons.Outlined.CollectionsBookmark,
        route = SettingsLibraryRoute,
    ),
    Item(
        titleRes = MR.strings.pref_category_reader,
        subtitleRes = MR.strings.pref_reader_summary,
        icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
        route = SettingsReaderRoute,
    ),
    Item(
        titleRes = MR.strings.pref_category_downloads,
        subtitleRes = MR.strings.pref_downloads_summary,
        icon = Icons.Outlined.GetApp,
        route = SettingsDownloadRoute,
    ),
    // KMK -->
    Item(
        titleRes = KMR.strings.pref_category_book_reading,
        subtitleRes = KMR.strings.pref_book_reading_summary,
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        route = SettingsBookReadingRoute,
    ),
    // KMK <--
    Item(
        titleRes = MR.strings.pref_category_tracking,
        subtitleRes = MR.strings.pref_tracking_summary,
        icon = Icons.Outlined.Sync,
        route = SettingsTrackingRoute,
    ),
    // AM (CONNECTIONS) -->
    Item(
        titleRes = KMR.strings.pref_category_connections,
        subtitleRes = KMR.strings.pref_connections_summary,
        icon = Icons.Outlined.Link,
        route = SettingsConnectionRoute,
    ),
    // <-- AM (CONNECTIONS)
    Item(
        titleRes = MR.strings.browse,
        subtitleRes = MR.strings.pref_browse_summary,
        icon = Icons.Outlined.Explore,
        route = SettingsBrowseRoute,
    ),
    Item(
        titleRes = MR.strings.label_data_storage,
        subtitleRes = MR.strings.pref_backup_summary,
        icon = Icons.Outlined.Storage,
        route = SettingsDataRoute,
    ),
    Item(
        titleRes = MR.strings.pref_category_security,
        subtitleRes = MR.strings.pref_security_summary,
        icon = Icons.Outlined.Security,
        route = SettingsSecurityRoute,
    ),
    // SY -->
    Item(
        titleRes = SYMR.strings.pref_category_eh,
        subtitleRes = SYMR.strings.pref_ehentai_summary,
        icon = EhAssets.EhLogo,
        route = SettingsEhRoute,
    ),
    Item(
        titleRes = SYMR.strings.pref_category_mangadex,
        subtitleRes = SYMR.strings.pref_mangadex_summary,
        icon = EhAssets.MangadexLogo,
        route = SettingsMangadexRoute,
    ),
    // SY <--
    // KMK --> AI feature categories. Off-device MTL needs no local engine, but the no-MTL
    // build is meant to be the one without AI features at all, so they are hidden there.
    // The upscaler is the exception: no-MTL builds force Simple mode (no MTL runtime, no
    // model weights) via UpscalePreferences.isSimpleMode, and the screen drops its
    // native-only rows itself — plus UpscaleMangaToggle already shows the per-manga toggle
    // on that flavor, which is unreachable if the category it depends on is hidden.
    Item(
        titleRes = KMR.strings.pref_yakuyomi_enabled,
        subtitleRes = KMR.strings.pref_yakuyomi_enabled_summary,
        icon = Icons.Outlined.Language,
        route = SettingsYakuyomiRoute,
        mtlOnly = true,
    ),
    Item(
        titleRes = KMR.strings.pref_upscale_title,
        subtitleRes = KMR.strings.pref_upscale_enabled_summary,
        icon = Icons.Outlined.AutoFixHigh,
        route = SettingsUpscalerRoute,
    ),
    Item(
        titleRes = KMR.strings.label_achievements,
        subtitleRes = KMR.strings.achievements_unlocked_count,
        formatSubtitle = {
            val prefs = mihon.app.di.globalAppGraph.achievementPreferences
            val unlocked = try {
                prefs.getUnlockedIds().size
            } catch (_: Exception) {
                0
            }
            val total = tachiyomi.domain.achievement.model.Achievements.all.size
            androidx.compose.runtime.remember(unlocked) { "$unlocked / $total unlocked" }
        },
        icon = Icons.Outlined.Star,
        route = SettingsAchievementsRoute,
    ),
    // KMK <--
    Item(
        titleRes = MR.strings.pref_category_advanced,
        subtitleRes = MR.strings.pref_advanced_summary,
        icon = Icons.Outlined.Code,
        route = SettingsAdvancedRoute,
    ),
    Item(
        titleRes = MR.strings.pref_category_about,
        subtitleRes = StringResource(0),
        formatSubtitle = {
            "${stringResource(MR.strings.app_name)} ${getVersionName(withBuildDate = false)}"
        },
        icon = Icons.Outlined.Info,
        route = AboutRoute,
    ),
    // KMK -->
).filterNot { it.mtlOnly && BuildConfig.IS_NOMTL }
// KMK <--
