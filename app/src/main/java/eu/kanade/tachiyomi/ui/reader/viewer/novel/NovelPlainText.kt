// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Turns a webnovel's plain text into the HTML the reader's document pipeline expects.
 *
 * ### Why this exists
 *
 * `TextPage.Kind.PLAIN` documents a contract the reader never honoured: *"Line breaks in [text]
 * are paragraph breaks."* A source that shipped plain text had its markup handed to the WebView
 * as-is, where the engine's whitespace collapsing merged the whole chapter into one wall of text.
 * This is the conversion that contract asks for, plus the cleanup Moon+ Reader ships for the same
 * material ("intelligent paragraph; indent paragraph; trim unwanted blank spaces" — decompiled
 * `BaseEBook` / `A.trimBlankSpace`), because webnovel text is machine-assembled and arrives with
 * the scars of that assembly: triple blank lines between paragraphs, stray leading spaces, and
 * paragraph breaks spelled as a single `\n` with no blank line between them.
 *
 * ### The two paragraph conventions, and why "intelligent" is a setting
 *
 * Prose conventions disagree about what one newline means. A novel pasted from a word processor
 * separates paragraphs with a blank line and uses single newlines only by accident (hard-wrapped
 * source); a webnovel chapter posted through a CMS usually breaks one paragraph per line with no
 * blank line anywhere. Reading the first under the second's rule shatters every line into a
 * paragraph; reading the second under the first's rule merges the whole chapter into one. So:
 *
 * - `smartParagraphs = true` (the default, and `PLAIN`'s documented contract): every non-blank
 *   line is a paragraph; blank lines are paragraph separators.
 * - `smartParagraphs = false`: paragraphs are separated only by blank lines; single newlines
 *   become `<br>` — the word-processor convention, which keeps hard-wrapped verse intact.
 *
 * No Android imports on purpose — pure, so it is asserted off-device.
 */
object NovelPlainText {

    /** The read-aloud chunk budget: big enough to speak smoothly, small enough to feel responsive. */
    const val SPEECH_CHUNK_CHARS = 1_800

    /**
     * The cleanup passes applied while converting, in the order they run.
     *
     * All default to on: the material this exists for is machine-assembled text, and every pass
     * here only removes noise a human would have removed before publishing. A source whose text
     * is deliberately space- or indent-shaped loses nothing structural — the conversions never
     * touch a paragraph's own characters, only the whitespace between and around lines.
     */
    data class Cleanup(
        /** Collapse runs of blank lines into nothing — a `<p>` boundary already separates. */
        val trimBlankLines: Boolean = true,
        /** Strip leading/trailing spaces and tabs from every line, and collapse internal runs. */
        val trimSpaces: Boolean = true,
        /** Treat a single `\n` as a paragraph break rather than a soft wrap. */
        val smartParagraphs: Boolean = true,
    ) {
        /**
         * A stable, short token for the reader's inline-cache key.
         *
         * The cache stores documents *built from* the converted text, so a cleanup change has to
         * re-build, and the key has to say which cleanup built it. Three booleans spell as three
         * digits — compact, and stable across app runs by construction.
         */
        val cacheToken: String
            get() = "plain-${if (trimBlankLines) 1 else 0}${if (trimSpaces) 1 else 0}${if (smartParagraphs) 1 else 0}"
    }

    /**
     * Converts [text] to the paragraph HTML the reader renders, under [cleanup].
     *
     * The result is a *fragment* — `<p>` elements, no wrapper — because both of its consumers
     * already speak fragments: [NovelDocument] wraps a body-less document in a shell, and the
     * continuous window appends body fragments. Character data is HTML-escaped first, so text
     * that contains `<` or `&` — a webnovel scene with a literal `<HP+10>` tag is not hypothetical
     * — renders as the characters the author wrote rather than being eaten as markup.
     */
    fun toHtml(text: String, cleanup: Cleanup): String {
        if (text.isBlank()) return ""
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paragraphs = mutableListOf<String>()
        // The paragraph under construction, as its raw (unescaped) lines. Escaping happens once,
        // at flush, per line — which is what keeps the soft-wrap `<br>` a *tag* and the text's
        // own `<` characters *entities*: one is inserted by us after escaping, the other was in
        // the text and is escaped with it. A buffer that concatenated them before escaping would
        // turn our separator into visible "<br>" text.
        var current = mutableListOf<String>()

        fun flush() {
            if (current.isNotEmpty()) {
                val body = current.joinToString("<br>") { escape(it) }
                paragraphs += "<p>$body</p>"
            }
            current = mutableListOf()
        }

        for (rawLine in lines) {
            var line = rawLine
            if (cleanup.trimSpaces) line = cleanSpaces(line)
            if (line.isBlank()) {
                // A blank line ends the paragraph under either convention. Under the smart one a
                // blank line carries no further meaning — the `<p>` boundary is the separator —
                // so runs of them collapse to a single flush (which is what trimBlankLines asks
                // for: they never became extra empty paragraphs in the first place).
                flush()
                continue
            }
            if (cleanup.smartParagraphs) {
                flush()
                current += line
            } else {
                current += line
            }
        }
        flush()
        return paragraphs.joinToString("")
    }

    /**
     * Trims the incidental whitespace a line can carry and collapses internal runs of it.
     *
     * The ideographic space `U+3000` is left alone: it is a paragraph's first-line indent that a
     * CJK author chose, not noise, and stripping it would un-indent every Chinese or Japanese
     * webnovel paragraph this pass touches. The reader's own typography adds the indent back
     * where the reader asked for one — which is exactly why the publisher's own must survive.
     */
    private fun cleanSpaces(line: String): String =
        line.trim(' ', '\t')
            .replace(Regex("[ \\t]{2,}"), " ")

    /** Escapes the characters that would otherwise be read as markup. */
    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    /**
     * Splits [text] into speakable chunks of at most [maxChars] characters, cut at sentence
     * boundaries where the text offers them — the read-aloud engine's queue units.
     *
     * Sentence ends are a manual scan, not a regex split: the ends are CJK marks, `!`/`?`, and an
     * ASCII period *followed by whitespace* (so `1.5` and `e.g.` stay whole), and a zero-width
     * regex split over them reads differently across Kotlin versions. A newline is a boundary
     * too — a paragraph break is a pause, and pauses are what the chunker exists to place.
     *
     * An empty or whitespace-only input yields no chunks: speaking silence would only make the
     * engine's end-of-document callback indistinguishable from progress.
     */
    fun chunkForSpeech(text: String, maxChars: Int = SPEECH_CHUNK_CHARS): List<String> {
        val source = text.trim()
        if (source.isEmpty()) return emptyList()

        // 1. Cut into sentences at the boundaries the text itself offers.
        val sentences = mutableListOf<String>()
        var begin = 0
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val sentenceEnd = c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n' ||
                (c == '.' && (i == source.length - 1 || source[i + 1].isWhitespace()))
            if (sentenceEnd) {
                var end = i + 1
                while (end < source.length && source[end].isWhitespace()) end += 1
                sentences += source.substring(begin, end).trim()
                begin = end
                i = end
            } else {
                i += 1
            }
        }
        if (begin < source.length) sentences += source.substring(begin).trim()

        // 2. Pack sentences into chunks within the budget, hard-splitting an oversized one.
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            val piece = sentence.trim()
            if (piece.isEmpty()) continue
            if (current.isNotEmpty() && current.length + piece.length + 1 > maxChars) {
                chunks += current.toString().trim()
                current.clear()
            }
            if (piece.length > maxChars) {
                // One sentence longer than the budget: hard-split it.
                var start = 0
                while (start < piece.length) {
                    val end = minOf(start + maxChars, piece.length)
                    chunks += piece.substring(start, end).trim()
                    start = end
                }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotEmpty() }
    }
}
// KMK <--
