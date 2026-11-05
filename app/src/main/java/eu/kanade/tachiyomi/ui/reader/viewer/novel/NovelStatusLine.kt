// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * The reader's status line: the one-line summary Moon+ Reader draws in the page margin
 * (decompiled `PrefStatusSetting`: `status_show_chapter` / `status_show_progress` /
 * `status_show_time`), ported to an overlay beside the book.
 *
 * Everything here is *formatting*, not acquisition: the caller resolves the clock, the progress
 * fraction and the battery level from their own sources and hands the resolved values in, so the
 * whole thing stays pure and assertable off-device. A field whose value is null contributes
 * nothing — including no separator — rather than rendering a dash for data the device has not
 * reported yet.
 */
object NovelStatusLine {

    /** What the line may show, in the order the fields render when all are on. */
    enum class Field {
        /** Wall-clock time, resolved by the host. */
        TIME,

        /** How far through the chapter the reader is, 0..100. */
        PROGRESS,

        /** The name of the section on screen — a spine item's title, a webnovel chapter heading. */
        CHAPTER,

        /** Battery level, 0..100, resolved by the host. */
        BATTERY,
        ;

        companion object {
            /** The fields a stored ordinal set names; unknown ordinals are dropped, not fatal. */
            fun fromOrdinals(values: Set<Int>): Set<Field> =
                values.mapNotNull { entries.getOrNull(it) }.toSet()
        }
    }

    /** Where the line sits. Stored as the ordinal. */
    enum class Position { TOP, BOTTOM }

    /**
     * The rendered line, or "" when no enabled field has a value.
     *
     * [time] arrives pre-formatted (the host owns the locale), [batteryPct] as 0..100 or null
     * while no reading exists yet, [chapter] as the document's own name. The separator is a
     * parameter only so the harness can assert against an unambiguous one; the reader passes the
     * middle dot.
     */
    fun format(
        fields: Set<Field>,
        time: String?,
        progressPct: Int?,
        chapter: String?,
        batteryPct: Int?,
        separator: String = " · ",
    ): String {
        if (fields.isEmpty()) return ""
        val parts = mutableListOf<String>()
        if (Field.TIME in fields && !time.isNullOrBlank()) parts += time
        if (Field.PROGRESS in fields && progressPct != null) parts += "$progressPct%"
        if (Field.CHAPTER in fields && !chapter.isNullOrBlank()) parts += chapter
        if (Field.BATTERY in fields && batteryPct != null) parts += "$batteryPct%"
        return parts.joinToString(separator)
    }
}
// KMK <--
