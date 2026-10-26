// KMK -->
package eu.kanade.tachiyomi.ui.reader.setting

import tachiyomi.core.common.preference.SettingKey

/**
 * Every key owned by [NovelTypographyPreferences].
 *
 * Single source of truth so the storage layer and the settings screen cannot drift apart, the same
 * reason `WebhookSettingKeys` exists.
 *
 * Do not rename any [SettingKey.key] without a migration in `mihon.core.migration.migrations` —
 * backup files match on the raw string.
 *
 * ### The tri-state keys
 *
 * `JUSTIFIED`, `HYPHENATION` and `VERTICAL_WRITING` default to [NOVEL_FOLLOW_PUBLISHER] rather than
 * to `false`. That is not the same as "off": it means "do not emit a rule at all", so the book's own
 * stylesheet decides. A default of `false` would force `text-align: left` onto every book and
 * silently restyle Japanese light novels out of vertical writing.
 */
object NovelTypographySettingKeys {
    /** Sentinel for "follow the publisher", stored as this string rather than as a boolean. */
    const val NOVEL_FOLLOW_PUBLISHER = "follow"

    val FONT_SIZE = SettingKey("pref_novel_font_size", 18)
    val FONT_SCALE = SettingKey("pref_novel_font_scale", 1.0f)
    val FONT_FAMILY = SettingKey("pref_novel_font_family", "serif")
    val FONT_COLOR = SettingKey("pref_novel_font_color", "")
    val BACKGROUND_COLOR = SettingKey("pref_novel_background_color", "")

    val SMOOTH_TEXT = SettingKey("pref_novel_smooth_text", true)

    /**
     * Whether a book's inline illustrations are drawn.
     *
     * Default on, which is also what the reader did before the setting existed — the markup asked for
     * them and the renderer fetched them. The switch exists for the case where that is not what
     * someone wants: decoding every plate of a long illustrated novel is the slowest part of turning
     * the page, and a text-only reading of the same book is a legitimate way to read it.
     */
    val SHOW_IMAGES = SettingKey("pref_novel_show_images", true)
    val JUSTIFIED = SettingKey("pref_novel_justified", NOVEL_FOLLOW_PUBLISHER)
    val HYPHENATION = SettingKey("pref_novel_hyphenation", NOVEL_FOLLOW_PUBLISHER)
    val VERTICAL_WRITING = SettingKey("pref_novel_vertical_writing", NOVEL_FOLLOW_PUBLISHER)

    /**
     * Bold body text and force-dark colours, both tri-states stored like the three above.
     *
     * "Off" for these two is the same as "follow the book" where it matters (nothing is emitted for
     * force-dark's `off`; bold's `off` emits `font-weight: 400`) but is kept a distinct answer: the
     * cycle follow → on → off → follow is how every tri-state in the tab is operated, and a control
     * with a missing third state would read as broken and stay on follow forever.
     */
    val BOLD_TEXT = SettingKey("pref_novel_bold_text", NOVEL_FOLLOW_PUBLISHER)
    val FORCE_DARK = SettingKey("pref_novel_force_dark", NOVEL_FOLLOW_PUBLISHER)

    /**
     * Whether a paragraph after the first is indented.
     *
     * Tri-state like the three above, but defaulting to **on** rather than to the publisher. That is
     * a deliberate exception and the reason is backwards compatibility: the stylesheet forced a 1em
     * indent unconditionally before the setting existed, so `on` is what every existing reader is
     * already reading, and defaulting to `follow` would quietly restyle their whole library the
     * moment this shipped. The other three default to `follow` because they were no-ops before they
     * were settings, and inventing a value for them would have invented a rendering.
     *
     * `follow` is still offered, and is the publisher's answer for anyone who wants it.
     */
    val PARAGRAPH_INDENT = SettingKey("pref_novel_paragraph_indent", "true")

    val LINE_SPACING = SettingKey("pref_novel_line_spacing", 1.6f)
    val PARAGRAPH_SPACING = SettingKey("pref_novel_paragraph_spacing", 0.0f)
    val FONT_SPACING = SettingKey("pref_novel_font_spacing", 0.0f)

    val MARGIN_LEFT = SettingKey("pref_novel_margin_left", 0.05f)
    val MARGIN_RIGHT = SettingKey("pref_novel_margin_right", 0.05f)
    val MARGIN_TOP = SettingKey("pref_novel_margin_top", 0.03f)
    val MARGIN_BOTTOM = SettingKey("pref_novel_margin_bottom", 0.03f)

    /**
     * How the book is laid out on screen: continuous scroll, discrete pages, or a two-column
     * spread. Stored as [eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelPageLayout.PageMode]'s
     * ordinal.
     *
     * Not part of [NovelTypography]: a page mode is how the reader *moves through* the flow, not
     * a property of the type, and it cannot ride the live-stylesheet re-apply - a mode change
     * is a fresh layout, so the viewer re-renders the document rather than swapping CSS in place.
     */
    val PAGE_MODE = SettingKey("pref_novel_page_mode", 0)

    /**
     * Whether a book that declares a cover image shows it as its first page.
     *
     * Most EPUBs keep the cover out of the spine - it lives in the package metadata, pointed at
     * by `cover-image` or the legacy `<meta name="cover">` - so a reader that only walks the
     * spine opens on the title page and the cover is never seen again after the library list.
     * On by default: it is what a physical book does, and what readers expect a "book" to open
     * with. The source skips it when the spine's own first document already embeds the cover, so
     * a book that ships a real cover page is not shown it twice.
     */
    val COVER_FIRST = SettingKey("pref_novel_cover_first", true)

    /**
     * Every key above, for the [tachiyomi.core.common.preference.SettingsRegistry].
     *
     * Registration asserts global uniqueness at startup, so a key added here that is already
     * claimed elsewhere fails loudly instead of silently shadowing that setting.
     */
    val all: List<SettingKey<*>> = listOf(
        FONT_SIZE,
        FONT_SCALE,
        FONT_FAMILY,
        FONT_COLOR,
        BACKGROUND_COLOR,
        SMOOTH_TEXT,
        SHOW_IMAGES,
        JUSTIFIED,
        HYPHENATION,
        VERTICAL_WRITING,
        BOLD_TEXT,
        FORCE_DARK,
        PARAGRAPH_INDENT,
        LINE_SPACING,
        PARAGRAPH_SPACING,
        FONT_SPACING,
        MARGIN_LEFT,
        MARGIN_RIGHT,
        MARGIN_TOP,
        MARGIN_BOTTOM,
        PAGE_MODE,
        COVER_FIRST,
    )
}
// KMK <--
