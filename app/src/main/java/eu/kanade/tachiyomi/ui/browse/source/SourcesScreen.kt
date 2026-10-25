package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Composable
import eu.kanade.presentation.browse.BrowseTabWrapper
import mihon.core.navigation.util.LocalBackStack
import java.io.Serializable

/**
 * Shown when picking a source to "find in another source" (smart search) - from a manga's
 * merge menu or from the recommends screens. Renders a [sourcesTab] inside a
 * [BrowseTabWrapper] so it looks like a plain browsing screen.
 */
@Composable
fun SourcesSearchScreen(smartSearchConfig: SmartSearchConfig?) {
    val backStack = LocalBackStack.current
    BrowseTabWrapper(sourcesTab(smartSearchConfig), onBackPressed = { backStack.removeLastOrNull() })
}

/**
 * Initialized when invoking smart search or from the recommends screens.
 */
data class SmartSearchConfig(val origTitle: String, val origMangaId: Long? = null) : Serializable
