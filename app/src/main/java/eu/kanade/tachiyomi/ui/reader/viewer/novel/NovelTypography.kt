package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Everything the reader can change about how text looks, independent of any Android or WebView
 * type.
 *
 * Kept free of platform imports on purpose: it is the value that gets persisted, passed to the
 * renderer, and asserted against in unit tests, and none of those need a device.
 *
 * See [NovelTypographyCss] for how this reaches the screen.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
data class NovelTypography(
    /**
     * Base text size in points, before [fontScale].
     *
     * Points rather than pixels so the reading size stays consistent across screen densities: a user
     * who picks 16pt means the same physical size on a 1x tablet and a 4x phone.
     */
    val fontSizePt: Int = DEFAULT_FONT_SIZE_PT,

    /** Text colour as `#rrggbb`, or null to leave the publisher's own colour alone. */
    val fontColor: String? = null,

    /**
     * Page background as `#rrggbb`, or null to keep whatever the book sets.
     *
     * Null matters: a publisher that chose a dark plate for one illustration expects it to stay
     * dark, and overriding it globally is how a reader ends up with unreadable black-on-black.
     */
    val backgroundColor: String? = null,

    /** Family name to request, e.g. `serif`, `sans-serif` or a bundled font file. */
    val fontFamily: String = DEFAULT_FONT_FAMILY,

    /**
     * Anti-aliasing quality.
     *
     * Subpixel uses the LCD subpixels and looks better for black-on-white but produces colour
     * fringing, which is wrong for coloured text on a dark background — so this is a real choice
     * rather than a quality slider.
     */
    val smoothText: Boolean = true,

    /**
     * Justify both edges. Null leaves the publisher's own alignment alone.
     *
     * Nullable rather than defaulting to false on purpose. A book whose stylesheet sets
     * `text-align: justify` looks wrong to a reader who wants a ragged right edge, and they cannot
     * express "off" if the default silently means "off" and emits no rule. Null = follow the book;
     * true and false both override it.
     */
    val justified: Boolean? = null,

    /** Break words across lines. Null follows the publisher; see [justified]. */
    val hyphenation: Boolean? = null,

    /** Space between paragraphs, in multiples of the text size. */
    val paragraphSpacing: Float = 0.0f,

    /** Line height as a multiple of the text size. 1.0 is single spacing. */
    val lineSpacing: Float = DEFAULT_LINE_SPACING,

    /** Extra tracking between characters, in em. 0 is the font's own default. */
    val fontSpacing: Float = 0.0f,

    /**
     * Global multiplier on top of [fontSizePt].
     *
     * Separate from the size so that a reader who wants "this book at my normal size" can store one
     * scale and reuse it after changing the base size, instead of the two fighting each other.
     */
    val fontScale: Float = 1.0f,

    /** Left page margin as a fraction of page width (0..0.5). */
    val marginLeft: Float = DEFAULT_MARGIN,

    /** Right page margin as a fraction of page width (0..0.5). */
    val marginRight: Float = DEFAULT_MARGIN,

    /** Top page margin as a fraction of page height. */
    val marginTop: Float = DEFAULT_VERTICAL_MARGIN,

    /** Bottom page margin as a fraction of page height. */
    val marginBottom: Float = DEFAULT_VERTICAL_MARGIN,

    /**
     * Lay text out top-to-bottom, right-to-left (縦書き). Null follows the publisher's writing mode.
     *
     * Null matters here more than anywhere else: forcing `horizontal-tb` on a Japanese light novel
     * rotates every line and destroys vertical ruby annotations.
     */
    val verticalWriting: Boolean? = null,
) {
    /**
     * The text size actually used for layout: [fontSizePt] scaled by [fontScale].
     *
     * Clamped at the low end so a user who drags both controls to minimum still gets legible text —
     * below roughly 8pt an EPUB is unreadable and users usually come back from that setting.
     */
    val effectiveFontSizePt: Float
        get() = (fontSizePt * fontScale).coerceIn(MIN_EFFECTIVE_SIZE_PT, MAX_EFFECTIVE_SIZE_PT)

    /** True when anything differs from the defaults and the stylesheet has to be injected. */
    val isCustomised: Boolean
        get() = this != DEFAULT

    /** This value with every field clamped into its legal range. */
    fun sanitized(): NovelTypography = copy(
        fontSizePt = fontSizePt.coerceIn(MIN_FONT_SIZE_PT, MAX_FONT_SIZE_PT),
        paragraphSpacing = paragraphSpacing.coerceIn(0f, MAX_PARAGRAPH_SPACING),
        lineSpacing = lineSpacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        fontSpacing = fontSpacing.coerceIn(-0.2f, 1.0f),
        fontScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        marginLeft = marginLeft.coerceIn(0f, MAX_MARGIN),
        marginRight = marginRight.coerceIn(0f, MAX_MARGIN),
        marginTop = marginTop.coerceIn(0f, MAX_VERTICAL_MARGIN),
        marginBottom = marginBottom.coerceIn(0f, MAX_VERTICAL_MARGIN),
    )

    companion object {
        const val DEFAULT_FONT_SIZE_PT = 18
        const val DEFAULT_FONT_FAMILY = "serif"
        const val DEFAULT_LINE_SPACING = 1.6f

        /** 5% of the page width. Wider than a phone screen needs, narrow enough to keep a line long. */
        const val DEFAULT_MARGIN = 0.05f

        /** 3% of page height, plus the system bars the reader already reserves. */
        const val DEFAULT_VERTICAL_MARGIN = 0.03f

        const val MIN_FONT_SIZE_PT = 10
        const val MAX_FONT_SIZE_PT = 40
        const val MIN_EFFECTIVE_SIZE_PT = 8f
        const val MAX_EFFECTIVE_SIZE_PT = 72f

        const val MIN_LINE_SPACING = 1.0f
        const val MAX_LINE_SPACING = 3.0f

        const val MAX_PARAGRAPH_SPACING = 3.0f

        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 3.0f

        /** A margin can never take more than half the page, or there is no column left to read. */
        const val MAX_MARGIN = 0.45f
        const val MAX_VERTICAL_MARGIN = 0.45f

        val DEFAULT = NovelTypography()
    }
}
