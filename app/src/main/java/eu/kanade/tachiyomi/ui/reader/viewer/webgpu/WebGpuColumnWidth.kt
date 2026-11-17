// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// How wide the continuous reader's column is, as pure arithmetic.
//
// **Side padding** (`webtoon_side_padding`, 0-25% per side) is a Webtoon-group setting the WebGPU
// reader ignored entirely: the legacy webtoon viewer applies it as a left/right margin on every page
// frame, so a strip is drawn narrower than the screen and centred, while the WebGPU reader drew every
// page at the full surface width and the slider did nothing at all.
//
// What the library actually consumes is one number - the width of the surface it renders into, which
// it then scales every page to. So "the column is narrower" is expressed here as a width, and the
// viewer applies it as the pager's layout width inside a full-screen frame.
//
// The Webtoon group's **scale type** (`webtoon_scale_type`, gated by
// `pref_webtoon_smart_scale_long_strip_gap`) is deliberately *not* here. It is defined against a
// paged viewer: the legacy listener scales the recycler so a page's rendered width becomes
// `pageHeight * ratio`, which needs the page's own height - a per-page value. The WebGPU reader has
// no paged webtoon mode (both WEBTOON and CONTINUOUS_VERTICAL are continuous there) and its library
// scales every page to one surface width, so a fixed column could only approximate the setting by
// narrowing *everything*, including the tall strips the setting exists to leave alone. The row is
// hidden while WebGPU is the renderer rather than faked; see `ReadingModePage`.

/**
 * Narrowest column the reader will ever produce, as a fraction of the screen width.
 *
 * The library refuses degenerate surface dimensions (see the renderer's `MIN_SURFACE_DIMENSION`),
 * and a 25%-per-side padding on a narrow screen in split-screen could otherwise ask for something
 * close to that. A fifth of the screen is still a readable column, and every real setting lands far
 * above it.
 */
internal const val COLUMN_MIN_WIDTH_FRACTION = 0.2f

/** The resolved column width in pixels; 0 means the surface does not exist yet. */
internal fun resolveColumnWidth(
    screenWidth: Int,
    sidePaddingPercent: Int,
): Int {
    if (screenWidth <= 0) return 0

    // Percent per side, so both sides together remove twice the percentage.
    val keptFraction = (100 - 2 * sidePaddingPercent.coerceIn(0, 100)) / 100f
    val padded = screenWidth * keptFraction.coerceAtLeast(0f)

    val floor = screenWidth * COLUMN_MIN_WIDTH_FRACTION
    return padded.coerceAtLeast(floor).toInt().coerceIn(1, screenWidth)
}
// KMK <--
