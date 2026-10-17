package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.source.text.TextBaseUrlSource
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.NovelTypographyPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerProvider
import exh.log.xLogE
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.archive.EpubArchiveUrls
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.epub.EpubResourceReader
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * Renders a text chapter in a WebView.
 *
 * ### Why a WebView
 *
 * An EPUB is an XHTML document carrying the publisher's own stylesheet — drop caps, ruby annotations,
 * columns, vertical writing, tables, footnotes. Re-implementing that in Compose means re-implementing
 * a browser, badly. So the document keeps its own markup and the reader's preferences are layered on as
 * an injected stylesheet (see [NovelTypographyCss]).
 *
 * The corollary: layout is the engine's business. What this viewer owns is which document is showing,
 * what the reader's typography is, where in the document they are, and how they move between documents
 * and chapters.
 *
 * ### Resource resolution
 *
 * Each document is loaded at the base URL its source asks for
 * ([eu.kanade.tachiyomi.source.text.TextBaseUrlSource]) — for an EPUB that is the **directory the
 * document sits in**, not the root of the archive. Requests to that scheme are intercepted and streamed
 * out of the archive, which is what makes `<img src="../images/plate.jpg">` resolve inside a zip file.
 * Requests to any other host are deliberately **not** intercepted, so an absolutely-linked image or an
 * external stylesheet still loads normally.
 *
 * Getting that base wrong is invisible and total: every relative reference lands one or two levels from
 * where the publisher meant it, so the stylesheets, illustrations and embedded fonts all 404 and the
 * book renders as bare text with the reader's own styling and nothing else. Which is why the base URL
 * comes from the page rather than from one constant.
 *
 * ### Where each concern lives
 *
 * | Concern | Owner |
 * |---|---|
 * | the markup handed to the engine | [NovelDocument] |
 * | measuring and scrolling the document | [NovelScrollBridge] |
 * | typography and per-chapter positions | [NovelTypographyPreferences] |
 * | the stylesheet itself | [NovelTypographyCss] |
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
@SuppressLint("SetJavaScriptEnabled")
internal class NovelWebViewer(
    private val activity: ReaderActivity,
    private val resources: TextResourceSource?,
    private val loader: TextPageLoader?,
    private val readerPrefs: ReaderPreferences,
    private val novelPrefs: NovelTypographyPreferences,
) : Viewer {

    override val isVertical: Boolean get() = true

    override val currentReaderPage: ReaderPage? get() = currentPage

    private var chapters: ViewerChapters? = null
    private var currentPage: ReaderPage? = null

    /**
     * The document asked for but perhaps not yet read.
     *
     * Separate from [currentPage] because the two answer different questions while a document is in
     * flight: `currentPage` is what is on the screen, and it is what a long press and the reading
     * position belong to. Navigation needs the one the reader is heading for, or a second tap while
     * the first is still loading would resolve its neighbour against the document they just left and
     * send them back.
     */
    private var pendingPage: ReaderPage? = null

    /**
     * Monotonic id of the newest [showDocument] request.
     *
     * A document's markup may arrive after the reader has turned past it. The id is how the stale
     * read recognises itself: only the newest request is allowed to render, so the reader's place is
     * decided by their last press rather than by whichever read happened to finish last.
     */
    private var documentRequest = 0

    /** The in-flight document read, cancelled by the next [showDocument]. */
    private var documentLoadJob: Job? = null

    /**
     * Character length of each document, for [NovelProgress.chapterPercent] and for turning a scroll
     * fraction back into a stored offset.
     *
     * Read from the loader on every use rather than cached at chapter open, because the loader fills
     * these in as documents are read and a snapshot taken before the first one would weight every
     * chapter by nothing at all. A document that has not been read has no length yet and contributes
     * zero, which is the "unknown, do not divide by it" case the progress code already handles.
     */
    private fun documentLengths(): List<Int> =
        (chapters?.currChapter?.pageLoader as? TextPageLoader)?.documentLengths().orEmpty()

    /** Scroll fraction to restore once the document now loading has laid out. */
    private var pendingRestoreFraction: Float? = null

    /**
     * Element to scroll to once the document now loading has laid out, from a contents entry.
     *
     * Separate from [pendingRestoreFraction] because the two are different *requests*: one is "put
     * me back", the other is "take me to this section". Whichever was asked for last wins, which is
     * why every jump clears both before setting one.
     */
    private var pendingAnchor: String? = null

    /** Fraction a search result asked for. See [jumpToFraction]. */
    private var pendingFraction: Float? = null

    /**
     * How far above an anchored element to stop.
     *
     * The reader's own top bar and bottom bar overlay the document when the menus are open, so an
     * element scrolled to the very top is hidden behind them. One line's worth of inset keeps the
     * heading visible without leaving a gap at the start of a section.
     */
    private val anchorInsetPx: Int get() = (webView.resources.displayMetrics.density * 32).toInt()

    /** Last fraction written to the position store, so a drag does not write a preference per frame. */
    private var lastSavedFraction = -1f

    /**
     * Archive path of the document on screen, or null before the first one loads.
     *
     * The contents sheet marks the entry for this document and opens scrolled to it. Read off the
     * live page rather than off the stored position, so it follows the reader as they move rather than
     * reporting where they last stopped.
     */
    val currentDocument: String? get() = currentPage?.textPage?.url

    /**
     * Base URL of the document currently loaded, or null before the first one is.
     *
     * The answer to "did `onPageFinished` fire for the document I asked for?". The engine reports
     * every top-level navigation, including ones a WebViewClient refused, so without this a stray
     * finish would apply a pending restore or an anchor against a layout it was never measured on.
     */
    private var currentBaseUrl: String? = null

    /** Cancelled in [destroy]: the typography subscription outlives the viewer otherwise. */
    private var typographyJob: Job? = null

    /** Typography, republished from settings. Changing it re-applies in place, no reload. */
    var typography by mutableStateOf(NovelTypography.DEFAULT)

    /**
     * Reader-controlled background, applied to the WebView itself as well as the document.
     *
     * A WebView whose content is black but whose overscroll glow is white reads as a rendering bug, so
     * this goes on both.
     */
    private val backgroundColor: Int
        get() = typography.backgroundColor?.toColorIntOrNull() ?: Color.TRANSPARENT

    /**
     * Taps, long-presses and scrolls.
     *
     * Nothing here consumes the event, so selecting and copying text, pinch-zoom and the WebView's own
     * scrolling all keep working — this decides what a tap *means*, never whether it arrives.
     */
    private val gestureDetector = GestureDetector(
        activity,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                currentPage?.let { activity.onPageLongTap(it) }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (abs(dy) > MIN_SCROLL_TO_HIDE_MENU && activity.viewModel.state.value.menuVisible) {
                    activity.hideMenu()
                }
                return false
            }
        },
    )

    private val webView: WebView = WebView(activity).apply {
        setBackgroundColor(backgroundColor)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            // The scroll bridge is how the reader measures the document; nothing else needs scripting.
            javaScriptEnabled = true
            // The publisher's layout has to survive: a viewport wider than the device would let the
            // engine pick a line length the reader never asked for.
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            hardenSettings()
        }
        webViewClient = ArchiveWebViewClient()
        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    override fun getView(): View = webView

    // ---------------------------------------------------------------- containment

    /**
     * Locks the renderer down to what a book actually needs.
     *
     * ### Why this is not paranoia
     *
     * The document in this WebView is a file the user downloaded, and it runs the publisher's own
     * `<script>`. Every setting below has a default that is fine for a page the app wrote and unsafe
     * for a page it did not — this WebView is otherwise configured as though we authored the HTML it
     * is displaying.
     *
     * | Setting | Default | Why it matters here |
     * |---|---|---|
     * | `allowFileAccess` | **true** | `file://` reaches the app's own storage. A book whose script fetches `file:///data/data/…/databases/…db` would otherwise read the user's library. |
     * | `allowContentAccess` | **true** | The same through `content://` — the whole document-provider surface, which is where the user's books are. |
     * | `allowUniversalAccessFromFileURLs` | false | Must stay false: it is the switch that discards the origin, letting a `file://` page read any other origin. |
     * | `mixedContentMode` | **ALWAYS_ALLOW** | An `https` page may pull `http` subresources. Nothing here needs an insecure one; permitting it is a downgrade, not a feature. |
     * | `javaScriptCanOpenWindowsAutomatically` | true | Popups the reader cannot see and cannot dismiss. |
     * | `mediaPlaybackRequiresUserGesture` | **false** | Without it a chapter autoplays audio and keeps playing as the reader turns the page. |
     * | `setGeolocationEnabled` | false | Stated rather than assumed: a book has no business asking where the reader is. |
     *
     * `setAllowFileAccessFromFileURLs` is deliberately absent. It has defaulted to false since API 16
     * and is deprecated, and `allowFileAccess = false` already closes the file scheme outright.
     * Restating a deprecated flag to the default it already had is the kind of thing that reads as
     * hardening while protecting nothing.
     */
    private fun WebSettings.hardenSettings() {
        allowFileAccess = false
        allowContentAccess = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        safeBrowsingEnabled = true
    }

    override fun setChapters(chapters: ViewerChapters) {
        this.chapters = chapters

        val pages = chapters.currChapter.pages ?: return
        if (pages.isEmpty()) return

        // Prefer the stored document over the chapter's resume index: this viewer can restore the
        // position *within* a document, which is strictly better information than "page 3 of 12".
        // Both are coerced, because a chapter can have grown shorter since it was saved — a deleted
        // file, a re-import.
        val stored = novelPrefs.position(chapters.currChapter.chapter.url)?.documentIndex
        val target = (stored ?: chapters.currChapter.requestedPage).coerceIn(0, pages.lastIndex)
        showDocument(pages[target])
    }

    override fun moveToPage(page: ReaderPage) {
        showDocument(page)
    }

    /** Advances one screen. Also the auto-scroll controller's step. */
    override fun moveToNext() {
        step(volumeForward = true)
    }

    override fun destroy() {
        typographyJob?.cancel()
        typographyJob = null
        // A read in flight must not outlive the viewer: it would finish against a destroyed WebView.
        documentLoadJob?.cancel()
        documentLoadJob = null
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        // Action-up only, so a held key does not flip direction mid-hold.
        if (event.action != KeyEvent.ACTION_UP) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Left to the platform when the reader is not set up for volume keys, so the keys still
                // change the volume.
                if (!readerPrefs.readWithVolumeKeys().get()) return false
                val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                val inverted = readerPrefs.readWithVolumeKeysInverted().get()
                step(volumeForward = up != inverted)
                return true
            }

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                activity.toggleMenu()
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                step(volumeForward = true)
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                step(volumeForward = false)
                return true
            }

            else -> return false
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * Re-applies typography to the document already on screen.
     *
     * Reloading would throw the scroll position away on every slider drag, so this swaps the stylesheet
     * in the live DOM instead of calling [moveToPage] again.
     */
    private fun applyTypography() {
        webView.setBackgroundColor(backgroundColor)
        webView.evaluateJavascript(liveTypographyJs(), null)
        webView.invalidate()
    }

    // ---------------------------------------------------------------- documents

    /**
     * Shows [page], reading its markup first if the source did not hand it over with the page list.
     *
     * Split in two because they are not the same moment. A source that answers
     * [eu.kanade.tachiyomi.source.text.TextIncrementalSource] has already given the reader the shape
     * of the book and nothing else, so the markup has to be fetched before there is anything to
     * render; a source that does not has it in hand already and this is one hop.
     *
     * The request token is what keeps the two honest. Turns are the reader pressing faster than a
     * document can be read, so without it a slow read for the document they passed finishes last and
     * puts them back where they left — the one failure mode a reading position cannot recover from,
     * because the position was saved for where they were, not where they went.
     */
    private fun showDocument(page: ReaderPage) {
        val textPage = page.textPage ?: return
        val request = ++documentRequest
        pendingPage = page

        if (textPage.text.isNotEmpty()) {
            renderDocument(page)
            return
        }

        val loader = loader ?: return
        documentLoadJob?.cancel()
        documentLoadJob = activity.lifecycleScope.launch {
            loader.ensureText(page)
            // A newer turn won while this document was being read; its own request will render.
            if (request != documentRequest) return@launch
            renderDocument(page)
        }
    }

    /** Hands a document to the engine. Every measurement the reader asks for happens after this. */
    private fun renderDocument(page: ReaderPage) {
        val textPage = page.textPage ?: return
        currentPage = page

        // The base URL decides where every relative reference in this document points, and it is a
        // property of the document, not of the book. EpubResourceReader.BASE_URL is the right answer
        // only for a spine item that sits at the archive root.
        val baseUrl = (resources as? TextBaseUrlSource)?.baseUrlFor(textPage) ?: EpubResourceReader.BASE_URL
        val html = NovelDocument.build(textPage.text, typography, baseUrl)

        // Set before the load, because onPageFinished for it can arrive before this function returns
        // on a warm engine, and a null here would make the finish look like somebody else's.
        currentBaseUrl = baseUrl
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)

        pendingRestoreFraction = restoreFractionFor(page)
        pendingAnchor = null
        pendingFraction = null
        lastSavedFraction = -1f
        activity.onPageSelected(page)
    }

    /** The fraction to restore for [page], or null when this document has no stored position. */
    private fun restoreFractionFor(page: ReaderPage): Float? {
        val stored = novelPrefs.position(page.chapter.chapter.url) ?: return null
        // Only a position *inside this document* is restorable here. A position elsewhere in the
        // chapter has already been acted on, by setChapters choosing which document to open.
        if (stored.documentIndex != page.index) return null
        return stored.fractionInDocument
    }

    /**
     * Jumps to a table-of-contents entry: the named document, and the named element inside it.
     *
     * Replaces any restore in progress — arriving at a contents entry means "the top of this section",
     * not "where you happened to stop reading it last time", and honouring the stored position would
     * silently land somewhere else in the chapter.
     *
     * The scroll happens in `onPageFinished`, after the document has laid out: asking for a position
     * before then measures an empty page and lands at the top.
     */
    fun jumpTo(document: String, fragment: String?) {
        val page = chapters?.currChapter?.pages?.firstOrNull { it.textPage?.url == document } ?: return

        showDocument(page)
        // Set *after* the load is requested, because [showDocument] clears the pending position and the
        // engine cannot answer onPageFinished before this returns. Reloading even when the document is
        // already the one on screen is deliberate: one path, and the caller asked to go somewhere.
        if (fragment != null) {
            pendingAnchor = fragment
        } else {
            // A contents entry without a fragment means "the top of this document".
            pendingFraction = 0f
        }
    }

    /**
     * Jumps to a fraction of the document with [documentIndex].
     *
     * The position inside a document is approximate — the source reports offsets in the page's plain
     * text while the engine lays out the markup — so this is deliberately expressed as a ratio. See
     * [toSearchResults] for why a character offset cannot cross over intact.
     *
     * [documentIndex] is a *text-page* index, matching what `TextSearchHit.pageIndex` carries, and not
     * a position in the reader's own page list. See the comment on the lookup below for why the two
     * are not interchangeable.
     */
    fun jumpToFraction(documentIndex: Int, fraction: Float) {
        val pages = chapters?.currChapter?.pages
        // Matched on `textPage.index`, which is what `TextSearchHit.pageIndex` is defined to carry —
        // not on the page's position in the list. The two agree only when every spine document read
        // cleanly: a skipped document (missing, corrupt, over the size ceiling) leaves a hole in the
        // text-page indexes while the reader's own list stays dense, so a positional lookup would
        // scroll to the wrong document — or to none — for every hit after the hole. See
        // `TextSearchHit.pageIndex`.
        val page = pages?.firstOrNull { it.textPage?.index == documentIndex } ?: return

        showDocument(page)
        pendingFraction = fraction.coerceIn(0f, 1f)
    }

    private fun handleTap(x: Float) {
        val width = webView.width
        if (width <= 0) return

        val zone = (if (readerPrefs.smallerTapZone().get()) SMALL_TAP_ZONE else TAP_ZONE) * width
        val forward = when {
            x >= width - zone -> true
            x <= zone -> false
            else -> {
                activity.toggleMenu()
                return
            }
        }

        step(volumeForward = forward != readerPrefs.pagerNavInverted().get().shouldInvertHorizontal)
    }

    // ---------------------------------------------------------------- turning

    /**
     * One unit of forward or backward reading: scroll a screen, or turn the document when there is no
     * screen left to scroll.
     *
     * The decision is made **inside** the JavaScript callback, not after it. `evaluateJavascript` posts
     * its result to the UI thread's queue rather than answering inline, so a caller that asked "did that
     * move?" on the line after the call always got "no" and turned the document on every tap, however far
     * down the page the reader actually was.
     *
     * ### Why a scroll that did not move is not on its own a reason to turn
     *
     * It used to be, and the same test had to mean both "at the end" and "at the beginning", which it
     * cannot: a tap that failed for either reason turned *forward*. The reported symptom was that
     * reaching the end of a document needed the page navigator, because the document could not tell
     * the host which end had been reached.
     *
     * So the host asks, once, with [NovelScrollBridge.EDGE], and acts on the answer:
     *
     * | Where the document is | Backward tap | Forward tap |
     * |---|---|---|
     * | at the top | previous document | scroll |
     * | in the middle | scroll | scroll |
     * | at the bottom | scroll | next document |
     * | one screen tall | previous document | next document |
     * | no bridge | nothing | nothing |
     *
     * "No bridge" deliberately does nothing. The bridge is installed by the document itself, so its
     * absence means the document has not parsed yet — and turning on that reading is what skipped a
     * document on the first tap of a freshly-opened book.
     *
     * Everything reached from these callbacks runs on the UI thread and only ever starts another load,
     * which is what makes it safe to call from here.
     */
    private fun step(volumeForward: Boolean) {
        val script = if (volumeForward) {
            NovelScrollBridge.SCROLL_BY_VIEWPORT
        } else {
            NovelScrollBridge.SCROLL_BACK_BY_VIEWPORT
        }

        webView.evaluateJavascript(script) { raw ->
            if (abs(NovelScrollBridge.number(raw)) > MIN_MOVEMENT_PX) return@evaluateJavascript
            turnAtEdge(volumeForward)
        }
    }

    /**
     * Asks the document which way it has run out of room and turns, if it has.
     *
     * A second round trip rather than a second column in the first script's result, because the first
     * script's whole job is to move the document and its answer has to be read before the move is
     * judged; asking again afterwards is the only ordering that cannot see a stale position.
     */
    private fun turnAtEdge(volumeForward: Boolean) {
        webView.evaluateJavascript(NovelScrollBridge.EDGE) { raw ->
            when (NovelScrollBridge.number(raw).toInt()) {
                // Nowhere left to go this way.
                EDGE_AT_TOP -> retreatDocument()
                EDGE_AT_BOTTOM -> advanceDocument()
                // A document that fits on one screen has no "middle"; either tap leaves it.
                EDGE_WHOLE_DOCUMENT -> if (volumeForward) advanceDocument() else retreatDocument()
                // In the middle of the document, or no bridge to ask. Neither is a reason to turn.
                else -> Unit
            }
        }
    }

    /** Next document, or next chapter. */
    private fun advanceDocument() {
        neighbour(1)?.let {
            showDocument(it)
            return
        }

        val chapters = chapters ?: return
        val nextChapter = chapters.nextChapter ?: run {
            // Past the last document of the last chapter. There is nowhere to go, so offer the menu —
            // that is where "next chapter" and the chapter list live — instead of swallowing the tap.
            activity.showMenu()
            return
        }

        // Both are requested: the preload is what makes the switch instant for a network source, and the
        // switch itself is what makes it happen for a local one, where preloading is a no-op.
        activity.requestPreloadChapter(nextChapter)
        activity.viewModel.goToAdjacentChapter(nextChapter)
    }

    /** Previous document, or previous chapter. */
    private fun retreatDocument() {
        neighbour(-1)?.let {
            showDocument(it)
            return
        }

        val chapters = chapters ?: return
        val previousChapter = chapters.prevChapter ?: return
        activity.requestPreloadChapter(previousChapter)
        activity.viewModel.goToAdjacentChapter(previousChapter)
    }

    /**
     * The document [offset] places from the one the reader is on, or null when there is none.
     *
     * Through [ReaderChapter.positionOf], which is by identity rather than by equality and returns `-1`
     * for a page the list no longer contains. The `-1` is the point: a chapter swap that has landed
     * while this viewer is still holding the old page used to compute `-1 + 1`, which is zero, and a
     * forward tap from there opened the *first* document of the book. Reading "no such neighbour" as
     * "the end of the chapter" sends the tap to the chapter boundary, which is where the reader was
     * actually trying to go.
     *
     * The page asked for rather than the page on screen, so a second tap during a read resolves its
     * neighbour against where the reader is heading and cannot send them back.
     */
    private fun neighbour(offset: Int): ReaderPage? {
        val chapter = chapters?.currChapter ?: return null
        val pages = chapter.pages ?: return null
        val page = pendingPage ?: currentPage ?: return null
        val at = chapter.positionOf(page)
        if (at < 0) return null
        return pages.getOrNull(at + offset)
    }

    // ---------------------------------------------------------------- resources

    /**
     * Serves archive-internal URLs out of the open book and lets everything else through.
     *
     * Returning null for a non-archive URL is load-bearing: it is what stops this class breaking a book
     * that links to an image on a real web server.
     *
     * A resource the archive does not contain answers with an explicit 404 rather than with null. A null
     * return hands the URL back to the platform, which then waits on a request to a scheme no handler
     * exists for — so one missing plate holds the document's layout hostage instead of showing a broken
     * image and getting out of the way.
     */
    private inner class ArchiveWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (!EpubResourceReader.isArchiveUrl(url)) return null

            val path = EpubResourceReader.archivePathOf(url)
            if (path == null) {
                logcat { "NovelViewer: archive url with no path, refused: $url" }
                return notFound()
            }

            val stream = runCatching { resources?.openResource(url) }
                .onFailure { xLogE("Failed to open archive resource: $url", it) }
                .getOrNull()
            if (stream == null) {
                // The single most useful line in the whole EPUB path. "Images are not displayed" has
                // no other explanation available from the UI — the document still asks for the plate
                // by name and nothing anywhere says it was refused — so every refusal names the file.
                logcat(LogPriority.WARN) {
                    "NovelViewer: archive resource not found: $path (chapter=${chapters?.currChapter?.chapter?.url})"
                }
                return notFound()
            }

            val mime = resolveMimeType(url, path)

            // Text responses must declare an encoding or the engine guesses and mangles CJK. Binary ones
            // must not: declaring UTF-8 over a font or an image truncates the body at the first byte
            // that is not valid UTF-8, which is a resource that fails to load with no error at all.
            val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) {
                "UTF-8"
            } else {
                null
            }
            logcat { "NovelViewer: serving $path as $mime" }
            return WebResourceResponse(mime, encoding, stream)
        }

        /**
         * The content type to serve [path] under, never null and never a lie.
         *
         * Three answers in order of authority: the source, which knows its own archive; the shared
         * extension table; and finally `application/octet-stream`. The last one is a last resort
         * rather than a guess because a renderer asked for an octet stream declines to decode it
         * exactly as it declines to decode a wrongly-typed image — so the only thing it buys is that
         * the request fails fast instead of being sniffed.
         *
         * The middle step is fed the **archive path**, not the URL. The URL is what the old code
         * passed, so a query string or fragment survived into the extension lookup and a `.jpg?v=2`
         * stopped being a JPEG — which is a plate that silently never draws.
         */
        private fun resolveMimeType(url: String, path: String): String =
            runCatching { resources?.contentTypeFor(url) }.getOrNull()
                ?: EpubArchiveUrls.contentTypeForPath(path)
                ?: OCTET_STREAM

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()

            // An archive-internal link is one of the book's own documents. The platform has no
            // handler for the scheme, so handing it back would simply fail — it has to be turned
            // into a page turn here instead.
            if (EpubResourceReader.isArchiveUrl(url)) {
                openArchiveLink(url)
                return true
            }

            // Everything else is a link out of the book: a footnote to a site, a "where to buy".
            //
            // Returning false would load it *in this WebView*, inside the reader's own chrome, on top
            // of the document the reader was part-way through, with a JavaScript context and the
            // reader's trust pointed at whatever the book chose. The comment that used to sit here
            // said this "belongs to the browser… so returning false lets it navigate" — it did not.
            // Handing it to the system is what was meant, and it is what happens now.
            openExternally(url)
            return true
        }

        /**
         * Follows a link inside the book, when it names a document this chapter actually loaded.
         *
         * The URL is mapped back with [EpubResourceReader.archivePathOf] — the same decoding and
         * normalisation the resource loader applies — so it lines up with the `TextPage.url` values
         * the page list is built from. Anything that does not name one of them is refused: a
         * whole book's links span files this chapter never read, and showing a document the loader
         * has no page for would leave the viewer with nothing to display.
         */
        private fun openArchiveLink(url: String) {
            val pages = chapters?.currChapter?.pages
            val document = EpubResourceReader.archivePathOf(url)
            val page = pages?.firstOrNull { it.textPage?.url == document }
            if (page != null) {
                showDocument(page)
            } else {
                xLogE("Refusing in-book link to a document this chapter does not contain: $url")
            }
        }

        /**
         * Hands a URL to whatever the system uses for links.
         *
         * Failure is swallowed: a footnote with no handler must not take the reader out of the book,
         * and the alternative to doing nothing is a crash.
         */
        private fun openExternally(url: String) {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { xLogE("No handler for out-of-book link: $url", it) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Only the document that was asked for counts. Applying a pending position to some other
            // document would scroll to an offset measured against a completely different layout, and
            // the reader would land mid-sentence with no way to explain it.
            if (url != currentBaseUrl) return
            applyPendingPosition()
            // Re-assert typography in the live DOM too, so the injected stylesheet and the running
            // document cannot drift apart after a self-navigation.
            view.evaluateJavascript(liveTypographyJs(), null)
            savePosition()
        }

        private fun notFound(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    /**
     * The current typography as a script that installs it into the running document.
     *
     * The stylesheet is encoded with [NovelScrollBridge.quoteForJs] rather than hand-escaped here.
     * The hand-rolled version replaced `\`, `'` and `\n` and stopped there — which left `\r`, U+2028
     * and U+2029 unescaped. Those are line terminators to a JavaScript parser, so a font family
     * containing one produced a `SyntaxError`: the whole script failed, the stylesheet swap silently
     * did nothing, and the reader kept the styling it had before the setting changed. A font name is
     * reader-chosen, so this was reachable by the reader and by nothing else — which is why it
     * presented as "the font setting does nothing" rather than as a crash.
     *
     * One encoder, used everywhere a value crosses into a script, is the fix. (Interim: this whole
     * script is deleted in phase 3 of the native-renderer spec.)
     */
    private fun liveTypographyJs(): String {
        val css = NovelTypographyCss.stylesheet(typography)
        return """
            (function(){
              var old = document.getElementById('kmk-typography-live');
              if (old && old.parentNode) old.parentNode.removeChild(old);
              var s = document.createElement('style');
              s.id = 'kmk-typography-live';
              s.textContent = ${NovelScrollBridge.quoteForJs(css)};
              if (document.head) document.head.appendChild(s);
            })();
        """.trimIndent()
    }

    /**
     * Applies whichever position was asked for, if any.
     *
     * Runs from `onPageFinished`, because every one of these measures the document: the engine has
     * laid nothing out before then, so an anchor resolves to the top of an empty page and a fraction
     * to zero.
     */
    private fun applyPendingPosition() {
        val anchor = pendingAnchor
        if (anchor != null) {
            pendingAnchor = null
            pendingFraction = null
            webView.evaluateJavascript(
                NovelScrollBridge.scrollToAnchor(anchor, anchorInsetPx),
                null,
            )
            return
        }

        val fraction = pendingFraction ?: pendingRestoreFraction ?: return
        pendingFraction = null
        pendingRestoreFraction = null
        lastSavedFraction = fraction
        webView.evaluateJavascript(
            NovelScrollBridge.SET_FRACTION.replace("%f", fraction.toString()),
            null,
        )
    }

    /**
     * Persists where the reader is inside the current document.
     *
     * Throttled on a fraction delta rather than per event: this is a preference write, and a drag
     * produces one of those per frame. The delta is small enough that a reader who closes the book
     * mid-sentence loses at most one percent of position.
     */
    private fun savePosition() {
        val page = currentPage ?: return
        val length = documentLengths().getOrNull(page.index) ?: return
        webView.evaluateJavascript(NovelScrollBridge.FRACTION) { raw ->
            val fraction = NovelScrollBridge.number(raw).toFloat().coerceIn(0f, 1f)
            if (abs(fraction - lastSavedFraction) < SAVE_THRESHOLD) return@evaluateJavascript
            lastSavedFraction = fraction
            novelPrefs.setPosition(
                page.chapter.chapter.url,
                NovelProgress.Position(
                    documentIndex = page.index,
                    characterOffset = NovelProgress.characterOffsetFor(fraction, length),
                    characterLength = length,
                ),
            )
        }
    }

    init {
        // Typography is read once at construction so the first document is never painted unstyled, and
        // then observed. Without the observation the settings screen re-lays-out a book nobody reopens,
        // and the reader quite reasonably concludes the setting does nothing.
        //
        // Started here rather than handed in, so the subscription ends with the viewer: the activity's
        // scope outlives a viewer that a reading-mode switch has already destroyed.
        typographyJob = activity.lifecycleScope.launch {
            novelPrefs.changes().collect { value ->
                typography = value
                applyTypography()
            }
        }
    }

    private companion object {
        /** Fraction of the width, at each edge, that turns a page. */
        const val TAP_ZONE = 0.30f

        const val SMALL_TAP_ZONE = 0.15f

        /** Pixels of scroll before a drag counts as "the reader is reading", and hides the menu. */
        const val MIN_SCROLL_TO_HIDE_MENU = 8f

        /** Below this, a "scroll" is the engine clamping at a document edge, not real movement. */
        const val MIN_MOVEMENT_PX = 0.5

        /** How far the reader must travel before the position is written again. */
        const val SAVE_THRESHOLD = 0.02f

        /** Last resort for a resource no extension table and no signature could classify. */
        const val OCTET_STREAM = "application/octet-stream"

        /**
         * Answers of [NovelScrollBridge.EDGE], named so the turn logic reads as a table rather than
         * as magic numbers.
         */
        const val EDGE_AT_TOP = 1

        /** Room in both directions: a scroll, not a turn. Also the "no bridge" answer. */
        const val EDGE_MIDDLE = 0

        const val EDGE_AT_BOTTOM = 2
        const val EDGE_WHOLE_DOCUMENT = 3
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
 * ### Decided from the loader, not from the page content
 *
 * The question is "is this chapter text?", and [TextPageLoader] is the authoritative answer: it is
 * what [PageLoaderRegistry] selects for a [eu.kanade.tachiyomi.source.text.TextSource] and for nothing
 * else. Deciding from `page.textPage` instead was an indirect signal about the same fact, and it was
 * wrong for two reasons — it needs the page list to exist first, and it reports null for a chapter whose
 * pages have not loaded, which is indistinguishable from "not a text chapter". Both mistakes hand an
 * EPUB to the pager.
 *
 * ### It never declines a text chapter
 *
 * There is no `return null` on the text path, and there must not be one. A null here means the pager
 * gets the chapter, and the pager cannot render text: it would show an empty reader with no error,
 * which is precisely the failure this provider exists to prevent. If the viewer cannot be built, the
 * honest outcome is a crash the user reports, not a blank page that looks like a bug in their book.
 *
 * Returning null is reserved for "this is not a text chapter", which is a different answer entirely.
 */
object NovelViewerProvider : ViewerProvider {
    override val order: Int = -10

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        val chapters = runCatching { activity.viewModel.state.value.viewerChapters }.getOrNull()
        if (chapters == null) {
            logcat {
                "NovelViewerProvider: DECLINED - viewerChapters is null " +
                    "(chapter=${activity.viewModel.state.value.currentChapter?.chapter?.url})"
            }
            return null
        }
        val rawLoader = chapters.currChapter.pageLoader
        val loader = rawLoader as? TextPageLoader
        if (loader == null) {
            // The other decline path, and the one a "why is my book a comic?" report lives or dies by:
            // this provider only ever claims a TextPageLoader, so anything else here means the chapter
            // was routed to the image chain upstream and there is nothing this viewer can do about it.
            logcat {
                "NovelViewerProvider: DECLINED - chapter loader is " +
                    "${rawLoader?.let { it::class.simpleName } ?: "null"}, not a TextPageLoader"
            }
            return null
        }
        logcat {
            "NovelViewerProvider: CLAIMED chapter=${chapters.currChapter.chapter.url} " +
                "resources=${loader.resourceSource?.let { it::class.simpleName } ?: "none"}"
        }

        // Images, stylesheets and fonts are served by the loader that read the chapter, so the archive
        // stays open for exactly as long as the chapter is on screen.
        val resources = loader.resourceSource

        return NovelWebViewer(
            activity = activity,
            resources = resources,
            loader = loader,
            readerPrefs = globalAppGraph.readerPreferences,
            novelPrefs = globalAppGraph.novelTypographyPreferences,
        ).apply {
            // Applied before the first page renders, so the document is never painted unstyled and then
            // restyled - that flash is what makes a reader think the typography is broken.
            typography = runCatching { globalAppGraph.novelTypographyPreferences.typography() }
                .getOrDefault(NovelTypography.DEFAULT)
        }
    }
}
