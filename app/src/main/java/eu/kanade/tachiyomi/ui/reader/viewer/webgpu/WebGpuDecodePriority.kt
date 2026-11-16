// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// What the decode worker owes a page, as a pure function over the facts that decide it.
//
// The policy used to live inline in `WebGpuViewer.decodePriorityOf`, reachable only through a live
// viewer with a populated page cache - which meant the one piece of arithmetic in this folder whose
// bands are documented as "load-bearing" (travelling-behind beating idle-ahead is the whole point of
// the direction boost) could not be asserted by anything. `scripts/kotlinc-check/run-harness.sh`
// compiles this file on a plain JVM and drives it directly; the viewer keeps the two lookups that
// need it (current page, page distance) and delegates the ordering.
//
// The rules below are deliberately unchanged from the inline version. What is new is that a change
// to any band now has to survive `DecodePriorityHarness`.

// Decode priority classes; higher decodes sooner.
internal const val DECODE_PRIORITY_CURRENT = 1000
internal const val DECODE_PRIORITY_ON_SCREEN = 900
internal const val DECODE_PRIORITY_DEMANDED = 800

// Preload band: the travelling side outranks the idle side, ahead outranks behind at equal
// everything, distance orders within each. Bands were chosen so the classes interleave by
// direction instead of stacking: travelling-behind (740-d) beats idle-ahead (730-d') whenever
// d < 10 + d', which is the "you scrolled up, so the pages above you decode first" rule -
// while every preload stays under DECODE_PRIORITY_DEMANDED and above DECODE_PRIORITY_OTHER.
internal const val DECODE_PRIORITY_AHEAD_BASE = 730
internal const val DECODE_PRIORITY_BEHIND_BASE = 720
internal const val DECODE_PRIORITY_DIRECTION_BOOST = 20
internal const val DECODE_PRIORITY_DISTANCE_CAP = 64
internal const val DECODE_PRIORITY_OTHER = 100

/**
 * The priority a page is enqueued at.
 *
 * The reader's own demand outranks geometry, and geometry outranks everything else: the page on
 * screen first, then everything the render walk is asking for, then the preload window by
 * distance - ahead before behind - with the side the reader last travelled toward boosted, so a
 * downward read fills downward first without the preload window itself ever becoming asymmetric.
 * Distances past the window radius clamp rather than fall to OTHER, so a far preload still
 * outranks an unrelated shell.
 *
 * [distance] is the signed page distance from the anchor, or null when there is no anchor or no
 * measurable distance; [scrollDirection] is the sign of the last real scroll (+1 down, -1 up).
 *
 * A distance of exactly 0 is the anchor's own position reached through a different shell: it
 * takes the ahead base with no boost, exactly as the inline version did.
 */
internal fun decodePriorityFor(
    isCurrent: Boolean,
    isOnScreen: Boolean,
    rendererDemanded: Boolean,
    distance: Int?,
    scrollDirection: Int,
): Int {
    if (isCurrent) return DECODE_PRIORITY_CURRENT
    if (isOnScreen) return DECODE_PRIORITY_ON_SCREEN
    if (rendererDemanded) return DECODE_PRIORITY_DEMANDED

    distance ?: return DECODE_PRIORITY_OTHER
    if (distance == 0) return DECODE_PRIORITY_AHEAD_BASE

    val travelling = if (scrollDirection > 0) distance > 0 else distance < 0
    val base = if (distance > 0) DECODE_PRIORITY_AHEAD_BASE else DECODE_PRIORITY_BEHIND_BASE
    val boost = if (travelling) DECODE_PRIORITY_DIRECTION_BOOST else 0
    // Widened before the magnitude, because abs(Int.MIN_VALUE) is negative: minOf would then
    // return that negative value and the subtraction would overflow into a hugely *negative*
    // priority - a page that can never be popped. Distances come from chapter/index arithmetic
    // and cannot reach that in practice, so this is a guard rather than a fix; it costs one
    // widening and is asserted by DecodePriorityHarness.
    val magnitude = minOf(kotlin.math.abs(distance.toLong()), DECODE_PRIORITY_DISTANCE_CAP.toLong()).toInt()
    return base + boost - magnitude
}
// KMK <--