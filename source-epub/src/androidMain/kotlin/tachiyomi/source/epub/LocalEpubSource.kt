package tachiyomi.source.epub

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.core.archive.EpubReader
import mihon.core.archive.EpubTocNode
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.i18n.kmk.KMR

/**
 * Built-in source for a library of EPUB files on disk.
 *
 * ### Layout
 *
 * ```
 * <local>/novels/<Series name>/<chapter>.epub
 * ```
 *
 * One folder is one series, and every `.epub` inside it is one chapter — the same shape the manga
 * local source uses, so the browse screen, chapter list and library all behave identically and a
 * user who already understands one understands the other.
 *
 * ### Why a separate source rather than a mode of the manga local source
 *
 * A manga folder may legitimately contain `.epub` files that are meant to be read as *images* — the
 * existing behaviour, which users rely on. Changing how those render would silently alter an
 * existing library. A separate source keeps that untouched and gives novels their own browse
 * surface, their own settings and their own identity in the database.
 *
 * ### Chapter identity
 *
 * A chapter URL is `"<series>/<file name>"`. That is stable for the life of the file: renaming the
 * file changes the URL (and so the chapter's identity), but re-scanning the library, refreshing it
 * or re-importing it does not. Reading position and annotations both hang off that URL.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
class LocalEpubSource(
    private val context: Context,
    private val fileSystem: EpubSourceFileSystem,
) : CatalogueSource, TextSource, TextTocSource, TextSearchSource, TextResourceSource {

    override val name: String = context.stringResource(KMR.strings.local_epub_source)

    /**
     * Keeps the currently-open book's archive mapped so the renderer can stream images out of it.
     *
     * The reader holds an mmap plus a file descriptor per open book, so [releaseResourceReader]
     * exists to close it when the reader moves on; without that call a session that opens fifty
     * books keeps fifty of them alive for the life of the process.
     */
    private val resourceReader = EpubResourceReader { chapter ->
        resolveChapterFile(chapter)?.epubReader(context)
    }

    override val id: Long = ID

    override val lang: String = "other"

    override val supportsLatest: Boolean = true

    override val textCapabilities: TextCapabilities = TextCapabilities.EPUB

    override fun toString() = name

    // ---------------------------------------------------------------- browse

    override fun getFilterList() = FilterList()

    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = withIOContext {
        val dirs = fileSystem.getSeriesDirectories()
            .sortedByDescending { it.lastModified() }
        MangasPage(dirs.map { it.toManga() }, false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val matches = fileSystem.getSeriesDirectories()
            .filter { query.isBlank() || it.name.orEmpty().contains(query, ignoreCase = true) }
            .sortedWith { a, b ->
                a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty())
            }
        MangasPage(matches.map { it.toManga() }, false)
    }

    private fun UniFile.toManga() = SManga.create().apply {
        url = name.orEmpty()
        title = name.orEmpty()
    }

    // ---------------------------------------------------------------- details

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val details = if (fetchDetails) async { getDetails(manga) } else null
        val list = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(details?.await() ?: manga, list?.await() ?: chapters)
    }

    /**
     * Fills series metadata from the books inside it.
     *
     * The folder name is the series title and wins — a user named the folder on purpose. Only the
     * fields a folder cannot express (author, description, publisher, cover) are taken from the
     * first book that declares them, so a library of one book picks up its own metadata.
     */
    private suspend fun getDetails(manga: SManga): SManga = withIOContext {
        val books = fileSystem.getBooksInSeries(manga.url)
        for (book in books) {
            val details = runCatching { book.epubReader(context).use { it.metadata } }
                .onFailure { logcat(LogPriority.ERROR, it) { "Error reading metadata for ${book.name}" } }
                .getOrNull() ?: continue

            details.authors.firstOrNull()?.let { manga.author = it }
            manga.artist = details.authors.getOrNull(1)
            details.description?.takeIf { it.isNotBlank() }?.let { manga.description = it }
            details.publisher?.takeIf { it.isNotBlank() }?.let { manga.genre = it }
            manga.status = SManga.COMPLETED
            break
        }
        manga
    }

    // ---------------------------------------------------------------- chapters

    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val books = fileSystem.getBooksInSeries(manga.url)

        val chapters = books.map { book ->
            SChapter.create().apply {
                url = "${manga.url}/${book.name}"
                name = book.nameWithoutExtension.orEmpty()
                date_upload = book.lastModified()
                chapter_number = ChapterRecognition
                    .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                    .toFloat()

                // The book's own title beats its filename: publishers name the file "book (1).epub"
                // and put the real chapter title inside.
                fillFromBook(book, manga, this)
            }
        }

        chapters.sortedWith { a, b -> b.name.compareToCaseInsensitiveNaturalOrder(a.name) }
    }

    /**
     * Overlays what the EPUB itself says on top of the filename-derived defaults.
     *
     * [chapter] is passed explicitly rather than relying on the caller's `apply` receiver. Inside
     * `reader.use { }` the innermost implicit receiver is the EpubReader, so a bare `name = ...`
     * here would resolve against the reader — which has no such setter — instead of against the
     * chapter being built.
     */
    private fun fillFromBook(book: UniFile, manga: SManga, chapter: SChapter) {
        // One open per book, closed immediately: a library scan that kept every reader open would
        // hold one file descriptor and one mmap per book for the lifetime of the screen.
        val reader = runCatching { book.epubReader(context) }.getOrNull() ?: return
        reader.use {
            val metadata = it.metadata
            // Only adopt the book's own title once we know it is a real book and not a stub.
            if (it.textDocuments().isNotEmpty()) {
                metadata.displayTitle?.let { title -> chapter.name = title }
            }
            metadata.authors.firstOrNull()?.let { author -> chapter.scanlator = author }
            metadata.publisher?.let { publisher ->
                // scanlator is the closest thing the chapter model has to "who published this";
                // fall back to the author so the field is never blank.
                if (chapter.scanlator.isNullOrBlank()) chapter.scanlator = publisher
            }
            parseDate(metadata.date)?.let { chapter.date_upload = it }
            if (manga.author.isNullOrBlank()) manga.author = metadata.authors.firstOrNull()
        }
    }

    /**
     * Best-effort parse of an EPUB `dc:date`.
     *
     * Not ISO-8601 in practice — producers emit `2020`, `2020-04`, `2020-04-13T08:00:00Z` and
     * `2020-04-13T08:00:00+02:00` with roughly equal frequency, so the pattern is progressively
     * loosened rather than fixed to one format. An unparseable date leaves the file's modified
     * time in place, which is a worse answer than none but a better one than a wrong year.
     */
    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val pattern = when {
            trimmed.length >= 10 -> "yyyy-MM-dd"
            trimmed.length >= 7 -> "yyyy-MM"
            trimmed.length >= 4 -> "yyyy"
            else -> return null
        }
        return runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT)
                .apply { isLenient = false }
                .parse(trimmed.take(if (pattern == "yyyy") 4 else pattern.count { !it.isLetter() }))
                ?.time
        }.getOrNull()
    }

    // ---------------------------------------------------------------- reading

    /**
     * Splits a book into one page per spine document.
     *
     * Raw markup, unmodified. Whatever CSS and markup the publisher shipped is what the renderer
     * receives, because stripping it is how a reader ends up with a book that has lost its ruby
     * annotations, its tables and its drop caps.
     */
    override suspend fun getTextPageList(chapter: SChapter): List<TextPage> = withIOContext {
        // Reuses the reader opened by a previous call for this chapter so the archive stays mapped
        // for the renderer to pull images out of.
        val epub = resourceReader.readerFor(chapter)
            ?: throw IllegalStateException("Chapter not found: ${chapter.url}")

        if (epub.hasDrm) {
            resourceReader.release(chapter.url)
            throw IllegalStateException("This book is DRM-protected and cannot be read")
        }

        epub.textDocuments().mapIndexedNotNull { index, item ->
            val markup = epub.readDocument(item) ?: return@mapIndexedNotNull null
            TextPage(
                index = index,
                url = item.href,
                text = markup,
                kind = TextPage.Kind.HTML,
                baseHref = item.href,
                name = item.title,
            )
        }
    }

    override fun openResource(absoluteUrl: String) = resourceReader.openResource(absoluteUrl)

    override fun contentTypeFor(absoluteUrl: String) = resourceReader.contentTypeFor(absoluteUrl)

    /** Unmaps the current book. Called by the reader when it leaves a chapter. */
    fun releaseResourceReader(chapterUrl: String) = resourceReader.release(chapterUrl)

    fun releaseAllResourceReaders() = resourceReader.releaseAll()

    /**
     * The book's table of contents.
     *
     * Reads the shared reader without closing it. `readerFor` hands out the instance the renderer
     * is streaming images out of, so wrapping this in `use` would unmap the archive out from under
     * a chapter already on screen.
     */
    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> = withIOContext {
        val shared = resourceReader.readerFor(chapter)
        if (shared != null) return@withIOContext shared.toTocEntries()

        // Not open yet (the TOC sheet can be opened before the first page loads), so open a private
        // reader and close it here.
        val file = resolveChapterFile(chapter) ?: return@withIOContext emptyList()
        file.epubReader(context).use { it.toTocEntries() }
    }

    private fun EpubReader.toTocEntries(): List<TocEntry> = toc.map { it.toTocEntry() }

    private fun EpubTocNode.toTocEntry(): TocEntry = TocEntry(
        label = label,
        href = fullHref,
        children = children.map { it.toTocEntry() },
    )

    override suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim()
        return getTextPageList(chapter).flatMap { page ->
            plainTextOf(page.text)
                .indexesOf(needle)
                // The offset is within a document; which document it is comes from the page, so
                // every hit is stamped here rather than inside indexesOf, which knows nothing about
                // the page it is searching.
                .map { hit -> hit.copy(pageIndex = page.index) }
        }
    }

    /**
     * Case-insensitive search over the page's visible text, returning displayable snippets.
     *
     * Matching runs on text with tags stripped, so a query spanning a tag boundary ("<em>wor</em>ld")
     * still matches, and the reported offset refers to the same stripped text the snippet shows —
     * otherwise the host would highlight the wrong characters.
     */
    private fun plainTextOf(html: String): String =
        html.replace(SCRIPT_OR_STYLE, " ")
            .replace(TAG, " ")
            .replace(WHITESPACE, " ")

    private fun String.indexesOf(needle: String): List<TextSearchHit> {
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
                // Measured against the trimmed snippet, so the host highlights the characters the
                // user can actually see rather than an offset shifted by the trim.
                matchOffset = at - start - (snippet.length - snippet.trimStart().length),
            )
            from = at + needle.length
        }
        return hits
    }

    private fun resolveChapterFile(chapter: SChapter): UniFile? {
        val parts = chapter.url.split('/', limit = 2)
        if (parts.size != 2) return null
        return fileSystem.getSeriesDirectory(parts[0])
            ?.findFile(parts[1])
            ?.takeIf { fileSystem.isEpub(it) }
    }

    /**
     * Cover art for a series, taken from the first book that declares one.
     *
     * Returns bytes rather than writing a cache file: where covers live is the host's decision, not
     * the source's, and this module has no business writing into the app's cover cache.
     *
     * Prefers the manifest's declared cover over "the first image in the file", because the latter
     * is usually a decorative frontispiece rather than the jacket.
     */
    suspend fun findSeriesCover(manga: SManga): EpubCover? = withIOContext {
        for (book in fileSystem.getBooksInSeries(manga.url)) {
            val cover = runCatching {
                book.epubReader(context).use { epub ->
                    val href = epub.coverHref ?: return@use null
                    val bytes = epub.getInputStream(href)?.use { it.readBytes() } ?: return@use null
                    EpubCover(fileName = "${manga.url.hashCode()}_cover.jpg", bytes = bytes)
                }
            }.onFailure { logcat(LogPriority.ERROR, it) { "Error reading cover for ${manga.url}" } }
                .getOrNull()

            if (cover != null) return@withIOContext cover
        }
        null
    }

    companion object {
        /**
         * Source id.
         *
         * Fixed forever: it is part of every database row that references a book, so changing it
         * orphans existing libraries. The range mirrors `metadataDelegatedSourceIds` (6900..6999)
         * to stay clear of extension ids.
         */
        const val ID = 7812L

        const val HELP_URL = "https://komikku-app.github.io/docs/guides/local-source/"

        private const val MAX_SEARCH_HITS = 200
        private const val SNIPPET_PADDING = 40

        private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
        private val TAG = Regex("(?s)<[^>]*>")
        private val WHITESPACE = Regex("[ \\t\\r\\n\\u00A0]+")
    }
}

fun EpubSourceFileSystem.isLocalEpub(): Boolean = getBaseDirectory() != null

/**
 * A series' cover image: raw bytes plus the name to cache them under.
 *
 * Deliberately not a [UniFile]: the source reads from wherever the user's library lives and must not
 * dictate where the app keeps its cache.
 */
data class EpubCover(
    val fileName: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is EpubCover && fileName == other.fileName && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}
