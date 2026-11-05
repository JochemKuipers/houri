// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * How a paged document's page turn is animated — Moon+ Reader's flip list
 * (`R.array.flip_animation_list`: none, two curls, slide horizontal, slide vertical, plus a speed
 * setting; decompiled `PrefVisual.setFlipValue`), reduced to the effects a snapshot overlay can
 * render honestly.
 *
 * ### Why one snapshot, animated away
 *
 * The obvious implementation captures the old page *and* the new one and animates between them.
 * The second capture races the WebView's compositor: the bridge's turn script has already moved
 * `scrollLeft`, but whether the next `draw()` shows the new column or the stale frame is a timing
 * property of the renderer, not of our code. So the animation is one-sided instead: the *old*
 * page is captured before the turn, the turn happens live underneath, and the old page's bitmap
 * animates away on top — slides off an edge, fades out, or swings over on its spine. The reader
 * ends on the live page by construction, a missed frame can only make the effect start a moment
 * early, and the overlay never draws content it does not have.
 */
enum class PageTurnAnimation {
    /** No overlay: the turn is the bridge's instant scroll, exactly as before. */
    NONE,

    /** The old page slides off the edge the reader is heading for. */
    SLIDE_HORIZONTAL,

    /** The old page slides off the top (or bottom, going back). */
    SLIDE_VERTICAL,

    /** The old page dissolves into the new one. */
    FADE,

    /**
     * The old page swings over on its spine — a `rotateY` about the leading edge, the simplified
     * member of Moon+'s curl family. Their `NewCurl3D` is a full canvas simulation of a bending
     * sheet; a snapshot cannot bend, so the flip is the honest part of that effect.
     */
    CURL,
    ;

    val isNone: Boolean get() = this == NONE

    companion object {
        /** The animation a stored ordinal names; out-of-range values (old build, hand edit) read as none. */
        fun fromPreference(value: Int): PageTurnAnimation =
            entries.getOrElse(value) { NONE }
    }
}

/**
 * The fullscreen overlay a paged turn animates: one bitmap of the page just left, drawn over the
 * live WebView and carried off by the [PageTurnAnimation] the reader chose.
 *
 * Owns its own animator and removes itself from its parent when done — the caller adds nothing,
 * sets nothing, and recycles nothing. The bitmap is recycled on the way out. A plain `View`
 * rather than Compose because it must sit inside the same `FrameLayout` the WebView lives in,
 * which is a classic view hierarchy.
 *
 * Geometry is read from the **parent**, not from `this`: the overlay is added to the hierarchy in
 * [run] and has no layout yet, so its own width/height are still zero on the frame the animation
 * starts. The parent is the container the WebView already fills, which is the size the snapshot
 * was taken at.
 */
internal class NovelPageTurnOverlay(context: Context) : View(context) {

    /**
     * Perspective depth for the curl. A `rotateY` at the default camera distance reads flat — the
     * page shrinks without foreshortening — so the camera is pulled back to a book-like distance:
     * far enough that the flip does not distort the text, near enough that the turn has depth.
     */
    private val cameraDistancePx: Float
        get() = resources.displayMetrics.density * 6_000f

    /** The spine-edge shading the curl draws while the page lifts. */
    private val spinePaint = Paint().apply { isAntiAlias = true }

    /** Fades the whole page for [PageTurnAnimation.FADE]; opaque for everything else. */
    private val pagePaint = Paint()

    private var drawnBitmap: Bitmap? = null

    /**
     * Captures [bitmap] of the page just left, covers [parent] with it, and animates it away over
     * [durationMs]. The overlay removes itself and recycles the bitmap when the animation ends.
     */
    fun run(parent: ViewGroup, bitmap: Bitmap, animation: PageTurnAnimation, forward: Boolean, durationMs: Long) {
        if (animation.isNone || bitmap.isRecycled || parent.width <= 0 || parent.height <= 0) {
            bitmap.recycle()
            return
        }
        drawnBitmap = bitmap
        alpha = 1f
        translationX = 0f
        translationY = 0f
        rotationY = 0f
        pivotX = 0f
        pivotY = parent.height * 0.5f
        cameraDistance = cameraDistancePx
        pagePaint.alpha = 255

        val from: Float
        val to: Float
        val apply: (Float) -> Unit
        when (animation) {
            PageTurnAnimation.SLIDE_HORIZONTAL -> {
                // Forward pulls the page off the right edge; backward pushes it in from the left —
                // the mirror a turned-back page should read as.
                from = 0f
                to = (if (forward) 1 else -1) * parent.width.toFloat()
                apply = { t -> translationX = t }
            }
            PageTurnAnimation.SLIDE_VERTICAL -> {
                from = 0f
                to = (if (forward) -1 else 1) * parent.height.toFloat()
                apply = { t -> translationY = t }
            }
            PageTurnAnimation.FADE -> {
                from = 0f
                to = 1f
                apply = { t -> pagePaint.alpha = ((1f - t) * 255).toInt() }
            }
            PageTurnAnimation.CURL -> {
                // The spine is the edge the reader is heading for: the leading edge forward, the
                // trailing one back. The page swings about it the way a paper page lifts.
                pivotX = if (forward) 0f else parent.width.toFloat()
                from = 0f
                to = (if (forward) -1 else 1) * 90f
                apply = { t -> rotationY = t }
            }
            PageTurnAnimation.NONE -> {
                bitmap.recycle()
                drawnBitmap = null
                return
            }
        }

        parent.addView(
            this,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs.coerceAtLeast(1L)
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                apply(it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (parent as? ViewGroup)?.removeView(this@NovelPageTurnOverlay)
                    drawnBitmap = null
                    bitmap.recycle()
                }
            })
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = drawnBitmap ?: return
        if (bitmap.isRecycled) return
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), pagePaint)
        // While the page is mid-lift the spine edge carries a shadow gradient — the one cue that
        // separates a curl from a plain slide. It sits on the pivot edge, fading inward, and only
        // exists while the rotation is under way.
        if (rotationY != 0f && width > 0) {
            val leading = pivotX < width * 0.5f
            spinePaint.shader = LinearGradient(
                if (leading) 0f else width.toFloat(),
                0f,
                if (leading) width * SPINE_SHADOW_FRACTION else width * (1f - SPINE_SHADOW_FRACTION),
                0f,
                Color.argb(SPINE_SHADOW_ALPHA, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), spinePaint)
        }
    }

    private companion object {
        /** The spine gradient reaches this fraction of the page width inward. */
        const val SPINE_SHADOW_FRACTION = 0.12f

        /** How dark the spine shadow is at the edge itself. */
        const val SPINE_SHADOW_ALPHA = 70
    }
}
// KMK <--
