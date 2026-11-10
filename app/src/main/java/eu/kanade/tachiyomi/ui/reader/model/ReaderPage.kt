package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.text.TextPage
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }

    // KMK -->
    /**
     * True for the extra pages a too-tall image was split into.
     *
     * Such a page is one vertical slice of a source page, so it never pairs into a spread and its
     * [index] is a synthetic value rather than a position in the chapter's list.
     */
    var splitSegment: Boolean = false

    /**
     * True once this page has been replaced by the segments of its own split.
     *
     * A superseded page is no longer in its chapter's list, but a viewer may still be holding it
     * with a decoded surface attached, which is what draws the old strip over its replacement.
     * Recording the state on the page itself lets a viewer reject one without having to go back to
     * the chapter to ask whether it is still real.
     *
     * Volatile because the writer and the viewer that reads it are on different threads, and the
     * write deliberately happens *before* [eu.kanade.tachiyomi.ui.reader.model.ReaderChapter.replacePages]
     * announces the new list - otherwise a viewer woken by that announcement could still read `false`
     * here and keep the page it was told to drop.
     */
    @Volatile
    var supersededBySplit: Boolean = false

    /**
     * The page whose split produced this one, or null when this page is not a segment.
     *
     * What lets a viewer that was showing the parent land on the first segment instead of on the
     * chapter's resume target, which after a mid-session split is a different page entirely.
     */
    var splitSourcePage: ReaderPage? = null

    /**
     * The text this page carries, when the source is a text source.
     *
     * Null for every image page, which is the overwhelming majority — the field exists so a chapter
     * can be *either* images or text without the page list, the chapter state machine, the progress
     * bar or the volume tracking needing a second parallel representation. A viewer picks its
     * renderer from this field's presence, exactly as it picks an image loader from `stream`.
     */
    var textPage: TextPage? = null

    /**
     * The image's pixel dimensions when they are known before the page decodes, or null while
     * they are not.
     *
     * Two producers fill this, in the order a reader meets them: a split writes every segment's
     * exact band size at cut time (a band's height is fully determined by the strip's height), and
     * the WebGPU pipeline's validate stage writes what the image header declares the moment the
     * page's bytes have been read - before the decode has run. A viewer uses it to reserve the
     * page's true display height while the placeholder is up, which is what turns the
     * placeholder-to-image swap from a reflow into a no-op. Volatile because the producers run on
     * loader/decode threads while the render thread reads.
     */
    @Volatile
    var predictedImageSize: PredictedImageSize? = null
    // KMK <--
}

/**
 * An image size learned before decode. [width] and [height] are the image's own pixels, the same
 * axes [eu.kanade.tachiyomi.source.model.Page] never carried because the source model predates any
 * reader that needed geometry early - and it cannot gain them without an ABI change, which this
 * app-side holder avoids.
 */
data class PredictedImageSize(val width: Int, val height: Int) {
    /** True when the pair can meaningfully divide into a display aspect. */
    val isValid: Boolean get() = width > 0 && height > 0
}
