package tachiyomi.source.epub

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextResourceSource
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubReader
import mihon.core.archive.EpubResourceServer
import java.io.InputStream

/**
 * Serves a chapter's images, stylesheets and fonts out of its archive, and owns the map's lifetime.
 *
 * Thin by design. The URL namespace, the refcounted unmap and the size ceiling all live in
 * `core:archive`, because the manga library now serves books too and the two must behave
 * identically — the per-document base URL is the single most consequential detail in the EPUB path
 * and it fails silently as bare text when it is wrong. This class only knows which file a chapter
 * URL names.
 *
 * @see EpubArchiveUrls for the reserved `https://book.internal/` origin — real scheme, reserved
 *   host — and why the base URL is the document's own directory rather than the archive root.
 */
class EpubResourceReader(
    bookProvider: (SChapter) -> EpubReader?,
) : TextResourceSource {

    private val server = EpubResourceServer<SChapter>(
        keyOf = { it.url },
        bookProvider = bookProvider,
    )

    /** The reader backing [chapter], opening it on first use. */
    fun readerFor(chapter: SChapter): EpubReader? = server.readerFor(chapter)

    /**
     * Hands an already-open reader to this server to own, keyed by [chapterUrl].
     *
     * See `EpubResourceServer.adopt` for why the cold path needs this rather than a `use` block.
     */
    fun adopt(chapterUrl: String, reader: EpubReader): EpubReader = server.adopt(chapterUrl, reader)

    /** Retires the reader for [chapterUrl]. Safe to call mid-stream; see [EpubResourceServer]. */
    fun release(chapterUrl: String) = server.release(chapterUrl)

    /** Retires every reader. */
    fun releaseAll() = server.releaseAll()

    /** The URL a document must be loaded at so its own relative references resolve. */
    fun documentBaseUrl(baseHref: String?): String = EpubArchiveUrls.documentBaseUrl(baseHref)

    override fun openResource(absoluteUrl: String): InputStream? = server.openResource(absoluteUrl)

    override fun contentTypeFor(absoluteUrl: String): String? = server.contentTypeFor(absoluteUrl)

    companion object {
        /** Retained for the renderer's in-book link handling; see [EpubArchiveUrls]. */
        fun archivePathOf(absoluteUrl: String): String? = EpubArchiveUrls.archivePathOf(absoluteUrl)

        fun isArchiveUrl(url: String): Boolean = EpubArchiveUrls.isArchiveUrl(url)

        fun contentTypeForPath(path: String): String? = EpubArchiveUrls.contentTypeForPath(path)

        const val BASE_URL: String = EpubArchiveUrls.BASE_URL
    }
}
