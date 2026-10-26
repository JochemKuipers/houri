package mihon.core.archive

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Serves an EPUB chapter as text, in the form a book's reader wants: one document per spine item,
 * with the publisher's markup untouched.
 *
 * ### Why this lives in `core:archive` rather than in the source that uses it
 *
 * The manga local source serves books, and so does anything else that ever grows a book half —
 * the reader and the source cannot depend on each other, and a book must read the same wherever
 * it was opened from. Everything that decides what a book *is* to a host lives here: the spine,
 * the size ceilings, the stripped plain text that search runs over, and the contents list. Two
 * implementations would drift, and the failure mode of drift is a book that reads correctly in
 * one place and not the other.
 *
 * ### Bounds, and why each is where it is
 *
 * Everything read out of the file is attacker-controlled, because the file is. Each ceiling is
 * written at the point where the allocation happens rather than at the point where it becomes
 * visible, so a hostile book costs a truncated book instead of a heap exhaustion report.
 *
 * Pure with respect to the archive: no Android imports, and the only state is the caller's.
 */
class EpubTextSource(
    private val readerFor: (SChapter) -> EpubReader?,
    // KMK -->
    /**
     * Whether the reader wants a book's declared cover prepended as the first page.
     *
     * A lambda rather than a boolean because the answer is two live preferences (show the cover
     * at all, and show illustrations at all - a text-only reading has no use for a cover image),
     * and the source must not know either of them. Evaluated on every enumeration so a settings
     * change applies to the next chapter opened.
     */
    private val wantsCoverPage: () -> Boolean = { true },
    // KMK <--
) : TextTocSource, TextSearchSource, TextIncrementalSource {

    /** What a book reader can rely on. An EPUB chapter almost always has a TOC, search and inline images. */
    val capabilities = TextCapabilities.EPUB.copy(SEARCH = true)

    /**
     * The chapter's documents, in reading order.
     *
     * Returns untouched markup. Publishers ship their own CSS and their own structure — tables, drop
     * caps, ruby, vertical writing — and the stripping that makes text extraction easier is exactly
     * what destroys the book. Cleanup belongs in the renderer.
     *
     * This is the eager path, kept for [eu.kanade.tachiyomi.source.text.TextSource.getTextPageList]:
     * it reads every spine item, because that is what a caller of that method has asked for. Hosts
     * that can read a document at a time want [getTextPageIndex] and [getTextPageText] instead — see
     * [TextIncrementalSource] for why that split is the difference between opening a novel and
     * waiting for one.
     *
     * [TextPage.index] is the **spine** index, which is sparse whenever a document is skipped. That
     * is deliberate and is what `TextSearchHit.pageIndex` is defined to carry: it is the document's
     * identity in the book, not its position in a list. A host that re-orders or drops pages must
     * map through it rather than indexing positionally.
     */
    fun textPages(chapter: SChapter): List<TextPage> {
        val indexed = indexedSpine(chapter)
        var totalChars = 0
        return buildList {
            for (page in indexed.pages) {
                // KMK --> The cover page arrives with its markup already in hand; "reading" it
                // would resolve its *image* path through the spine-item fallback and hand back the
                // first real document's markup as the cover's.
                if (page.url == indexed.coverUrl) {
                    totalChars += page.text.length
                    add(page)
                    continue
                }
                // KMK <--
                val markup = documentText(chapter, page) ?: run {
                    // Every skipped document is logged by name: a chapter that reads as empty is
                    // otherwise indistinguishable from a broken one, and this is the only line that
                    // names the file responsible.
                    logcat { "EpubTextSource: ${chapter.url} could not read spine item ${page.index} (${page.url})" }
                    continue
                }
                totalChars += markup.length
                if (totalChars > MAX_CHAPTER_CHARS) {
                    logcat { "EpubTextSource: ${chapter.url} exceeds the in-memory markup cap; truncating" }
                    break
                }
                add(page.copy(text = markup))
            }
        }.also { pages ->
            logcat {
                "EpubTextSource: ${chapter.url} -> ${pages.size} text page(s), $totalChars chars"
            }
            if (pages.isEmpty()) {
                logcat(LogPriority.ERROR) {
                    "EpubTextSource: ${chapter.url} produced NO text pages; the reader will refuse " +
                        "this chapter. Check the 'could not read spine item' lines for the cause."
                }
            }
        }
    }

    /**
     * The chapter's spine documents, in reading order, **without reading any of them**.
     *
     * The cheap half of [textPages], and the one a reader actually wants. A spine is a list of records
     * the package document already declared, so this costs nothing; [textPages] then costs one
     * inflate-and-decode per document, which for a five-hundred-document novel is the whole of the
     * reader's startup delay.
     *
     * Each page has an empty `text`. That means "not read", never "empty": a document that genuinely
     * has nothing in it is indistinguishable from one that was never opened, which is why a host asks
     * for the text explicitly rather than deciding from the list.
     *
     * Dense, where [textPages] is sparse: this cannot know which documents will fail to read without
     * reading them. That is the intended trade and it is why every consumer that locates a document by
     * identity — search above all — enumerates through here rather than through [textPages], so the
     * two can never disagree about a document's index.
     *
     * Suspend because it is not free — it opens the archive and parses the package document — which is
     * why the internal work is a plain function so that [textPages] and [plainTextFor], which are
     * already blocking by contract, can share it without becoming suspend themselves.
     */
    override suspend fun getTextPageIndex(chapter: SChapter): List<TextPage> = spineIndex(chapter)

    // KMK -->
    /**
     * The chapter's documents in reading order, **including a synthesized cover page at index 0**
     * when the reader wants one and the book declares a cover the spine does not already show.
     *
     * Every spine document after a cover shifts up by one. That is the whole cost of the feature
     * and it is paid consistently: search hits, reading positions and the page list all speak the
     * same shifted coordinates, and positions additionally record the document's URL (see
     * `NovelProgress.Position`) so an index written before the shift still resolves.
     */
    private class IndexedSpine(
        val pages: List<TextPage>,
        /** Archive path of the synthesized cover page, or null when there is none. */
        val coverUrl: String?,
    )

    private fun indexedSpine(chapter: SChapter): IndexedSpine {
        val epub = readerFor(chapter)
            ?: throw IllegalStateException("Chapter not found: ${chapter.url}")

        if (epub.hasDrm) throw IllegalStateException("This book is DRM-protected and cannot be read")

        val documents = epub.textDocuments()
        if (documents.size > MAX_DOCUMENTS) {
            logcat {
                "EpubTextSource: ${chapter.url} has ${documents.size} spine items; " +
                    "exposing the first $MAX_DOCUMENTS"
            }
        }
        val cover = coverPageFor(chapter, epub, documents)
        val pages = documents.take(MAX_DOCUMENTS).mapIndexed { index, item ->
            TextPage(
                index = index + if (cover != null) 1 else 0,
                url = item.href,
                text = "",
                kind = TextPage.Kind.HTML,
                baseHref = item.href,
                name = item.title,
            )
        }
        return IndexedSpine(
            pages = if (cover != null) listOf(cover) + pages else pages,
            coverUrl = cover?.url,
        )
    }

    /**
     * The synthesized cover page, or null when there is nothing to synthesize.
     *
     * Three gates, in the order that keeps them cheap:
     *
     * 1. the reader's preference ([wantsCoverPage]);
     * 2. the book declares a cover at all, and the archive actually contains it - a manifest
     *    that names a file the zip does not hold would otherwise add a page that renders as a
     *    broken image;
     * 3. the spine's own first document does not already embed the cover - calibre-built books
     *    usually *do* carry a cover page in the spine, and showing theirs twice is worse than
     *    not showing it at all.
     *
     * The third gate reads the first spine document, which is a real cost, so its verdict is
     * cached per chapter: the spine is enumerated on every open and again for every search, and
     * the answer depends only on the file.
     */
    private fun coverPageFor(
        chapter: SChapter,
        epub: EpubReader,
        documents: List<EpubSpineItem>,
    ): TextPage? {
        if (!wantsCoverPage()) return null
        val coverHref = epub.coverHref ?: return null
        if (!epub.contains(coverHref)) {
            logcat {
                "EpubTextSource: ${chapter.url} declares cover $coverHref but the archive does not contain it"
            }
            return null
        }
        if (coverEmbeddedInSpine(chapter, epub, documents, coverHref)) return null
        return TextPage(
            index = 0,
            url = coverHref,
            // A complete document, self-contained per the TextPage contract. The base href is the
            // archive root (an empty path), so the plain archive path of the src resolves - the
            // same channel every other plate travels: inlined when it fits the ceilings, streamed
            // by the reader's resource server when it does not.
            text = coverMarkup(coverHref),
            kind = TextPage.Kind.HTML,
            baseHref = "",
            name = null,
        )
    }

    /**
     * The cover document: the book's own cover image, on the reader's page.
     *
     * The `kmk-cover` class and the `kmk-cover-img` id are matched by the reader's injected
     * stylesheet (see `NovelTypographyCss`), which sizes and centres the image - inline styles
     * would lose the cascade fight against the `!important` rules the reader layers on top, so
     * the cover's presentation belongs to the stylesheet the reader controls.
     */
    private fun coverMarkup(coverHref: String): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Cover</title></head>")
        append("<body class=\"kmk-cover\">")
        append("<img id=\"kmk-cover-img\" src=\"")
        append(coverHref.replace("\"", "%22"))
        append("\" alt=\"\">")
        append("</body></html>")
    }

    /**
     * Whether the spine's own first document already shows the cover, by reading it and looking
     * for the cover's reference.
     *
     * Compared by both spellings a producer could have written - the archive path as the manifest
     * declares it, and its percent-decoded form - plus the bare file name, because a relative
     * `src="cover.jpg"` from the same directory names the same file without either. All compared
     * case-insensitively: a producer that renamed `Cover.jpg` to `cover.jpg` between the OPF and
     * the page is not a book that deserves a double cover.
     */
    private fun coverEmbeddedInSpine(
        chapter: SChapter,
        epub: EpubReader,
        documents: List<EpubSpineItem>,
        coverHref: String,
    ): Boolean {
        coverVerdicts[chapter.url]?.let { return it }
        val first = documents.firstOrNull()
        val embedded = first?.let { epub.readText(it.href) }?.let { markup ->
            val file = coverHref.substringAfterLast('/')
            val lower = markup.lowercase()
            lower.contains(coverHref.lowercase()) ||
                lower.contains(EpubPaths.percentDecode(coverHref).lowercase()) ||
                (file.isNotBlank() && lower.contains(file.lowercase()))
        } ?: false
        // Bounded: a long session opens many chapters and this is a verdict about a file, not a
        // reader choice - old chapters do not come back with a different answer.
        if (coverVerdicts.size >= COVER_VERDICT_CACHE) coverVerdicts.remove(coverVerdicts.keys.first())
        coverVerdicts[chapter.url] = embedded
        return embedded
    }

    /** Per-chapter cache of [coverEmbeddedInSpine]'s verdict. See its comment for the bound. */
    private val coverVerdicts = LinkedHashMap<String, Boolean>()
    // KMK <--

    /** The same enumeration, blocking. See [getTextPageIndex]. */
    private fun spineIndex(chapter: SChapter): List<TextPage> = indexedSpine(chapter).pages

    /**
     * The markup of one spine document, or null when it cannot be read or is over the size ceiling.
     *
     * One document, so the ceiling that matters here is [EpubReader]'s per-entry one rather than the
     * chapter-wide total: the point of reading a document at a time is that a book nobody finished
     * never has to hold all of them at once.
     */
    override suspend fun getTextPageText(chapter: SChapter, page: TextPage): String? {
        // KMK --> A page that arrives with its markup already in hand - the synthesized cover - is
        // its own document and never a spine item: resolving it by the fallback in [spineItemOf]
        // would read the first *real* document's markup as the cover's.
        if (page.text.isNotEmpty()) return page.text
        // KMK <--
        val markup = documentText(chapter, page)
        if (markup == null) {
            logcat {
                "EpubTextSource: ${chapter.url} could not read spine item ${page.index} (${page.url})"
            }
        }
        return markup
    }

    /**
     * The book's contents.
     *
     * Whole-book, not this chapter's: the TOC is a property of the file, and a reader opening a
     * multi-file book wants to see the shape of the volume with the parts it cannot reach greyed
     * out rather than a list that silently stops at its own chapter.
     */
    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> {
        val epub = readerFor(chapter) ?: return emptyList()
        if (epub.hasDrm) return emptyList()
        return epub.toc.map { node -> node.toTocEntry() }
    }

    /**
     * One level of the contents, mapped into the shape a host renders.
     *
     * Recursive rather than fixed-depth: EPUB 3 nav documents nest five or six levels without
     * embarrassment, and truncating at a depth would silently drop real structure. The parser
     * already bounds how deep it will follow.
     */
    private fun EpubTocNode.toTocEntry(): TocEntry =
        TocEntry(
            label = label,
            // fullHref, not href: a contents entry addressing a *section* is `document#fragment`,
            // and the host splits that back apart. `href` alone would drop every section entry onto
            // its document's top.
            href = fullHref,
            children = children.map { it.toTocEntry() },
        )

    /**
     * Finds [query] across the chapter's documents.
     *
     * Searches the **stripped plain text** and reports the offset in those same units. The host holds
     * markup; the source searched text. Handing back a markup offset would put the reader tens of
     * percent of a page from the match, because the markup is longer by exactly the tags and
     * indentation the strip removed. Reporting the stripped length alongside lets the host convert
     * without re-deriving anything.
     *
     * Enumerated through [getTextPageIndex] rather than [textPages], and that is not a shortcut. The
     * host locates a hit by matching `pageIndex` against a page's own index, so the two sides have to
     * be the same enumeration: [textPages] drops a document it cannot read and so is sparse, while the
     * index is dense because it has not tried. A hit from one and a page from the other would put the
     * reader in the wrong document for every hit after the first hole, and a document that fails to
     * read cannot be shown anyway, so losing its hits costs nothing.
     *
     * Caching the stripped text is left to the caller. It depends only on the file, so it is
     * computed once per chapter rather than per keystroke — the source that owns the archive holds
     * it alongside the archive and retires it when the archive retires: see `LocalEpubTextAdapter`
     * in `source-local`. A caller that does not keep the archive open between keystrokes hands the
     * suspension to this and re-reads per query, which for a remote source is the unavoidable cost.
     */
    override suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> =
        searchInChapter(chapter, query, plainTextFor(chapter))

    /**
     * Finds [query] over a plain-text index the caller computed, instead of over one read up fresh.
     *
     * Building [plainTextFor] costs one open, inflate and strip per spine document — the whole
     * chapter, once. A search over it runs on a debounce while the reader types, so recomputing the
     * index per keystroke throws that cost away nine times running for answers the previous
     * keystroke's pass could have searched directly. The index depends only on the file, which is
     * exactly why passing it in is sound: the caller holds it for as long as the archive is held,
     * and the archive is what retires it. The whole-book host is `LocalEpubTextAdapter`.
     *
     * The [suspend] overload keeps the shape the interface promises — one call, anything reached —
     * and stays the right answer for a caller that does not hold the archive between keystrokes.
     */
    fun searchInChapter(chapter: SChapter, query: String, plainText: List<IndexedText>): List<TextSearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim()
        return plainText.flatMap { page ->
            val plain = page.plain
            plain.indexesOf(needle, plain.length).map { hit -> hit.copy(pageIndex = page.index) }
        }
    }

    /**
     * The chapter's visible text with markup removed and whitespace collapsed.
     *
     * Reads a document at a time and keeps only the stripped text, so this costs a whole book's worth
     * of *plain* text rather than a whole book's worth of markup — and the markup is the larger of the
     * two by the tags the strip removed. It is still a whole book, which is why the source that owns
     * the archive caches it rather than recomputing it per keystroke.
     */
    fun plainTextFor(chapter: SChapter): List<IndexedText> {
        // KMK --> The cover page is an image; it has no text to search and no document behind it to
        // read, so it is skipped rather than searched as its neighbours' first document.
        val indexed = indexedSpine(chapter)
        return indexed.pages.mapNotNull { page ->
            if (page.url == indexed.coverUrl) return@mapNotNull null
            documentText(chapter, page)?.let { IndexedText(page.index, plainTextOf(it)) }
        }
        // KMK <--
    }

    /** Stripped text for one document, paired with the spine index it belongs to. */
    data class IndexedText(val index: Int, val plain: String)

    /** Reads the markup of one document. Blocking; see [getTextPageText]. */
    private fun documentText(chapter: SChapter, page: TextPage): String? =
        readerFor(chapter)?.takeIf { !it.hasDrm }?.readDocument(spineItemOf(chapter, page))

    /**
     * The spine record behind a page, for reading one document at a time.
     *
     * Resolved by [TextPage.url] first, then by index: the href is the identity that stays stable even
     * when the book has changed underneath a chapter that was left open, and the index is the fallback
     * for a page list built before the spine was re-read.
     */
    private fun spineItemOf(chapter: SChapter, page: TextPage): EpubSpineItem {
        val documents = readerFor(chapter)?.textDocuments().orEmpty()
        return documents.firstOrNull { it.href == page.url }
            ?: documents.getOrNull(page.index)
            ?: EpubSpineItem(id = page.url, href = page.url, mediaType = XHTML_MEDIA_TYPE, linear = true)
    }

    /**
     * The chapter's documents with their inline images removed, for a reader who wants the prose
     * without the plates.
     *
     * Images are *removed* rather than broken: an `<img>` that cannot load still occupies a line, so
     * stripping the element reflows the text around where it was while leaving a broken icon and a
     * gap. On a device where decoding every illustration of a long novel is the slowest part of
     * turning the page, that is the difference between usable and not.
     *
     * Applied to markup on the way out rather than in the source, because a source cannot honour it:
     * the markup has already been produced by the time a preference is known.
     */
    fun textPages(chapter: SChapter, showImages: Boolean): List<TextPage> {
        val pages = textPages(chapter)
        if (showImages) return pages
        return pages.map { it.copy(text = stripImages(it.text)) }
    }

    companion object {
        /**
         * Most spine items a chapter may expose to the reader.
         *
         * The longest real novel in one file is a few hundred. Anything past this is a declaration
         * rather than a book, and the reader can only show so many documents before the list itself
         * stops being usable.
         */
        const val MAX_DOCUMENTS = 5_000

        /**
         * Most markup characters one chapter may hold in memory at once.
         *
         * A `String` is UTF-16, so this is ~32 MB of heap. That number is the whole reason it is
         * 16 M characters and not 64: the earlier ceiling permitted a single chapter to reach ~128 MB,
         * which on a low-end device is the allocation that gets the process killed — so the truncation
         * meant to prevent an OOM arrived too late to do anything about one.
         */
        const val MAX_CHAPTER_CHARS = 16L * 1024 * 1024

        /**
         * Media type given to a spine record synthesised because the book's spine no longer describes
         * the page. Only ever a fallback; see [spineItemOf].
         */
        private const val XHTML_MEDIA_TYPE = "application/xhtml+xml"

        // KMK -->
        /**
         * Chapters whose cover-embedded verdict is remembered. Small on purpose: the verdict is a
         * fact about a file, so the cache only exists to keep the search index build from paying
         * the first document's read again after every open.
         */
        private const val COVER_VERDICT_CACHE = 8
        // KMK <--
    }
}
