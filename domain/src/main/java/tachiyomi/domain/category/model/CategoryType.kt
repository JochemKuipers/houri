package tachiyomi.domain.category.model

// KMK -->
/**
 * Role a subcategory plays, as requested in issue #57: two subcategories can share a name
 * while meaning different things ("Jane Doe" as author vs. as artist), so a classification
 * lets lists display and filter them apart.
 *
 * Stored as a raw Int on the category row (like the other KMK extension fields); `null`
 * means "unclassified" - the empty default that every existing category keeps without a
 * migration touching it. Only subcategories take these; top-level categories stay
 * `null` so the type never shows twice for one nesting level.
 */
enum class CategoryType {
    AUTHOR,
    ARTIST,
    ILLUSTRATOR,
    DESIGNER,
    ;

    companion object {
        // The management screens persist an Int; keep this the single mapping so the
        // dialog and the lists cannot drift apart on which number means which role.
        fun fromStored(value: Int?): CategoryType? =
            if (value == null) null else entries.getOrElse(value) { null }
    }
}
// KMK <--
