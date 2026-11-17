package eu.kanade.tachiyomi.data.track.animeplanet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Headless WebView-based HTTP client that can bypass Cloudflare challenges.
 *
 * Cloudflare's [cf_clearance] cookie is tied to the browser fingerprint
 * (TLS + User-Agent), so OkHttp requests fail even with valid cookies.
 * The WebView solves CF challenges natively via JS execution, making it
 * the only reliable way to fetch pages behind CF protection.
 *
 * Cookies are managed by the shared [android.webkit.CookieManager], so
 * cookies set during the login WebView flow are automatically available.
 */
class AnimePlanetWebClient(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fetch the full HTML of [url] using a headless WebView.
     * Waits for the page to fully load (including CF challenge resolution).
     * Timeout: [timeoutMs] (default 20 s).
     */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 20_000): String {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val createAndLoad = Runnable {
                    val webView = WebView(appContext).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                        }
                    }

                    var loadCount = 0

                    var done = false

                    // Pending delayed runnables that need to be cancelled on cleanup
                    var pendingExtractRunnable: Runnable? = null

                    fun destroyWebView() {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Exception) {
                            // WebView may already be in a bad state
                        }
                    }

                    fun finish(result: (() -> Unit)?) {
                        if (done) return
                        done = true
                        // Cancel any pending delayed extract runnable
                        pendingExtractRunnable?.let { mainHandler.removeCallbacks(it) }
                        mainHandler.post {
                            try {
                                result?.invoke()
                            } finally {
                                destroyWebView()
                            }
                        }
                    }

                    cont.invokeOnCancellation {
                        finish(null)
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (done) return
                            loadCount++
                            // First load: wait a bit for CF challenge JS to execute.
                            // Subsequent loads (CF redirects): shorter wait.
                            val delay = if (loadCount == 1) 3_000L else 1_000L
                            val extractRunnable = Runnable {
                                if (done) return@Runnable
                                pollExtract(view)
                            }
                            pendingExtractRunnable = extractRunnable
                            mainHandler.postDelayed(extractRunnable, delay)
                        }

                        // KMK --> 2026-11: anime-planet.com now fronts the search page with a
                        // Cloudflare managed challenge (verified live: plain HTTP is challenged,
                        // a real browser engine passes). The fixed 3s wait used to extract the
                        // challenge page itself — "Just a moment…" — and the search parsed zero
                        // cards from it, so queries returned "no results" while the challenge
                        // was still clearing (or the challenge's reload loop ran the caller's
                        // 20s timeout out to an error). A challenge page is never the answer:
                        // poll until the cleared-challenge reload delivers the real document.
                        // The caller's withTimeoutOrNull is the hard bound — its cancellation
                        // path finishes and destroys this WebView either way.
                        fun pollExtract(view: WebView) {
                            try {
                                view.evaluateJavascript(SCRIPT_EXTRACT_HTML) { html ->
                                    val decoded = decodeJsString(html)
                                    if (!done && isChallengePage(decoded)) {
                                        val rearm = Runnable { if (!done) pollExtract(view) }
                                        pendingExtractRunnable = rearm
                                        mainHandler.postDelayed(rearm, 1_500L)
                                        return@evaluateJavascript
                                    }
                                    finish {
                                        cont.resume(decoded)
                                    }
                                }
                            } catch (_: Exception) {
                                // WebView may have been destroyed between the delay and now
                                finish(null)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            // Only handle main-frame errors
                            if (request.isForMainFrame && !done) {
                                finish {
                                    cont.resumeWithException(
                                        IOException("WebView error: ${error.description}"),
                                    )
                                }
                            }
                        }
                    }

                    webView.loadUrl(url)
                }

                mainHandler.post(createAndLoad)
            }
        } ?: throw IOException(
            // Shown verbatim in the tracker search's error screen; name the likely cause
            // instead of the implementation detail.
            "Anime-Planet did not respond in time — the site's bot protection may be " +
                "challenging this request. Try again, and re-login from tracker settings if it keeps failing.",
        )
    }

    /**
     * Fire-and-forget POST via the WebView.
     * Loads [url] with the given form-encoded [body] and waits for the page to settle.
     * Returns the final URL (to detect redirects) or null on timeout.
     */
    suspend fun postForm(url: String, body: String): String? {
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val createAndPost = Runnable {
                    val webView = WebView(appContext).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                        }
                    }

                    var done = false
                    var pendingRunnable: Runnable? = null

                    fun destroyWebView() {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Exception) {
                            // WebView may already be in a bad state
                        }
                    }

                    fun finish(result: (() -> Unit)?) {
                        if (done) return
                        done = true
                        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
                        mainHandler.post {
                            try {
                                result?.invoke()
                            } finally {
                                destroyWebView()
                            }
                        }
                    }

                    cont.invokeOnCancellation { finish(null) }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (done) return
                            val runnable = Runnable {
                                finish { cont.resume(url) }
                            }
                            pendingRunnable = runnable
                            mainHandler.postDelayed(runnable, 1_000L)
                        }
                    }

                    webView.postUrl(url, body.toByteArray())
                }

                mainHandler.post(createAndPost)
            }
        }
    }

    companion object {
        /** JS snippet that returns the outer HTML of the document. */
        private const val SCRIPT_EXTRACT_HTML = "document.documentElement.outerHTML"

        /**
         * Markers of a Cloudflare interstitial ("Just a moment…", the managed-challenge
         * script bundle, the Turnstile prompt). Checked on every poll of the extracted
         * document so a page the challenge has not cleared yet is never handed to the
         * parser as if it were the answer.
         */
        private val CHALLENGE_MARKERS = listOf(
            "Just a moment",
            "challenge-platform",
            "_cf_chl_opt",
            "cf-chl",
            "Checking if the site connection is secure",
            "Verify you are human",
        )

        fun isChallengePage(html: String): Boolean = CHALLENGE_MARKERS.any { html.contains(it) }

        /**
         * Decode a JSON-encoded JS string returned by [WebView.evaluateJavascript].
         * Strips surrounding quotes and un-escapes common sequences.
         */
        fun decodeJsString(raw: String?): String {
            if (raw == null || raw == "null") return ""
            return raw
                .removeSurrounding("\"")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0022", "\"")
                .replace("\\u0026", "&")
                .replace("\\u0027", "'")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
    }
}
