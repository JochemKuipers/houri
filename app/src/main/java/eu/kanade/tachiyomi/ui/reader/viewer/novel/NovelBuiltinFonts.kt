// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * The typefaces the app carries in `app/src/main/assets/novel-fonts/`: a short list of curated
 * variable OpenType fonts, one file per family, each with its `OFL.txt` beside the set.
 *
 * ### Why bundled at all
 *
 * The font setting used to offer four CSS generic keywords plus whatever the reader imported by
 * hand. `serif` is not a typeface — it is an instruction to the WebView to pick one by itself, and
 * each OEM resolves it differently, so the same book read on two devices never quite matches. A
 * shipped set turns "a serif that reads well in a book" into a *named* answer the reader can pick
 * and rely on, without any network fetch or per-device substitution.
 *
 * ### Variable fonts, one file per family
 *
 * Each file carries its whole weight axis, so `NovelTypography.boldText` gets **real** weights
 * rather than the engine's synthetic bold, and every weight in between stays available for a book
 * that sets its own. One file per family — rather than the four-file regular/italic/bold set a
 * static font needs — is what keeps the whole bundle inside a few megabytes of APK while still
 * giving Book-style type to every serif here.
 *
 * ### The stored value
 *
 * Family values follow the same prefix convention as the reader-imported ones
 * ([NovelFonts.PREFIX]): `"builtin/<Family>"`, where `<Family>` is the display name. The prefix
 * cannot collide with a CSS generic keyword (`serif`, `monospace`…), and the whole value is also
 * the CSS family name — quoted by [NovelTypographyCss.quoteFamily], which handles the slash — so
 * the `@font-face` declaration and the `font-family` rule cannot disagree about what was chosen.
 *
 * No Android imports on purpose: [familyValues] is asserted off-device like the rest of the
 * pure-value reader settings.
 */
object NovelBuiltinFonts {

    /** Marks a font family value as one the app carries. */
    const val PREFIX = "builtin/"

    /**
     * What a family is for.
     *
     * Also the generic the picker's preview falls back to when a face cannot be read for a preview
     * ([Category.cssFallback] is a CSS keyword on purpose: the generic families the reader offers
     * are exactly these keywords).
     */
    enum class Category(val cssFallback: String) {
        Serif("serif"),
        Sans("sans-serif"),
        Mono("monospace"),
        Handwriting("cursive"),
    }

    /** One bundled family. [asset] is a path under the application's asset root. */
    data class Family(
        /** Display name, and the half of the stored value after [PREFIX]. */
        val name: String,
        /** Asset path the face is read from, e.g. `novel-fonts/literata.ttf`. */
        val asset: String,
        /** Group the picker shows the family under, and the preview's generic fallback. */
        val category: Category,
    )

    /**
     * The bundle, in picker order: serif, sans, mono, handwriting — the order of classical
     * type-setting, which is the order a reader decides in.
     *
     * Every file here is an OpenType variable font under the SIL Open Font License, whose full
     * text travels with the set under the same directory's `licenses/` — the licence must be
     * delivered with the font it covers, not linked.
     */
    val all: List<Family> = listOf(
        Family("Literata", "novel-fonts/literata.ttf", Category.Serif),
        Family("Lora", "novel-fonts/lora.ttf", Category.Serif),
        Family("Source Serif 4", "novel-fonts/sourceserif4.ttf", Category.Serif),
        Family("Bitter", "novel-fonts/bitter.ttf", Category.Serif),
        Family("Vollkorn", "novel-fonts/vollkorn.ttf", Category.Serif),
        Family("Playfair Display", "novel-fonts/playfairdisplay.ttf", Category.Serif),
        Family("Crimson Pro", "novel-fonts/crimsonpro.ttf", Category.Serif),
        Family("Inter", "novel-fonts/inter.ttf", Category.Sans),
        Family("Source Sans 3", "novel-fonts/sourcesans3.ttf", Category.Sans),
        Family("Open Sans", "novel-fonts/opensans.ttf", Category.Sans),
        Family("Karla", "novel-fonts/karla.ttf", Category.Sans),
        Family("Rubik", "novel-fonts/rubik.ttf", Category.Sans),
        Family("Atkinson Hyperlegible Next", "novel-fonts/atkinsonhyperlegiblenext.ttf", Category.Sans),
        Family("JetBrains Mono", "novel-fonts/jetbrainsmono.ttf", Category.Mono),
        Family("Source Code Pro", "novel-fonts/sourcecodepro.ttf", Category.Mono),
        Family("Caveat", "novel-fonts/caveat.ttf", Category.Handwriting),
    )

    /** Stored family values, in picker order — [all] as two-sided strings. */
    val familyValues: List<String> = all.map { PREFIX + it.name }

    /**
     * The family a stored value names, or null for anything that is not a bundled value.
     *
     * Matched without case, so a value restored from another device, migrated by hand or typed by
     * a user who wrote "literata" still finds its own file — the display name is what the list
     * shows, not what a nibble of case must guarantee.
     */
    fun byValue(value: String): Family? {
        val name = value.removePrefix(PREFIX)
        if (name == value) return null
        return all.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
// KMK <--
