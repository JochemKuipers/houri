package eu.kanade.tachiyomi.ui.reader.viewer.novel

import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TocEntry

/**
 * One row of the reader's table of contents, flattened and ready to render.
 *
 * ### Why a flat list with a depth
 *
 * EPUB 3 nav documents nest arbitrarily deep — five and six levels are ordinary — and a `LazyColumn`
 * wants a flat list. Indenting by [depth] keeps the structure visible without the nesting, and
 * clamping the indent stops a six-deep entry from pushing its label off the screen.
 *
 * ### Why entries can be untappable
 *
 * A book-wide table of contents points at files that are not in the chapter currently open; those are
 * the common case for any book split into several EPUBs. Rather than silently doing nothing on tap —
 * which reads as a broken button — they are carried as [reachable] `false` and rendered disabled.
 *
 * No Android imports, so the traversal is asserted by `scripts/kotlinc-check`.
 */
data class NovelTocEntry(
    val label: String,
    /** 0 for a top-level entry. Clamped for layout, not for meaning. */
    val depth: Int,
    /** Archive path of the destination document, with any fragment removed. */
    val document: String,
    /** Element id inside [document], or null when the entry targets the whole file. */
    val fragment: String?,
    /** False when [document] is not among the pages of the chapter on screen. */
    val reachable: Boolean,
    /** True when this entry is the one the reader is already on. */
    val current: Boolean,
)

/**
 * Flattens a table of contents into renderable rows.
 *
 * [availableDocuments] is the set of archive paths the open chapter actually loaded; an entry whose
 * document is not in it is marked unreachable rather than dropped, so the structure of the book stays
 * visible instead of appearing to be missing.
 *
 * @param currentDocument the document on screen, or null when the reader is between documents.
 */
fun flattenToc(
    entries: List<TocEntry>,
    availableDocuments: Set<String>,
    currentDocument: String? = null,
): List<NovelTocEntry> {
    val rows = mutableListOf<NovelTocEntry>()

    fun walk(entry: TocEntry, depth: Int) {
        val document = entry.href.substringBefore('#').trim()
        rows += NovelTocEntry(
            // A nav document with an empty <a> is malformed but not rare; a blank row is worse than a
            // row labelled from its destination, and tapping it still works.
            label = entry.label.ifBlank { document },
            depth = depth,
            document = document,
            fragment = entry.href.substringAfter('#', "").ifBlank { null },
            reachable = document.isNotEmpty() && document in availableDocuments,
            current = currentDocument != null && document == currentDocument && entry.href.substringAfter('#', "").isBlank(),
        )
        entry.children.forEach { walk(it, depth + 1) }
    }

    entries.forEach { walk(it, 0) }
    return rows
}

// KMK --> The status line's CHAPTER field: the name of the section on screen, resolved from the
// book's own table of contents when it names one. A book is one library chapter, so the chapter
// *name* is the novel's title — reporting that as "the chapter" was the field's whole defect.
// KMK <--

/**
 * One label per document the ToC names, for the status line's chapter field.
 *
 * The outermost entry naming a document wins: nav files often list a chapter twice (a top-level
 * "Chapter 1" and a deeper sub-section pointing into the same file), and the first is the one a
 * reader would call the chapter. Both the raw href spelling and its percent-decoded form are
 * keyed — the ToC speaks hrefs, the page speaks archive paths, and producers disagree on which
 * one is encoded.
 *
 * Pure, like everything else here.
 */
fun tocLabelsOf(entries: List<TocEntry>): Map<String, String> {
    val labels = LinkedHashMap<String, String>()
    fun walk(entry: TocEntry) {
        val document = entry.href.substringBefore('#').trim()
        if (document.isNotEmpty() && entry.label.isNotBlank()) {
            val label = entry.label.trim()
            if (document !in labels) labels[document] = label
            val decoded = percentDecode(document)
            if (decoded.isNotEmpty() && decoded !in labels) labels[decoded] = label
        }
        entry.children.forEach { walk(it) }
    }
    entries.forEach { walk(it) }
    return labels
}

/**
 * A human label for a document when the ToC has none: its own file name, extension stripped,
 * percent-decoded. `OEBPS/Text/Chapter%201.xhtml` reads as "Chapter 1". Null when the URL gives
 * nothing to show.
 */
fun documentLabelOf(documentUrl: String?): String? {
    val path = documentUrl?.trim().orEmpty()
    if (path.isEmpty()) return null
    val name = path.substringAfterLast('/')
    if (name.isEmpty()) return null
    val stem = name.substringBeforeLast('.').ifBlank { name }
    return percentDecode(stem).ifBlank { null }
}

/**
 * Percent-decodes a path segment leniently: `%41` → `A`, multi-byte sequences decoded as UTF-8,
 * and anything malformed (`%G1`, a trailing `%`) left exactly as it was rather than throwing —
 * a book's own naming is untrusted input, and the label is cosmetic.
 */
fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = ByteArray(value.length)
    var size = 0
    var i = 0
    while (i < value.length) {
        val c = value[i]
        // A hex pair needs the two characters after the '%'; anything else is literal.
        if (c == '%' && i + 2 < value.length) {
            val hi = value[i + 1].hexDigitOrNull()
            val lo = value[i + 2].hexDigitOrNull()
            if (hi != null && lo != null) {
                bytes[size++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        bytes[size++] = c.code.toByte()
        i += 1
    }
    return runCatching { String(bytes, 0, size, Charsets.UTF_8) }.getOrDefault(value)
}

private fun Char.hexDigitOrNull(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}

/**
 * One row of the reader's in-chapter search results.
 *
 * @property pageIndex document the match is in.
 * @property fraction how far through that document the match sits, 0..1.
 * @property snippet the text around the match.
 * @property matchOffset where in [snippet] the match begins, for highlighting.
 */
data class NovelSearchResult(
    val pageIndex: Int,
    val fraction: Float,
    val snippet: String,
    val matchOffset: Int,
)

/**
 * Turns source hits into result rows.
 *
 * ### Why a fraction, not a character offset
 *
 * [TextSearchHit.documentOffset] is an offset into the page's **plain text** — markup stripped,
 * whitespace collapsed. The document the reader shows is the **markup**: a tag the strip removed is
 * still a real position in it, and pretty-printed HTML contributes whitespace the strip discarded. An
 * offset therefore cannot be turned back into a scroll position exactly, and pretending otherwise
 * would drop the reader a paragraph from the match with nothing to suggest why.
 *
 * So only the *ratio* crosses over — the same quantity [NovelProgress.fractionInDocument] uses, and
 * stable across font sizes, screen sizes and orientations. The document is resolved exactly; the
 * position inside it is approximate and lands in the right part of the chapter.
 *
 * A hit whose source did not report a document length keeps `documentLength == 0`, which happens for
 * an extension predating that field. Those rows are kept and land at the top of their document rather
 * than being dropped — a result the reader can see and act on beats a silently shorter list.
 */
fun toSearchResults(hits: List<TextSearchHit>): List<NovelSearchResult> = hits.map { hit ->
    val length = hit.documentLength
    NovelSearchResult(
        pageIndex = hit.pageIndex,
        fraction = if (length <= 0) 0f else (hit.documentOffset.toFloat() / length).coerceIn(0f, 1f),
        snippet = hit.snippet,
        matchOffset = hit.matchOffset.coerceIn(0, hit.snippet.length),
    )
}
