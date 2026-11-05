package mtl.core

import android.graphics.Bitmap

/**
 * A [PageImage] over an `android.graphics.Bitmap` — the standard carrier for stages and
 * providers that touch pixels directly. [ownsBitmap] decides what [close] does: a wrapper
 * the spine created over its own decode owns nothing (the page run recycles the bitmap),
 * while a stage-produced image (a detection mask, a renderer output) owns its bitmap and
 * recycles it on close.
 */
class BitmapPageImage(
    val bitmap: Bitmap,
    private val ownsBitmap: Boolean,
) : PageImage {

    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override val platform: Any get() = bitmap

    override fun close() {
        if (ownsBitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
