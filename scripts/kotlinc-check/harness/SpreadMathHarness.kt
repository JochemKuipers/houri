// Runs the REAL spread geometry (compiled straight from app/.../WebGpuSpreadMath.kt).
//
// Three rules here have each cost an incident, and all three are invisible in the log:
//
//   - the taller side is never shrunk (shrinking collapsed a spread to thumbnails after an e-ink
//     resume, where the surface dimensions had gone degenerate);
//   - a degenerate dimension is a noop rather than a divide-by-zero or a zero-height upload
//     (gralloc 0x3b, the "format 59" failure);
//   - a side too small to upload is never rescaled *toward*, because the target height comes from
//     the partner and would produce a texture the GPU refuses.
//
// The harness asserts the plan arithmetic, which is what everything downstream consumes.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SPREAD_MAX_DIM
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SPREAD_MIN_SIDE_DIM
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SpreadHeightMatchPlan
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.clampSpreadDim
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.isSpreadSideViable
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.resolveSpreadHeightMatch
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.scaledSpreadWidth
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.shouldAttemptSpreadRescale

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

fun main() {
    // --- the clamps ------------------------------------------------------------------------
    check("clamp leaves an in-range value alone", clampSpreadDim(1200) == 1200)
    check("clamp floors at 1", clampSpreadDim(0) == 1)
    check("clamp floors a negative at 1", clampSpreadDim(-500) == 1)
    check("clamp caps at the maximum", clampSpreadDim(100_000) == SPREAD_MAX_DIM)
    check("the floor is below the upload minimum on purpose", SPREAD_MIN_SIDE_DIM > 1)
    check("the maximum is the renderer's own limit", SPREAD_MAX_DIM == 8192)

    // --- scaledSpreadWidth -----------------------------------------------------------------
    check("aspect is preserved", scaledSpreadWidth(800, 1200, 1200) == 800)
    check("scaling up preserves aspect", scaledSpreadWidth(800, 600, 1200) == 1600)
    check("scaling down preserves aspect", scaledSpreadWidth(1600, 1200, 600) == 800)
    check("a square stays square", scaledSpreadWidth(500, 500, 900) == 900)
    check("rounding is to nearest", scaledSpreadWidth(1000, 3000, 1000) == 333)
    check("the result is clamped to the maximum", scaledSpreadWidth(4000, 1, 8000) == SPREAD_MAX_DIM)
    check("the result is clamped to at least 1", scaledSpreadWidth(1, 100_000, 1) == 1)

    check("zero width is a noop", scaledSpreadWidth(0, 1200, 1200) == null)
    check("zero height is a noop", scaledSpreadWidth(800, 0, 1200) == null)
    check("negative width is a noop", scaledSpreadWidth(-800, 1200, 1200) == null)
    check("negative target height is a noop", scaledSpreadWidth(800, 1200, -1) == null)
    check("zero target height is a noop", scaledSpreadWidth(800, 1200, 0) == null)

    // --- resolveSpreadHeightMatch ----------------------------------------------------------
    // Left is shorter: the plan rescales the LEFT up to the right's height, never the right down.
    val leftShorter = resolveSpreadHeightMatch(800, 1000, 800, 1400)
    check("left shorter is detected", leftShorter?.shorterIsLeft == true)
    check("the target is the taller height", leftShorter?.targetHeight == 1400)
    check("the taller side is never the target of a shrink", (leftShorter?.targetHeight ?: 0) >= 1400)

    // Right is shorter: mirrored.
    val rightShorter = resolveSpreadHeightMatch(800, 1400, 800, 1000)
    check("right shorter is detected", rightShorter?.shorterIsLeft == false)
    check("the target is still the taller height", rightShorter?.targetHeight == 1400)

    // Equal heights are a noop: there is nothing to match, and rescaling would cost a pass.
    check("equal heights are a noop", resolveSpreadHeightMatch(800, 1200, 800, 1200) == null)

    // Zero dimensions are a noop, never a divide-by-zero and never a zero-height upload.
    check("zero left width is a noop", resolveSpreadHeightMatch(0, 1200, 800, 1000) == null)
    check("zero left height is a noop", resolveSpreadHeightMatch(800, 0, 800, 1000) == null)
    check("zero right width is a noop", resolveSpreadHeightMatch(800, 1000, 0, 1200) == null)
    check("zero right height is a noop", resolveSpreadHeightMatch(800, 1000, 800, 0) == null)
    check("negative dimensions are a noop", resolveSpreadHeightMatch(-1, -1, -1, -1) == null)

    // A partner taller than the upload ceiling is clamped, so the rescale cannot produce a texture
    // the GPU refuses.
    val overCeiling = resolveSpreadHeightMatch(800, 1000, 800, 20_000)
    check("an over-ceiling partner is clamped", overCeiling?.targetHeight == SPREAD_MAX_DIM)

    // --- isSpreadSideViable ----------------------------------------------------------------
    check("a normal page is viable", isSpreadSideViable(800, 1200))
    check("a side at the floor is viable", isSpreadSideViable(SPREAD_MIN_SIDE_DIM, SPREAD_MIN_SIDE_DIM))
    check("a side below the floor is not", !isSpreadSideViable(SPREAD_MIN_SIDE_DIM - 1, 1200))
    check("a zero-height side is not viable", !isSpreadSideViable(800, 0))
    check("a degenerate side is not viable", !isSpreadSideViable(0, 0))

    // --- shouldAttemptSpreadRescale --------------------------------------------------------
    val plan = SpreadHeightMatchPlan(shorterIsLeft = true, targetHeight = 1400)
    check("a viable rescale is attempted", shouldAttemptSpreadRescale(true, false, plan))
    check("no retained bytes means no rescale", !shouldAttemptSpreadRescale(false, false, plan))
    check("an in-flight rescale blocks a second", !shouldAttemptSpreadRescale(true, true, plan))
    check("no plan means no rescale", !shouldAttemptSpreadRescale(true, false, null))
    check("no bytes and no plan is still no rescale", !shouldAttemptSpreadRescale(false, false, null))

    // The gate is the one that stops a spread whose bytes the LRU already evicted from spinning a
    // retry storm: evicted bytes are terminal for the pass, and the next decode re-arms it.
    check("evicted bytes are terminal", !shouldAttemptSpreadRescale(false, false, plan))

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}