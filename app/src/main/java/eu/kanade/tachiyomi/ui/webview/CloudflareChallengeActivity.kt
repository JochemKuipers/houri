package eu.kanade.tachiyomi.ui.webview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.network.interceptor.CloudflareChallenge
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.delay
import mihon.app.di.globalAppGraph
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The visible Cloudflare challenge (issue #79).
 *
 * A popup-sized activity that loads the page the interceptor was blocked on, using the same user
 * agent and headers as the original request — Cloudflare ties `cf_clearance` to both, so anything
 * else would solve a challenge the app cannot use. When the cookie arrives the activity closes and
 * the parked request resumes on its own; closing it without solving fails that request instead of
 * silently retrying it where the user cannot see.
 *
 * The window is translucent and the page sits in a card, so this reads as a popup over whatever
 * screen the user was on rather than as a screen of its own.
 */
class CloudflareChallengeActivity : BaseActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = CloudflareChallenge.current()
        if (session == null || !WebViewUtil.supportsWebView(this)) {
            CloudflareChallenge.finish(false)
            finish()
            return
        }

        val page = WebView(this).apply {
            setDefaultSettings()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Empty would make Chromium fall back to its own agent, which does not match the
            // okhttp one the clearance cookie is issued for.
            session.headers["User-Agent"]?.takeIf { it.isNotBlank() }?.let {
                settings.userAgentString = it
            }
            webViewClient = WebViewClient()
        }
        webView = page
        page.loadUrl(session.url, session.headers)

        setComposeContent {
            CloudflareChallengeContent(
                webView = page,
                onClose = { finish() },
            )
        }

        lifecycleScope.launchIO {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (hasClearance(session.url)) {
                    CloudflareChallenge.finish(true)
                    withUIContext { finish() }
                    return@launchIO
                }
                delay(POLL_INTERVAL_MS)
            }
            CloudflareChallenge.finish(false)
            withUIContext { finish() }
        }
    }

    override fun onDestroy() {
        // Back, the close button and the window being killed all pass through here. No-op when
        // the cookie already resolved the challenge.
        CloudflareChallenge.finish(false)
        webView?.let { page ->
            (page.parent as? ViewGroup)?.removeView(page)
            page.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun hasClearance(url: String): Boolean = runCatching {
        globalAppGraph.networkHelper.cookieJar.get(url.toHttpUrl())
            .any { it.name == "cf_clearance" }
    }.getOrDefault(false)

    companion object {
        private const val TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_MS = 400L

        fun newIntent(context: Context): Intent {
            return Intent(context, CloudflareChallengeActivity::class.java).apply {
                // Started from an OkHttp thread against the application context.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

@Composable
private fun CloudflareChallengeContent(
    webView: WebView,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(KMR.strings.cloudflare_challenge_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(KMR.strings.cloudflare_challenge_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(MR.strings.action_cancel),
                        )
                    }
                }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
