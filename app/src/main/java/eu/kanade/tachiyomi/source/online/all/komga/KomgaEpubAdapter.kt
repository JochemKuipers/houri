package eu.kanade.tachiyomi.source.online.all.komga

import android.content.Context
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TocEntry
import kotlinx.serialization.json.Json
import mihon.app.di.globalAppGraph
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubReader
import mihon.core.archive.EpubResourceServer
import mihon.core.archive.EpubTextSource
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the EPUB chapters of a Komga server to the built-in epub reader.
 *
 * The EPUB itself is stored on the Komga server, not on the device, so the first open downloads
 * the book into the app cache and reads it from disk exactly like a local file — same reader
 * classes ([EpubReader], [EpubResourceServer], [EpubTextSource]), same `https://book.internal/`
 * URL space, same plain-text search.
 *
 * The cache file is named `<bookId>.epub.cache` and its companion `<bookId>.hash` stores the
 * `fileHash` the server reported when it was written. A book whose fileHash changed on the
 * server is re-downloaded on next open; an unchanged book is served from disk without touching
 * the network.
 *
 * The download is deliberately **not** on the open path: [EpubTextSource] and [EpubResourceServer]
 * take synchronous lambdas and read the archive straight off disk. Every suspend entry point
 * therefore awaits [ensureDownloaded] first, and the lambdas only ever touch the cache — a
 * network call could not happen there even by accident.
 */
class KomgaEpubAdapter(
    private val context: Context,
    private val source: Komga,
) {
    // ------------------------------------------------------------ cache layout

    private val json: Json get() = globalAppGraph.json

    private val cacheDir: File by lazy {
        File(context.cacheDir, "komga").apply { mkdirs() }
    }

    private fun bookIdOf(url: String): String? =
        BOOK_ID_REGEX.find(url)?.groupValues?.get(1)

    private fun cacheFile(bookId: String): File = File(cacheDir, "$bookId.epub.cache")

    private fun hashFile(bookId: String): File = File(cacheDir, "$bookId.hash")

    private fun cachedHash(bookId: String): String? =
        runCatching { hashFile(bookId).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } }.getOrNull()

    private fun writeCachedHash(bookId: String, hash: String) {
        runCatching { hashFile(bookId).writeText(hash) }
    }

    // ------------------------------------------------------------ book access

    private suspend fun fetchBook(bookId: String) =
        source.client.newCall(GET("${source.baseUrl}/api/v1/books/$bookId", source.headers)).awaitSuccess()

    /**
     * The `fileHash` of the book, or null if the server cannot be reached. A null here is not an
     * error: the download then always refreshes the cache, which is correct but heavier.
     */
    private suspend fun fetchFileHash(bookId: String): String? =
        try {
            with(json) { fetchBook(bookId).parseAs<BookDto>().fileHash.ifBlank { null } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No hash means "always verify by re-downloading", never "fail the chapter".
            logcat { "Komga: fileHash probe failed for $bookId: ${e.message}" }
            null
        }

    /**
     * Download the book if [fileHash] is not yet cached (or the cached copy is stale). Throws on
     * network failure — the reader surfaces it as a load error, per the TextSource contract.
     */
    private suspend fun ensureLocalFile(bookId: String, fileHash: String?): File {
        val target = cacheFile(bookId)
        if (fileHash != null && target.exists() && cachedHash(bookId) == fileHash) {
            return target
        }
        // No usable cached copy — fetch straight from the server.
        val request = GET("${source.baseUrl}/api/v1/books/$bookId/file", source.headers)
        val response = source.client.newCall(request).awaitSuccess()
        response.body.byteStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (fileHash != null) {
            writeCachedHash(bookId, fileHash)
        } else {
            // Without a hash we cannot trust the cached copy later, so drop the marker.
            runCatching { hashFile(bookId).delete() }
        }
        logcat { "Komga epub downloaded: $bookId (${target.length()} bytes)" }
        return target
    }

    /**
     * Ensure the book [chapterUrl] names is on disk, downloading it when it is not. Called by
     * every suspend entry point before the synchronous readers are used; a no-op when this URL is
     * not one of our books (the readers then find nothing to open, which they already tolerate).
     */
    private suspend fun ensureDownloaded(chapterUrl: String) {
        val bookId = bookIdOf(chapterUrl) ?: return
        val fileHash = fetchFileHash(bookId)
        ensureLocalFile(bookId, fileHash)
    }

    /**
     * The cached EPUB for [chapterUrl], opened for reading — or null when this URL is not a book
     * of ours or the cache file is missing. Synchronous on purpose; see the class comment.
     */
    private fun openLocalEpub(chapterUrl: String): EpubReader? =
        bookIdOf(chapterUrl)?.let(::cacheFile)
            ?.takeIf { it.exists() }
            ?.openArchiveReader()
            ?.let { EpubReader(it) }

    private fun File.openArchiveReader(): mihon.core.archive.ArchiveReader? =
        runCatching {
            ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()?.use { mihon.core.archive.ArchiveReader(it) }

    // ------------------------------------------------------------ resource leases

    private val resources = EpubResourceServer<SChapter>(
        keyOf = { it.url },
        bookProvider = { chapter -> openLocalEpub(chapter.url) },
    )

    private val text = EpubTextSource(
        readerFor = { chapter ->
            resources.readerFor(chapter) ?: openLocalEpub(chapter.url)?.let { resources.adopt(chapter.url, it) }
        },
        // Remote epubs have no app-level preference for a synthesized cover page: the epub may
        // itself not declare one. Defer to the same default the local reader uses.
        wantsCoverPage = { true },
    )

    // ------------------------------------------------------------ TextSource half

    suspend fun textPages(chapter: SChapter): List<TextPage> {
        ensureDownloaded(chapter.url)
        return text.textPages(chapter)
    }

    suspend fun textPageIndex(chapter: SChapter): List<TextPage> {
        ensureDownloaded(chapter.url)
        return text.getTextPageIndex(chapter)
    }

    suspend fun textPageText(chapter: SChapter, page: TextPage): String? {
        ensureDownloaded(chapter.url)
        return text.getTextPageText(chapter, page)
    }

    suspend fun tableOfContents(chapter: SChapter): List<TocEntry> {
        ensureDownloaded(chapter.url)
        return text.getTableOfContents(chapter)
    }

    private val plainTextIndexes = ConcurrentHashMap<String, List<EpubTextSource.IndexedText>>()

    suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> {
        ensureDownloaded(chapter.url)
        val index = plainTextIndexes[chapter.url]?.takeIf { it.isNotEmpty() }
            ?: text.plainTextFor(chapter).also { built ->
                if (built.isNotEmpty()) plainTextIndexes[chapter.url] = built
            }
        return text.searchInChapter(chapter, query, index)
    }

    /** The URL each document must be loaded at: an in-archive path, resoluble here. */
    fun baseUrlFor(page: TextPage): String = EpubArchiveUrls.documentBaseUrl(page.baseHref)

    fun openResource(absoluteUrl: String): InputStream? = resources.openResource(absoluteUrl)

    fun contentTypeFor(absoluteUrl: String): String? = resources.contentTypeFor(absoluteUrl)

    /** Releases the archive for [chapterUrl]; called when the chapter is closed. */
    fun releaseResourceReader(chapterUrl: String) {
        plainTextIndexes.remove(chapterUrl)
        resources.release(chapterUrl)
    }

    /** Releases every archive. */
    fun releaseAllResourceReaders() {
        plainTextIndexes.clear()
        resources.releaseAll()
    }

    private companion object {
        val BOOK_ID_REGEX = Regex("""/api/v1/books/([^/?#]+)""")
    }
}
