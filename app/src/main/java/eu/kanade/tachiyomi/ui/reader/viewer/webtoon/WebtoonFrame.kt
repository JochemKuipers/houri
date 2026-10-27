package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * Frame layout which contains a [WebtoonRecyclerView]. It's needed to handle touch events,
 * because the recyclerview is scaled and its touch events are translated, which breaks the
 * detectors.
 *
 * TODO consider integrating this class into [WebtoonViewer].
 */
class WebtoonFrame(context: Context) : FrameLayout(context) {

    /**
     * Scale detector, either with pinch or quick scale.
     */
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    /**
     * Fling detector.
     */
    private val flingDetector = GestureDetector(context, FlingListener())

    var doubleTapZoom = true
        set(value) {
            field = value
            recycler?.doubleTapZoom = value
            scaleDetector.isQuickScaleEnabled = value
        }

    // KMK -->
    var pinchToZoom = true
        set(value) {
            field = value
            recycler?.pinchToZoom = value
        }
    // KMK <--

    var zoomOutDisabled = false
        set(value) {
            field = value
            recycler?.zoomOutDisabled = value
        }

    // KMK -->
    /**
     * Pointer count of the event most recently handed to [scaleDetector].
     *
     * Every scale callback arrives synchronously from inside `scaleDetector.onTouchEvent(ev)`,
     * so at callback time this names the event that produced it. A genuine quick scale runs on
     * one finger - the second tap is held and the same finger drags - while a pinch runs on two
     * or more. That is the only fact that tells the two apart at [ScaleListener.onScaleBegin],
     * and the difference is load-bearing: quick scale belongs to the double-tap-zoom setting,
     * a pinch to the pinch-to-zoom one, so classifying a pinch as a quick scale lets it zoom
     * with pinch zoom disabled.
     */
    private var scalePointerCount = 0
    // KMK <--

    /**
     * Recycler view added in this frame.
     */
    private val recycler: WebtoonRecyclerView?
        get() = getChildAt(0) as? WebtoonRecyclerView

    /**
     * Dispatches a touch event to the detectors.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // KMK -->
        scalePointerCount = ev.pointerCount
        // KMK <--
        scaleDetector.onTouchEvent(ev)
        flingDetector.onTouchEvent(ev)

        // Get the bounding box of the recyclerview and translate any motion events to be within it.
        // Used to allow scrolling outside the recyclerview.
        val recyclerRect = Rect()
        recycler?.getHitRect(recyclerRect) ?: return super.dispatchTouchEvent(ev)
        // Shrink the box to account for any rounding issues.
        recyclerRect.inset(1, 1)

        if (recyclerRect.right < recyclerRect.left || recyclerRect.bottom < recyclerRect.top) {
            return super.dispatchTouchEvent(ev)
        }

        ev.setLocation(
            ev.x.coerceIn(recyclerRect.left.toFloat(), recyclerRect.right.toFloat()),
            ev.y.coerceIn(recyclerRect.top.toFloat(), recyclerRect.bottom.toFloat()),
        )
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Scale listener used to delegate events to the recycler view.
     */
    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // KMK --> fromQuickScale, not a guess from "a double tap happened recently": a
            // pinch started while the second tap's finger was still down used to arm quick
            // scaling and then rode its exemption past the pinch gate.
            recycler?.onScaleBegin(fromQuickScale = scalePointerCount == 1)
            // KMK <--
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // KMK -->
            recycler?.onScale(detector.scaleFactor, fromQuickScale = scalePointerCount == 1)
            // KMK <--
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            recycler?.onScaleEnd()
        }
    }

    /**
     * Fling listener used to delegate events to the recycler view.
     */
    inner class FlingListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            recycler?.onManualScroll()
            return recycler?.zoomFling(velocityX.toInt(), velocityY.toInt()) ?: false
        }
    }
}
