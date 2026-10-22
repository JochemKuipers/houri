// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import java.nio.ByteBuffer
import java.nio.IntBuffer

// EXIF orientation for the WebGPU decode path.
//
// The native decoder hands back raw pixels, so a camera-scan page would display sideways and pair
// with the wrong aspect unless the buffer is rotated here. Only the single-frame path rotates: an
// animated frame stack carrying an orientation tag is vanishingly rare, and rotating every frame
// would multiply transient memory for it.

/**
 * Orientation tags are SHORT values, but readMetadata hands back the raw text; matching digits
 * keeps a stray-space or prefixed value from failing toIntOrNull. Compiled once - this sits on the
 * per-page decode path.
 */
internal val exifOrientationDigits = Regex("\\d+")

/** A rotated RGBA buffer with its new dimensions. Pure math, no Android dependency. */
internal data class RotatedRgba(val buffer: ByteBuffer, val width: Int, val height: Int)

/**
 * Reorients an RGBA [src] (4 bytes/pixel) per EXIF orientation 1-8, moving 4-byte units
 * opaquely. Returns the input untouched for orientation 1/out-of-range, degenerate dims, or a
 * short buffer - never throws. 5-8 swap axes. Single pass, no intermediate allocation.
 *
 * Each orientation has its own loop rather than a `when` inside the pixel loops: the mapping is
 * loop-invariant, so hoisting it drops two subtractions and two multiplies per pixel, and every
 * loop advances source and destination with increments instead of recomputing `row * width`
 * addresses. Pixel-for-pixel output is identical to the per-pixel `when` this replaced - each
 * helper below is that `when`'s formula with the index algebra unfolded.
 */
internal fun rotateRgbaForExif(src: ByteBuffer, width: Int, height: Int, orientation: Int): RotatedRgba {
    if (orientation !in 2..8 || width <= 0 || height <= 0) return RotatedRgba(src, width, height)
    val swapAxes = orientation >= 5
    val dstWidth = if (swapAxes) height else width
    val dstHeight = if (swapAxes) width else height
    if (dstWidth !in 1..SPREAD_MAX_DIM || dstHeight !in 1..SPREAD_MAX_DIM) {
        return RotatedRgba(src, width, height)
    }
    return try {
        val srcInts = src.duplicate().asIntBuffer()
        if (srcInts.remaining() < width * height) return RotatedRgba(src, width, height)
        val out = ByteBuffer.allocateDirect(dstWidth * dstHeight * 4)
        val outInts = out.asIntBuffer()
        when (orientation) {
            2 -> copyFlippedHorizontally(srcInts, outInts, width, height)
            3 -> copyRotated180(srcInts, outInts, width, height)
            4 -> copyFlippedVertically(srcInts, outInts, width, height)
            else -> rotateQuarterTurn(srcInts, outInts, orientation, width, height)
        }
        out.rewind()
        RotatedRgba(out, dstWidth, dstHeight)
    } catch (_: OutOfMemoryError) {
        System.gc()
        RotatedRgba(src, width, height)
    } catch (_: Exception) {
        RotatedRgba(src, width, height)
    }
}

/** Orientation 2: dst(dx, dy) = src(width-1-dx, dy). Each dst row is its src row reversed. */
private fun copyFlippedHorizontally(srcInts: IntBuffer, dstInts: IntBuffer, width: Int, height: Int) {
    var dy = 0
    while (dy < height) {
        val row = dy * width
        var dx = 0
        var sx = width - 1
        while (dx < width) {
            dstInts.put(row + dx, srcInts.get(row + sx))
            dx++
            sx--
        }
        dy++
    }
}

/**
 * Orientation 3: dst(dx, dy) = src(width-1-dx, height-1-dy). The src index of dst row-major
 * index i is exactly (pixelCount - 1 - i), so the 180° case is one reverse sweep of the whole
 * buffer - the cheapest orientation of the eight.
 */
private fun copyRotated180(srcInts: IntBuffer, dstInts: IntBuffer, width: Int, height: Int) {
    val pixelCount = width * height
    var dstIndex = 0
    var srcIndex = pixelCount - 1
    while (dstIndex < pixelCount) {
        dstInts.put(dstIndex, srcInts.get(srcIndex))
        dstIndex++
        srcIndex--
    }
}

/** Orientation 4: dst(dx, dy) = src(dx, height-1-dy). Rows copy forward, bottom-to-top. */
private fun copyFlippedVertically(srcInts: IntBuffer, dstInts: IntBuffer, width: Int, height: Int) {
    var dy = 0
    while (dy < height) {
        val srcRow = (height - 1 - dy) * width
        val dstRow = dy * width
        var dx = 0
        while (dx < width) {
            dstInts.put(dstRow + dx, srcInts.get(srcRow + dx))
            dx++
        }
        dy++
    }
}

/**
 * Orientations 5-8 (axis swaps). dst(dx, dy) reads src along that source row or column while
 * writes stride through the transposed dst column: with dx as the outer loop, the source index
 * advances linearly (+1 or -1 per pixel) and the dst index advances by the dst row stride (the
 * source height), so neither index is ever recomputed from multiplications.
 *
 * Base source indices and per-pixel steps, unfolded from the original formulas
 * (dst(dx,dy) = src(sx, sy), dstWidth = height, dstHeight = width):
 * | o | sx         | sy         | src index        | step |
 * | 5 | dy         | dx         | dx*w + dy        | +1   |
 * | 6 | dy         | height-1-dx| (h-1-dx)*w + dy  | +1   |
 * | 7 | width-1-dy | height-1-dx| (h-1-dx)*w+w-1-dy| -1   |
 * | 8 | width-1-dy | dx         | dx*w + w-1-dy    | -1   |
 */
private fun rotateQuarterTurn(srcInts: IntBuffer, dstInts: IntBuffer, orientation: Int, width: Int, height: Int) {
    val mirrored = orientation == 7 || orientation == 8
    val leading = !mirrored // 5/6 walk the source forward, 7/8 walk it backward
    var dx = 0
    while (dx < height) {
        var srcIndex = (if (orientation == 5 || orientation == 8) dx else height - 1 - dx) * width
        if (mirrored) srcIndex += width - 1
        val step = if (leading) 1 else -1
        var dstIndex = dx
        var dy = 0
        while (dy < width) {
            dstInts.put(dstIndex, srcInts.get(srcIndex))
            dstIndex += height
            srcIndex += step
            dy++
        }
        dx++
    }
}
// KMK <--
