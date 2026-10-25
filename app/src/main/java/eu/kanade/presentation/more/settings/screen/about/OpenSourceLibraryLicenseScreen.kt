package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.navigation3.runtime.NavKey
import com.google.android.material.textview.MaterialTextView
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Serializable
data class OpenSourceLibraryLicenseRoute(
    val name: String,
    val website: String?,
    val license: String,
) : NavKey

@Composable
fun OpenSourceLibraryLicenseScreen(name: String, website: String?, license: String) {
    val backStack = LocalBackStack.current
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            AppBar(
                title = name,
                navigateUp = backStack::removeLastOrNull,
                actions = {
                    if (!website.isNullOrEmpty()) {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.website),
                                    icon = Icons.Default.Public,
                                    onClick = { uriHandler.openUri(website) },
                                ),
                            ),
                        )
                    }
                },
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            HtmlLicenseText(html = license)
        }
    }
}

@Composable
private fun HtmlLicenseText(html: String) {
    AndroidView(
        factory = {
            MaterialTextView(it)
        },
        update = {
            it.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        },
    )
}
