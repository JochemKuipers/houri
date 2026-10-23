package mihon.core.archive

import eu.kanade.tachiyomi.source.text.TextSearchHit

/**
 * Cap on hits one query returns, so a common word in a long book cannot allocate forever.
 *
 * File-level because the transforms below are: they touch no archive, no file and no parser, which
 * is exactly what makes them the part of the book pipeline worth asserting off-device. Pulling
 * `EpubTextSource` into an offline check would drag `EpubReader` and the whole archive chain with it.
 * See `scripts/kotlinc-check/harness/DocumentHarness.kt`.
 *
 * They are also the part most likely to be quietly wrong — each is a heuristic over markup a book's
 * own producer wrote — and a heuristic with no test is a heuristic nobody can safely change.
 */
const val MAX_SEARCH_HITS = 200

/** Characters of context either side of a match in a snippet. See [MAX_SEARCH_HITS]. */
const val SNIPPET_PADDING = 40

private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
private val TAG = Regex("(?s)<[^>]*>")
private val WHITESPACE = Regex("[ \\t\\r\\n\\u00A0]+")

private val IMG = Regex("(?is)<img\\b[^>]*>")
private val EMPTY_FIGURE = Regex("(?is)<figure\\b[^>]*>\\s*</figure>")
private val EMPTY_PICTURE = Regex("(?is)<picture\\b[^>]*>\\s*</picture>")
private val CSS_IMAGE_URL = Regex("(?i)url\\(\\s*['\"]?[^)'\"]+\\.(?:jpe?g|png|gif|webp|bmp|svg)['\"]?\\s*\\)")

// The block elements a description breaks its paragraphs on, opening or closed: textContent()
// emits opening tags but not the nested ones' closing tags, so both spellings have to break.
private val BLOCK_TAG = Regex(
    "(?i)</?\\s*(?:p|div|h[1-6]|li|ul|ol|blockquote|section|article|figure|figcaption|table|tr|pre|hr)" +
        "\\b[^>]{0,127}>",
)
private val BREAK_TAG = Regex("(?i)<\\s*br\\s*/?\\s*>")

/** Bounded to a name and a short run of attributes, so a stray `<` in prose is left alone. */
private val TAG_SHAPED = Regex("</?[A-Za-z][^>]{0,127}>")

/** The body between `&` and `;` — `#8212`, `#x2014` or a name — so one group feeds every case. */
private val ENTITY = Regex("&(#[xX][0-9A-Fa-f]{1,6}|#[0-9]{1,7}|[a-zA-Z]+);")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "mdash" to "\u2014",
    "ndash" to "\u2013",
    "hellip" to "\u2026",
    "lsquo" to "\u2018",
    "rsquo" to "\u2019",
    "ldquo" to "\u201C",
    "rdquo" to "\u201D",
    "copy" to "\u00A9",
    "trade" to "\u2122",
    "reg" to "\u00AE",
    "bull" to "\u2022",
)

private val SPACE_RUN = Regex("[ \\t\\r\\u00A0]+")
private val SPACE_AROUND_BREAK = Regex(" *\\n *")
private val BLANK_LINES = Regex("\\n{3,}")

/**
 * Strips markup down to the words a reader would search for.
 *
 * Scripts, styles and heads go first: their contents are not prose, and a `<style>` body is long
 * enough to dominate the count in a book that barely has any text. Whitespace is collapsed so
 * pretty-printed markup does not inflate an offset relative to what the renderer lays out — the two
 * have to agree, or a search result lands in the wrong place.
 */
fun plainTextOf(markup: String): String =
    markup
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(TAG, " ")
        .replace(WHITESPACE, " ")

/**
 * Turns a `dc:description` into the plain prose the details screen renders.
 *
 * The description field is shown as markdown, not HTML: handed the producer's authored markup,
 * the screen prints the tags and entities as literal text — `<p>When Harry Potter…` and
 * `south&#8212;and`. The parser keeps the markup on purpose (the round-trip stays lossless and the
 * caller decides how to present it); this is that decision for the library.
 *
 * Paragraph breaks survive as blank lines, which is how markdown separates paragraphs, and the
 * entities a producer wrote as `&#8212;` decode to their character. A tag-shaped sequence is only
 * stripped when it names a plausible element, so a stray `<` in prose is not eaten. Regex rather
 * than a parse, for the same reason [stripImages] is: a malformed description costs the reader
 * their formatting, never the whole library refresh.
 */
