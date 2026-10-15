package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerProvider
import exh.log.xLogE
import mihon.app.di.globalAppGraph
import tachiyomi.source.epub.EpubResourceReader

/**
 * Renders a text chapter in a WebView.
 *
 * ### Why a WebView
 *
 * An EPUB is an XHTML document carrying the publisher's own stylesheet — drop caps, ruby
 * annotations, columns, vertical writing, tables, footnotes. Re-implementing that in Compose means
 * re-implementing a browser, badly. So the document keeps its own markup and the reader's
 * preferences are layered on as an injected stylesheet (see [NovelTypographyCss]).
 *
 * The corollary: layout is the engine's business. What this viewer owns is which document is
 * showing, what the user's typography is, and where in the document they are.
 *
 * ### Resource resolution
 *
 * Documents load under [EpubResourceReader.BASE_URL] — a reserved `.internal` scheme that cannot
 * collide with a real host — and requests to it are intercepted and streamed out of the archive.
 * That is what makes `<img src="../images/plate.jpg">` resolve inside a zip file. Requests to any
 * other host are deliberately **not** intercepted, so an absolutely-linked image or an external
 * stylesheet still loads normally.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
@SuppressLint("SetJavaScriptEnabled")
class NovelWebViewer(
    private val activity: ReaderActivity,
    private val resources: TextResourceSource?,
) : Viewer {

    override val isVertical: Boolean get() = true

    override val currentReaderPage: ReaderPage? get() = currentPage

    private var chapters: ViewerChapters? = null
    private var currentPage: ReaderPage? = null

    /** Typography, republished from settings. Changing it re-applies in place, no reload. */
    var typography by mutableStateOf(NovelTypography.DEFAULT)

    /** Scroll fraction to restore once the next document has finished laying out. */
    private var pendingRestoreFraction: Float? = null

    /**
     * Reader-controlled background, applied to the WebView itself as well as the document.
     *
     * A WebView whose content is black but whose overscroll glow is white reads as a rendering bug,
     * so this goes on both.
     */
    private val backgroundColor: Int
        get() = typography.backgroundColor?.toColorIntOrNull() ?: Color.TRANSPARENT

    private val webView: WebView = WebView(activity).apply {
        setBackgroundColor(backgroundColor)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            // Needed only to read the scroll position for progress; nothing else uses JS here.
            javaScriptEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        webViewClient = ArchiveWebViewClient()
    }

    override fun getView(): View = webView

    override fun setChapters(chapters: ViewerChapters) {
        this.chapters = chapters
        chapters.currChapter.pages?.firstOrNull()?.let(::moveToPage)
    }

    override fun moveToPage(page: ReaderPage) {
        val textPage = page.textPage ?: return
        currentPage = page

        val html = NovelTypographyCss.applyTo(textPage.text, typography)
        // baseUrl is what every relative src/href resolves against. Without it the engine resolves
        // against the app's own origin and every image 404s.
        webView.loadDataWithBaseURL(
            EpubResourceReader.BASE_URL,
            html,
            "text/html",
            "UTF-8",
            null,
        )

        pendingRestoreFraction = null
        activity.onPageSelected(page)
    }

    /**
     * Re-applies typography to the document already on screen.
     *
     * Reloading would throw away the scroll position on every slider drag, so this swaps the
     * stylesheet in the live DOM instead of calling [moveToPage] again.
     */
    fun applyTypography() {
        webView.setBackgroundColor(backgroundColor)
        webView.evaluateJavascript(liveTypographyJs(), null)
        webView.invalidate()
    }

    /** Scrolls one screen down, for auto-scroll and for a keyboard page-down. */
    override fun moveToNext() {
        webView.evaluateJavascript(SCROLL_BY_JS, null)
    }

    override fun destroy() {
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = false

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * Serves archive-internal URLs out of the open book and lets everything else through.
     *
     * Returning null for a non-archive URL is load-bearing: it is what stops this class breaking a
     * book that links to an image on a real web server.
     */
    private inner class ArchiveWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val source = resources ?: return null
            val url = request.url.toString()
            if (EpubResourceReader.isArchiveUrl(url).not()) return null

            val stream = runCatching { source.openResource(url) }
                .onFailure { xLogE("Failed to open archive resource: $url", it) }
                .getOrNull() ?: return null

            val mime = source.contentTypeFor(url) ?: EpubResourceReader.contentTypeForPath(url) ?: "application/octet-stream"
            // Text responses must declare an encoding or the engine guesses and mangles CJK.
            val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) {
                "UTF-8"
            } else {
                null
            }
            return WebResourceResponse(mime, encoding, stream)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Re-assert typography in the live DOM too, so the inline stylesheet and the running
            // document cannot drift apart after a self-navigation.
            view.evaluateJavascript(liveTypographyJs(), null)
        }
    }

    /**
     * The current typography as a script that installs it into the running document.
     *
     * Built as a JS string literal rather than concatenated into markup so a font name containing a
     * quote cannot break out of the script — the user chooses that font.
     */
    private fun liveTypographyJs(): String {
        val css = NovelTypographyCss.stylesheet(typography)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        return """
            (function(){
              var old = document.getElementById('kmk-typography-live');
              if (old && old.parentNode) old.parentNode.removeChild(old);
              var s = document.createElement('style');
              s.id = 'kmk-typography-live';
              s.textContent = '$css';
              if (document.head) document.head.appendChild(s);
            })();
        """.trimIndent()
    }

    private companion object {
        val SCROLL_BY_JS = "window.scrollBy({ top: window.innerHeight * 0.9, behavior: 'smooth' });"
    }
}

/** `#rrggbb` / `#rgb` to a packed colour int, or null when it is not a hex colour. */
internal fun String.toColorIntOrNull(): Int? {
    val hex = trim().removePrefix("#")
    if (hex.length != 3 && hex.length != 6) return null
    val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
    val value = expanded.toLongOrNull(16) ?: return null
    return Color.rgb(
        ((value shr 16) and 0xFF).toInt(),
        ((value shr 8) and 0xFF).toInt(),
        (value and 0xFF).toInt(),
    )
}

/**
 * Chooses the novel viewer for text chapters.
 *
 * Gated on the loaded chapter rather than on a source id: a chapter that came back carrying text is
 * a text chapter, whatever produced it. An extension therefore gets the novel reader the moment it
 * implements [eu.kanade.tachiyomi.source.text.TextSource], with no change here.
 */
object NovelViewerProvider : ViewerProvider {
    override val order: Int = -10

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        val chapters = runCatching { activity.viewModel.state.value.viewerChapters }.getOrNull() ?: return null
        val page = chapters.currChapter.pages?.firstOrNull() ?: return null
        if (page.textPage == null) return null

        // Images, stylesheets and fonts are served by the loader that read the chapter, so the
        // archive stays open for exactly as long as the chapter is on screen.
        val resources = (chapters.currChapter.pageLoader as? TextPageLoader)?.resourceSource

        return NovelWebViewer(activity, resources).apply {
            // Applied before the first page renders, so the document is never painted unstyled and
            // then restyled - that flash is what makes a reader think the typography is broken.
            typography = runCatching { globalAppGraph.novelTypographyPreferences.typography() }
                .getOrDefault(NovelTypography.DEFAULT)
        }
    }
}
