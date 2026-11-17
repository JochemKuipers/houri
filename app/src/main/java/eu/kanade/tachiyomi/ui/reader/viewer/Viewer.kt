package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters

/**
 * Interface for implementing a viewer.
 */
interface Viewer {

    /**
     * Returns the view this viewer uses.
     */
    fun getView(): View

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    fun destroy() {}

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    fun setChapters(chapters: ViewerChapters)

    /**
     * Tells this viewer to move to the given [page].
     */
    fun moveToPage(page: ReaderPage)

    /**
     * Whether this viewer lays pages out vertically. Used for orientation and
     * progress-bar direction instead of `is` checks in the activity.
     */
    val isVertical: Boolean
        get() = false

    /**
     * The currently displayed [ReaderPage], if this viewer shows chapter pages.
     * Viewers with their own page model (WebGPU) return null.
     */
    val currentReaderPage: ReaderPage?
        get() = null

    /**
     * Advances one step for auto-scroll. No-op by default; pagers step pages,
     * webtoon scrolls, WebGPU viewers opt out.
     */
    fun moveToNext() {}

    /**
     * Retries translation for the current page. No-op by default.
     */
    fun retryTranslation() {}

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean

    // KMK --> Audience lifecycle hooks. The activity is the only thing that knows when it stops,
    // and several viewer subsystems have work that is only worth doing while someone is looking:
    // the WebGPU memory monitor's polling cadence, the renderer's display-refresh-mode request,
    // and any viewer that holds a cache it could shed while backgrounded. A default no-op keeps
    // every existing viewer correct without touching them; only the viewers that have such work
    // override these.
    //
    // Note this is *not* a memory-pressure signal. `onTrimMemory` already covers the system's own
    // trimming, and routing screen-off through it would mean a full tile rebuild on every
    // lock/unlock - see the landmine in `viewer/webgpu/AGENTS.md`.

    /** The activity is no longer in front of the user, but is still alive. */
    fun onActivityStop() {}

    /** The activity is in front of the user again. */
    fun onActivityStart() {}

    /**
     * The device's memory is short *right now*, and this viewer should shed what it can spare.
     *
     * The WebGPU viewer already answered this for itself with a full byte budget and a live sampler
     * (`viewer/webgpu/AGENTS.md`, "MEMORY BUDGET"). Everything else in the app - the pager, the
     * strip, the novel WebView - had no path at all, so a device under pressure still held the whole
     * decoded working set through a background or a memory spike, and the system's only recourse was
     * to kill the process.
     *
     * The default is a no-op on purpose rather than a blanket "clear every cache": each viewer knows
     * which of its caches is cheap to rebuild and which is not, and getting that wrong costs a
     * visible re-decode of the page the user is on. The WebGPU viewer's own rule is the model -
     * *never* evict what is on screen, because a cache that cannot hold its drawn window is worse
     * than one that is merely small.
     *
     * Callers must treat this as advisory: a viewer may decline, and may do nothing at all if it has
     * nothing cheap to shed.
     */
    fun onMemoryPressure() {}
    // KMK <--
}