fun descriptionTextOf(markup: String): String =
    markup
        .replace(SCRIPT_OR_STYLE, " ")
        .replace(BREAK_TAG, "\n")
        .replace(BLOCK_TAG, "\n\n")
        .replace(TAG_SHAPED, " ")
        .decodeEntities()
        .replace(SPACE_RUN, " ")
        .replace(SPACE_AROUND_BREAK, "\n")
        .replace(BLANK_LINES, "\n\n")
        .trim()

/**
 * Resolves the HTML entities a book's producer wrote into the characters they name.
 *
 * Numeric references are the long tail — `&#8212;` for an em dash appears in every description
 * that passed through a tool that escaped its own markup — and decode fully, including hex. The
 * named table is the short list that actually occurs in book metadata; an unlisted name passes
 * through untouched rather than being eaten, because losing a word to a failed lookup is worse
 * than showing an odd `&mdash;` the eye skips over.
 */
private fun String.decodeEntities(): String = replace(ENTITY) { match ->
    val body = match.groupValues[1]
    val decoded = when {
        body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
        body.startsWith("#") -> body.substring(1).toIntOrNull()
        else -> null
    }
    when {
        decoded != null -> decoded.asStringOrNull() ?: match.value
        body.startsWith("#") -> match.value
        else -> NAMED_ENTITIES[body] ?: match.value
    }
}

/** A code point as text, or null when it is not one a string may hold (surrogates, zero, too big). */
private fun Int.asStringOrNull(): String? =
    if (this in 1..0x10FFFF && this !in 0xD800..0xDFFF) String(Character.toChars(this)) else null

/**
 * Removes a book's inline images, for a reader who wants the prose without the plates.
 *
 * Elements are *removed* rather than broken: an `<img>` that cannot load still occupies a line, so
 * stripping the element reflows the text around where it was while leaving a broken icon and a gap.
 * On a device where decoding every illustration of a long novel is the slowest part of turning the
 * page, that is the difference between usable and not.
 *
 * Regex rather than a parse: this runs on markup a book's producer wrote, so a malformed document is
 * expected rather than exceptional, and a parser that threw on one would lose the whole chapter over a
 * preference. The elements removed have no text content of their own, so removing the tags is
 * sufficient — there is nothing to preserve inside them.
 */
fun stripImages(markup: String): String =
    markup
        .replace(IMG, "")
        .replace(EMPTY_FIGURE, "")
        .replace(EMPTY_PICTURE, "")
        // A leftover reference inside inline CSS would 404 with nothing left to show for it.
        .replace(CSS_IMAGE_URL, "")

/**
 * Case-insensitive search over stripped text, returning displayable snippets.
 *
 * [plainLength] is the whole document's length in these same units. It travels on every hit so a host
 * can turn [TextSearchHit.documentOffset] into a position without re-deriving the text — and it has
 * to be the *stripped* length, because the host holds markup and the two are not interchangeable.
 *
 * `pageIndex` is stamped by the caller, since this knows nothing about which document it searched.
 */
fun String.indexesOf(needle: String, plainLength: Int): List<TextSearchHit> {
    if (isEmpty() || needle.length > length) return emptyList()
    val hits = mutableListOf<TextSearchHit>()
    var from = 0
    while (hits.size < MAX_SEARCH_HITS) {
        val at = indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        val start = (at - SNIPPET_PADDING).coerceAtLeast(0)
        val end = (at + needle.length + SNIPPET_PADDING).coerceAtMost(length)
        val snippet = substring(start, end)
        hits += TextSearchHit(
            pageIndex = 0,
            snippet = snippet,
            // Measured against the trimmed snippet, so the host highlights the characters the reader
            // can actually see rather than an offset shifted by the trim.
            matchOffset = at - start - (snippet.length - snippet.trimStart().length),
            documentOffset = at,
            documentLength = plainLength,
        )
        from = at + needle.length
    }
    return hits
}
