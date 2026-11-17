// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// KMK -->
// The dual-page spread geometry, as pure arithmetic.
//
// Split out of `WebGpuSpread.kt` because every function here is a decision a reader can see and
// none of them need a viewer, a page cache or an Android class. `SpreadMathHarness` compiles this
// file on a plain JVM and asserts the parts that have each cost an incident: the taller side is
// never shrunk (that collapsed spreads to thumbnails on an e-ink resume), a degenerate dimension
// is a noop rather than a divide-by-zero, and a page too small to upload never gets rescaled
// toward (gralloc 0x3b).

/** Floor for a spread side upload: below this the GPU rejects the texture (gralloc 0x3b). */
internal const val SPREAD_MIN_SIDE_DIM = 8

/** Hard ceiling for any rescaled spread dimension. */
internal const val SPREAD_MAX_DIM = 8192

/** Clamp any spread dimension into the uploadable 1..[SPREAD_MAX_DIM] range. Pure math. */
internal fun clampSpreadDim(value: Int): Int = value.coerceIn(1, SPREAD_MAX_DIM)

/**
 * Width preserving aspect when scaling [srcWidth]x[srcHeight] to [targetHeight], clamped to
 * 1..[SPREAD_MAX_DIM]; null on non-positive dims (hard noop, never divides by zero).
 * Pure math, no Android dependency.
 */
internal fun scaledSpreadWidth(srcWidth: Int, srcHeight: Int, targetHeight: Int): Int? {
    if (srcWidth <= 0 || srcHeight <= 0 || targetHeight <= 0) return null
    // Written out rather than via roundToInt so this file keeps zero imports: it is compiled
    // directly by the offline harness, and one stdlib import is all it would take to lose that.
    val scaled = srcWidth.toDouble() * targetHeight.toDouble() / srcHeight.toDouble()
    val rounded = scaled.toLong() + if (scaled - scaled.toLong() >= 0.5) 1L else 0L
    return rounded.coerceIn(1L, SPREAD_MAX_DIM.toLong()).toInt()
}

/** A resolved height-match: which side is shorter and the clamped taller height. */
internal data class SpreadHeightMatchPlan(val shorterIsLeft: Boolean, val targetHeight: Int)

/**
 * Resolve one deterministic shorter-to-taller pass, or null when it is a noop (zero dims,
 * already equal heights). The target is the clamped taller height, so the taller side is
 * never shrunk (which collapsed spreads to tiny on e-ink resume). Pure math.
 */
internal fun resolveSpreadHeightMatch(
    leftWidth: Int,
    leftHeight: Int,
    rightWidth: Int,
    rightHeight: Int,
): SpreadHeightMatchPlan? {
    if (leftWidth <= 0 || leftHeight <= 0 || rightWidth <= 0 || rightHeight <= 0) return null
    if (leftHeight == rightHeight) return null
    return SpreadHeightMatchPlan(
        shorterIsLeft = leftHeight < rightHeight,
        targetHeight = clampSpreadDim(maxOf(leftHeight, rightHeight)),
    )
}

/** True when a decoded side is big enough to rescale or rescale toward. Pure math. */
internal fun isSpreadSideViable(width: Int, height: Int): Boolean =
    width >= SPREAD_MIN_SIDE_DIM && height >= SPREAD_MIN_SIDE_DIM

/**
 * Gate for firing a rescale: needs a plan, retained source bytes, and no rescale already
 * running. Evicted bytes (freed on eviction) are terminal for the pass - the next decode
 * re-arms via fresh bytes - so a dead spread can never spin a retry storm. Pure math.
 */
internal fun shouldAttemptSpreadRescale(
    hasBytes: Boolean,
    rescaleInFlight: Boolean,
    plan: SpreadHeightMatchPlan?,
): Boolean = plan != null && hasBytes && !rescaleInFlight
// KMK <--
// Mihon <--
