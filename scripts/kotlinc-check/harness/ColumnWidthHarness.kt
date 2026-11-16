// Runs the REAL column-width policy (compiled straight from app/.../WebGpuColumnWidth.kt).
//
// This is the arithmetic behind the Webtoon group's side-padding slider, which the WebGPU reader
// used to ignore outright. The failure mode it guards is not a crash: it is a surface the library
// refuses (it rejects sub-8px dimensions) or a layout that is technically applied but visually
// indistinguishable from the default - both of which read to a user as "the setting does nothing".

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.COLUMN_MIN_WIDTH_FRACTION
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.resolveColumnWidth

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
    val screen = 1080

    // --- the default: no padding means the full surface, which is what the reader did before ----
    check("zero padding keeps the full width", resolveColumnWidth(screen, 0) == screen)
    check("zero padding on a tablet", resolveColumnWidth(1600, 0) == 1600)

    // --- the slider, which is percent per side ------------------------------------------------
    // 10% per side removes 20% of the screen in total, so the column keeps 80%.
    check("10% per side keeps 80%", resolveColumnWidth(screen, 10) == 864)
    check("5% per side keeps 90%", resolveColumnWidth(screen, 5) == 972)
    check("25% per side keeps 50%", resolveColumnWidth(screen, 25) == 540)
    check("1% per side is honoured", resolveColumnWidth(screen, 1) == 1058)

    // The column has to actually shrink - an off-by-two (padding only one side, or percent treated
    // as a fraction of the remainder) would still produce a plausible-looking number.
    check("padding shrinks the column monotonically", (0..25).toList().zip((0..25).toList().drop(1)).all {
        resolveColumnWidth(screen, it.first) > resolveColumnWidth(screen, it.second)
    })
    check("the column is centred by construction", resolveColumnWidth(screen, 25) * 2 == screen)

    // --- the arithmetic is proportional to the screen ----------------------------------------
    check("the same percentage scales with the screen", resolveColumnWidth(2400, 10) == 1920)
    check("a narrow screen keeps the same fraction", resolveColumnWidth(540, 10) == 432)

    // --- the floor: a setting cannot ask for a surface the library refuses --------------------
    // The largest the preference can be is 25% per side, which leaves half - far above the floor -
    // so this is a guard against a corrupted or future value rather than a live path.
    check("a 100% padding still leaves a fifth", resolveColumnWidth(screen, 100) == (screen * COLUMN_MIN_WIDTH_FRACTION).toInt())
    check("a 1000% padding still leaves a fifth", resolveColumnWidth(screen, 1000) == (screen * COLUMN_MIN_WIDTH_FRACTION).toInt())
    check("a negative padding is treated as none", resolveColumnWidth(screen, -50) == screen)
    check("the floor is a fifth of the screen", COLUMN_MIN_WIDTH_FRACTION == 0.2f)

    // --- degenerate screens: the surface does not exist yet ----------------------------------
    check("a zero-width screen answers 0", resolveColumnWidth(0, 10) == 0)
    check("a negative screen answers 0", resolveColumnWidth(-1080, 10) == 0)

    // A 1px screen is absurd but must not produce a 0-width child, which is the value that makes
    // the library latch a degenerate scale.
    check("a 1px screen still answers at least 1", resolveColumnWidth(1, 25) >= 1)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}