package tachiyomi.source.epub

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.text.EpubArchiveSource
import eu.kanade.tachiyomi.source.text.TextBaseUrlSource
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import exh.log.xLogE
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.core.archive.EpubReader
import mihon.core.archive.EpubTextSource
import mihon.core.archive.epubReader
import mihon.core.archive.indexesOf
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
) : CatalogueSource,
    TextSource,
    TextTocSource,
    TextSearchSource,
    TextIncrementalSource,
    TextResourceSource,
    TextBaseUrlSource,
    EpubArchiveSource {

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

    /**
     * The book-as-text half of this source.
     *
     * Resolves the shared reader first, because that is the instance the renderer is streaming images
     * out of and closing it would unmap the archive out from under a chapter already on screen. A
     * cold read — the contents sheet can be opened before the first page loads — opens its own reader
     * and *adopts* it, so there is still exactly one owner for every mapped archive and one release
     * path for all of them.
     */
    private val text = EpubTextSource { chapter ->
        resourceReader.readerFor(chapter) ?: resolveChapterFile(chapter)
            ?.epubReader(context)
            ?.let { resourceReader.adopt(chapter.url, it) }
    }

    /**
     * Stripped plain text for the chapter last searched, or null.
     *
     * Guarded by [searchLock] rather than left to be a plain field: [searchInChapter] runs from the
     * host's search flow, which cancels the previous search by starting the next one, so two of them
     * genuinely do overlap on a slow book. A stale write landing after a fresh one would then leave
     * the cache pointing at the older query's chapter.
     */
    @Volatile
    private var searchCache: SearchCache? = null

    private val searchLock = Any()

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
     * One page per spine document, raw and unmodified.
     *
     * Thin: everything that decides what a book *is* — the spine, the ceilings, the stripped text
     * search runs over — lives in [EpubTextSource], so a book in the manga library behaves
     * identically to one in this library.
     *
     * Whatever CSS and markup the publisher shipped is what the renderer receives, because stripping
     * it is how a reader ends up with a book that has lost its ruby annotations, its tables and its
     * drop caps.
     */
    override suspend fun getTextPageList(chapter: SChapter): List<TextPage> =
        withIOContext { text.textPages(chapter) }

    /**
     * The chapter's documents with none of their text read.
     *
     * The reason this source is worth the [TextIncrementalSource] membership: an EPUB chapter is a
     * whole novel, so the eager list means inflating and decoding every spine item before the reader
     * sees the first screen and holding all of it for as long as the book stays open.
     */
    override suspend fun getTextPageIndex(chapter: SChapter): List<TextPage> =
        withIOContext { text.getTextPageIndex(chapter) }

    /** One document's markup, read when the reader reaches it. */
    override suspend fun getTextPageText(chapter: SChapter, page: TextPage): String? =
        withIOContext { text.getTextPageText(chapter, page) }

    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> =
        withIOContext { text.getTableOfContents(chapter) }

    /**
     * Finds [query] across the chapter's documents.
     *
     * Cached per chapter because search follows typing: without this, every pause in the keystrokes
     * re-reads and re-strips every document in the book, so typing a word means reading the chapter
     * several times over. Stripping depends only on the file, so it is done once and reused. Held as
     * plain text rather than as [TextPage] so it cannot be mistaken for the loader's own copy of the
     * markup and quietly double the book's footprint.
     */
    override suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim()
        return plainTextFor(chapter).flatMap { indexed ->
            indexed.plain.indexesOf(needle, indexed.plain.length)
                // The offset is within a document; which document it is comes from the cache entry,
                // so every hit is stamped here rather than inside indexesOf, which knows nothing
                // about the document it searched.
                .map { hit -> hit.copy(pageIndex = indexed.index) }
        }
    }

    /** Stripped plain text per document for [chapter], built once and reused across keystrokes. */
    private suspend fun plainTextFor(chapter: SChapter): List<EpubTextSource.IndexedText> {
        synchronized(searchLock) { searchCache }?.let { if (it.url == chapter.url) return it.pages }
        val pages = withIOContext { text.plainTextFor(chapter) }
        synchronized(searchLock) { searchCache = SearchCache(chapter.url, pages) }
        return pages
    }

    /**
     * Drops the cached plain text.
     *
     * Called when a book is re-read. Without it a search opened after a re-import answers from text
     * that is no longer in the file, and its results point at offsets that mean nothing — which
     * presents as "search is broken" rather than as "search is stale".
     */
    fun invalidateSearchCache() {
        synchronized(searchLock) { searchCache = null }
    }

    /**
     * Stripped text for one chapter, keyed by its url.
     *
     * Holds one chapter's worth rather than growing per chapter: moving to another chapter replaces
     * it. An unbounded cache here would be a second full copy of every book a reader has opened,
     * held for the life of the process.
     */
    private class SearchCache(val url: String, val pages: List<EpubTextSource.IndexedText>)

    override fun openResource(absoluteUrl: String) = resourceReader.openResource(absoluteUrl)

    override fun contentTypeFor(absoluteUrl: String) = resourceReader.contentTypeFor(absoluteUrl)

    /**
     * The URL each document has to be loaded at.
     *
     * [TextPage.baseHref] is set to the spine item's in-archive path, which is archive-relative and
     * therefore meaningless to a layout engine on its own. The reader turns it into a real URL here;
     * without it every relative reference in the book resolves against the archive root instead of
     * the document's own directory, and the publisher's stylesheets, images and fonts all 404.
     */
    override fun baseUrlFor(page: TextPage): String = resourceReader.documentBaseUrl(page.baseHref)

    /**
     * Retires the map for [chapterUrl].
     *
     * Called by the reader's page loader when the chapter is recycled — see [EpubArchiveSource] for
     * why the loader is the one that knows when that is. The unmap itself is deferred until the
     * renderer has closed whatever it is reading, so calling this mid-stream is safe.
     */
    override fun releaseResourceReader(chapterUrl: String) {
        resourceReader.release(chapterUrl)
        // The chapter's pages are about to change identity; cached plain text for them is stale.
        invalidateSearchCache()
    }

    override fun releaseAllResourceReaders() {
        resourceReader.releaseAll()
        invalidateSearchCache()
    }

    /**
     * The file a chapter URL names, or null when it is not one of ours.
     *
     * A chapter URL is `"<series>/<file>"`, and it is *stored* data — it reaches this function from
     * the database, so from a sync, a restored backup, or another device. Split on `/` and handed
     * straight to [EpubSourceFileSystem.getSeriesDirectory], which is why that one re-checks the
     * shape of both halves rather than trusting them: without the check, a URL of
     * `"../novels/Other/secret.epub"` resolves to a different folder than the one it names.
     */
    private fun resolveChapterFile(chapter: SChapter): UniFile? {
        val parts = chapter.url.split('/', limit = 2)
        if (parts.size != 2) return null
        val series = parts[0]
        val file = parts[1]
        if (!EpubSourceFileSystem.isEntryName(series) || !EpubSourceFileSystem.isEntryName(file)) {
            xLogE("Refusing EPUB chapter url with an unusable path: ${chapter.url}")
            return null
        }
        return fileSystem.getSeriesDirectory(series)
            ?.findFile(file)
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
     *
     * Read through [EpubReader.readBytes] with an explicit [MAX_COVER_BYTES] ceiling. A cover is
     * decoded into a bitmap by whatever the host does with it, so this is the one image in the
     * module whose size is multiplied rather than merely copied: a declared "cover" pointing at a
     * 200 MB TIFF is an out-of-memory crash in the library grid, and the grid is exactly where the
     * reader has not yet decided to trust the file.
     */
    suspend fun findSeriesCover(manga: SManga): EpubCover? = withIOContext {
        for (book in fileSystem.getBooksInSeries(manga.url)) {
            val cover = runCatching {
                book.epubReader(context).use { epub ->
                    val href = epub.coverHref ?: return@use null
                    val bytes = epub.readBytes(href, MAX_COVER_BYTES) ?: return@use null
                    EpubCover(fileName = "${manga.url.hashCode()}_cover.jpg", bytes = bytes)
                }
            }.onFailure {
                logcat(LogPriority.ERROR, it) { "Error reading cover for ${manga.url}" }
            }
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

        /**
         * Most bytes a cover image may be.
         *
         * Covers are bitmaps, so this is a memory ceiling in disguise. A real jacket is 100–500 KB;
         * a few megabytes covers an unreasonably large scan of a real book.
         */
        private const val MAX_COVER_BYTES = 16L * 1024 * 1024

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
