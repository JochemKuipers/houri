// KMK -->
package eu.kanade.tachiyomi.ui.reader.setting

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelProgress
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.SettingKey

/**
 * Reads and writes [NovelTypography], and persists reading positions for text chapters.
 *
 * Every accessor passes `SettingKey.key` and `SettingKey.default` through to the store, so the
 * defaults live in exactly one place ([NovelTypographySettingKeys]) and cannot drift from the UI.
 *
 * ### Reading position
 *
 * Stored as a [NovelProgress.Position] — document index plus character offset — rather than as a
 * page index, because a page index in a reflowable document means something different on every
 * device and at every font size. See [NovelProgress] for the reasoning.
 */
@SingleIn(AppScope::class)
@Inject
class NovelTypographyPreferences(private val store: PreferenceStore) {

    // ------------------------------------------------------------ typography

    val fontSize: Preference<Int>
        get() = store.getInt(NovelTypographySettingKeys.FONT_SIZE.key, NovelTypographySettingKeys.FONT_SIZE.default)

    val fontScale: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.FONT_SCALE.key, NovelTypographySettingKeys.FONT_SCALE.default)

    val fontFamily: Preference<String>
        get() = store.getString(NovelTypographySettingKeys.FONT_FAMILY.key, NovelTypographySettingKeys.FONT_FAMILY.default)

    val fontColor: Preference<String>
        get() = store.getString(NovelTypographySettingKeys.FONT_COLOR.key, NovelTypographySettingKeys.FONT_COLOR.default)

    val backgroundColor: Preference<String>
        get() = store.getString(
            NovelTypographySettingKeys.BACKGROUND_COLOR.key,
            NovelTypographySettingKeys.BACKGROUND_COLOR.default,
        )

    val smoothText: Preference<Boolean>
        get() = store.getBoolean(
            NovelTypographySettingKeys.SMOOTH_TEXT.key,
            NovelTypographySettingKeys.SMOOTH_TEXT.default,
        )

    val lineSpacing: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.LINE_SPACING.key, NovelTypographySettingKeys.LINE_SPACING.default)

    val paragraphSpacing: Preference<Float>
        get() = store.getFloat(
            NovelTypographySettingKeys.PARAGRAPH_SPACING.key,
            NovelTypographySettingKeys.PARAGRAPH_SPACING.default,
        )

    val fontSpacing: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.FONT_SPACING.key, NovelTypographySettingKeys.FONT_SPACING.default)

    val marginLeft: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.MARGIN_LEFT.key, NovelTypographySettingKeys.MARGIN_LEFT.default)

    val marginRight: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.MARGIN_RIGHT.key, NovelTypographySettingKeys.MARGIN_RIGHT.default)

    val marginTop: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.MARGIN_TOP.key, NovelTypographySettingKeys.MARGIN_TOP.default)

    val marginBottom: Preference<Float>
        get() = store.getFloat(NovelTypographySettingKeys.MARGIN_BOTTOM.key, NovelTypographySettingKeys.MARGIN_BOTTOM.default)

    // ------------------------------------------------------------ tri-state

    private val justifiedPref: Preference<String>
        get() = store.getString(NovelTypographySettingKeys.JUSTIFIED.key, NovelTypographySettingKeys.JUSTIFIED.default)

    private val hyphenationPref: Preference<String>
        get() = store.getString(NovelTypographySettingKeys.HYPHENATION.key, NovelTypographySettingKeys.HYPHENATION.default)

    private val verticalWritingPref: Preference<String>
        get() = store.getString(
            NovelTypographySettingKeys.VERTICAL_WRITING.key,
            NovelTypographySettingKeys.VERTICAL_WRITING.default,
        )

    /** True when the reader explicitly chose justify-on or justify-off; null to follow the book. */
    var justified: Boolean?
        get() = triState(justifiedPref.get())
        set(value) = justifiedPref.set(encodeTriState(value))

    var hyphenation: Boolean?
        get() = triState(hyphenationPref.get())
        set(value) = hyphenationPref.set(encodeTriState(value))

    var verticalWriting: Boolean?
        get() = triState(verticalWritingPref.get())
        set(value) = verticalWritingPref.set(encodeTriState(value))

    // ------------------------------------------------------------ reads / writes

    /** The current typography, sanitized so a hand-edited or migrated value cannot break layout. */
    fun typography(): NovelTypography = NovelTypography(
        fontSizePt = fontSize.get(),
        fontColor = fontColor.get().takeIf { it.isNotBlank() },
        backgroundColor = backgroundColor.get().takeIf { it.isNotBlank() },
        fontFamily = fontFamily.get(),
        smoothText = smoothText.get(),
        justified = justified,
        hyphenation = hyphenation,
        paragraphSpacing = paragraphSpacing.get(),
        lineSpacing = lineSpacing.get(),
        fontSpacing = fontSpacing.get(),
        fontScale = fontScale.get(),
        marginLeft = marginLeft.get(),
        marginRight = marginRight.get(),
        marginTop = marginTop.get(),
        marginBottom = marginBottom.get(),
        verticalWriting = verticalWriting,
    ).sanitized()

    fun setTypography(value: NovelTypography) {
        val t = value.sanitized()
        fontSize.set(t.fontSizePt)
        fontScale.set(t.fontScale)
        fontFamily.set(t.fontFamily)
        fontColor.set(t.fontColor.orEmpty())
        backgroundColor.set(t.backgroundColor.orEmpty())
        smoothText.set(t.smoothText)
        justified = t.justified
        hyphenation = t.hyphenation
        verticalWriting = t.verticalWriting
        paragraphSpacing.set(t.paragraphSpacing)
        lineSpacing.set(t.lineSpacing)
        fontSpacing.set(t.fontSpacing)
        marginLeft.set(t.marginLeft)
        marginRight.set(t.marginRight)
        marginTop.set(t.marginTop)
        marginBottom.set(t.marginBottom)
    }

    // ------------------------------------------------------------ position

    /** Stores the reading position for a text chapter. */
    fun setPosition(chapterUrl: String, position: NovelProgress.Position) {
        positionPreference(chapterUrl).set(position.encode())
    }

    /** Reads the stored position for a text chapter, or null when there is none. */
    fun position(chapterUrl: String): NovelProgress.Position? =
        NovelProgress.Position.decode(positionPreference(chapterUrl).get())

    fun clearPosition(chapterUrl: String) {
        positionPreference(chapterUrl).set("")
    }

    /**
     * Per-chapter position key.
     *
     * The chapter URL is hashed rather than embedded: a URL contains characters that are awkward in
     * a preference key, and it can be long enough to bloat one. Hashing also keeps the key count
     * sane when a library has thousands of chapters.
     *
     * Marked private so reading positions are never picked up by backup — they describe a position
     * inside a file that the user's backup does not contain.
     */
    private fun positionPreference(chapterUrl: String): Preference<String> =
        store.getString(Preference.privateKey(POSITION_KEY_PREFIX + chapterUrl.hashCode()), "")

    // ------------------------------------------------------------ helpers

    /**
     * Maps the stored tri-state string to a nullable boolean.
     *
     * Anything that is not exactly "true"/"false" reads as "follow the publisher", so a malformed
     * or migrated value degrades to the book's own styling rather than to a forced one.
     */
    private fun triState(raw: String): Boolean? = when (raw) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun encodeTriState(value: Boolean?): String = when (value) {
        true -> "true"
        false -> "false"
        null -> NovelTypographySettingKeys.NOVEL_FOLLOW_PUBLISHER
    }

    private companion object {
        const val POSITION_KEY_PREFIX = "pref_novel_position_"
    }
}
// KMK <--
