// KMK -->
package mihon.core.archive

/**
 * Pulls a single footnote's markup out of a content document, by the id a note reference points
 * at — the decompiled Moon+ Reader `getFootnoteHtml` job, reduced to what a popup needs and made
 * safe for hostile input.
 *
 * ### Why string surgery and not a parser
 *
 * The extraction runs on the tap path: the reader has just been shown a note reference, and the
 * answer has to arrive before the impression of the tap fades. A pull-parser walk that
 * reconstructs an element's outer markup has to replay every attribute byte-perfectly; a bounded
 * scan of the raw text does not reconstruct anything, it *copies* it. Moon+'s own implementation
 * scans for `id="…"` and the enclosing `</aside>` for the same reason, and EPUB 3 note markup is
 * exactly the shape that scan serves: an `<aside epub:type="footnote">` (or an `<li>` in an
 * endnotes list) whose id the reference names.
 *
 * ### Bounded by construction
 *
 * Both inputs are attacker-controlled — a book names any id and carries any markup — so the scan
 * has two hard ceilings: it never starts before the first tag containing the id, and it never
 * copies more than [MAX_NOTE_CHARS] characters. A note that outgrows the ceiling (or whose
 * enclosing tag never arrives) returns null, and the caller falls back to the link's ordinary
 * behaviour — navigating to the note — which is always available and never wrong.
 */
object EpubFootnotes {

    /** The longest markup a popup will hold. Real footnotes are paragraphs; this is generous. */
    const val MAX_NOTE_CHARS = 32_000

    /**
     * The markup of the element whose `id` (or legacy `name`) is [targetId], up to and including
     * its closing tag, or null when the document does not name one, the enclosing close tag is
     * missing, or the note would exceed [MAX_NOTE_CHARS].
     *
     * The close tag is the *matched element's own*: the scan reads the tag name off the opening
     * tag it found and searches for that close. A fixed list of likely closers is how a first
     * draft got this wrong — an `<aside>` note containing a link closed at the first `</a>`,
     * and a legacy `<a name>` note never closed at all.
     */
    fun extract(markup: String, targetId: String): String? {
        if (markup.isEmpty() || targetId.isEmpty()) return null
        val start = findElementStart(markup, targetId) ?: return null

        val name = ELEMENT_NAME.matchAt(markup, start)?.groupValues?.get(1) ?: return null
        val close = "</$name"
        val at = markup.indexOf(close, start, ignoreCase = true)
        if (at == -1) return null
        // Over the closing bracket, so the returned markup ends with a complete tag.
        val end = markup.indexOf('>', at).takeIf { it != -1 } ?: return null

        if (end - start > MAX_NOTE_CHARS) return null

        val note = markup.substring(start, end + 1)
        // An id-bearing shell with no visible text at all is a placeholder, not a note — handing
        // it to the popup draws an empty card where the reader expected the explanation. The
        // check walks the note with its tags removed, because the tags themselves are full of
        // non-space characters and a first draft that searched the raw markup matched on its
        // own `id=` attribute.
        val visible = note.replace(TAG, " ")
        return note.takeIf { visible.any { c -> !c.isWhitespace() } }
    }

    /**
     * The offset of the opening tag that carries [targetId], or null.
     *
     * Matches both quote spellings and the legacy `<a name="…">` anchor, anchored to a real tag
     * opening so the scan cannot start inside an attribute value that merely *mentions* the id.
     */
    private fun findElementStart(markup: String, targetId: String): Int? {
        val quoted = Regex.escape(targetId)
        val patterns = listOf(
            Regex("""<\w+[^>]*?\bid\s*=\s*"$quoted"[^>]*>""", RegexOption.IGNORE_CASE),
            Regex("""<\w+[^>]*?\bid\s*=\s*'$quoted'[^>]*>""", RegexOption.IGNORE_CASE),
            Regex("""<a\b[^>]*?\bname\s*=\s*["']$quoted["'][^>]*>""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(markup)
            if (match != null) return match.range.first
        }
        return null
    }

    /** The element name at the head of a matched opening tag: `<aside` → `aside`. */
    private val ELEMENT_NAME = Regex("""<(\w+)""")

    /** One complete tag, for the emptiness check. */
    private val TAG = Regex("""<[^>]*>""")
}
// KMK <--
