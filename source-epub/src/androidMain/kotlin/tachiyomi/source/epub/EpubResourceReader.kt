package tachiyomi.source.epub

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import mihon.core.archive.EpubReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.lang.withIOContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams a book's images, stylesheets and fonts to the renderer on demand.
 *
 * ### The scheme
 *
 * Pages are handed to the renderer with a base URL of [SCHEME], so every relative reference in the
 * publisher's markup resolves to something like `epub-archive.internal/OEBPS/images/plate.jpg`. The
 * renderer intercepts those and calls back here. That is what makes `<img src="../images/x.jpg">`
 * work inside a zip file — without a scheme the WebView resolves it against the app's own URL space
 * and every image 404s.
 *
 * ### Caching
 *
 * One [EpubReader] is kept per chapter, keyed by chapter URL. An mmap of a 40 MB book plus its file
 * descriptor is not something to hold per page, and re-opening it for every image would re-parse the
 * OPF on each request. Entries are dropped when [release] is called at the end of a chapter.
 *
 * Concurrency: the renderer requests images from its own threads, so the map is concurrent and a
 * reader is only ever published to it after it is fully constructed.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
class EpubResourceReader(
    private val bookProvider: (SChapter) -> EpubReader?,
) : TextResourceSource {

    private val readers = ConcurrentHashMap<String, EpubReader>()

    /**
     * The reader backing [chapter], opening it on first use.
     *
     * `putIfAbsent` rather than `getOrPut`: `getOrPut` is not atomic, so two threads arriving at a
     * cold entry would each open a book and one of the two readers would be discarded without ever
     * being closed - leaking an mmap and a file descriptor per race. `putIfAbsent` hands the loser
     * the winner's instance, which it then closes.
     *
     * Returns null when the book cannot be opened, and does not cache that: a file that appears
     * later (a sync finishing mid-session) should still be picked up.
     */
    fun readerFor(chapter: SChapter): EpubReader? {
        readers[chapter.url]?.let { return it }

        val opened = bookProvider(chapter) ?: return null
        val existing = readers.putIfAbsent(chapter.url, opened)
        if (existing != null) {
            // Lost the race; close ours so its mmap is not leaked.
            opened.runCatching { close() }
            return existing
        }
        return opened
    }

    /**
     * Drops the reader for [chapterUrl] and unmaps it.
     *
     * Called when the reader leaves a chapter. Without it a session that opens fifty books holds
     * fifty mmaps for the lifetime of the process.
     */
    fun release(chapterUrl: String) {
        readers.remove(chapterUrl)?.runCatching { close() }
    }

    /** Drops every reader. */
    fun releaseAll() {
        readers.keys.toList().forEach(::release)
    }

    /**
     * Strips the [SCHEME] prefix off [absoluteUrl], returning the archive path.
     *
     * Returns null for anything that is not one of ours, which is the host's signal to let the URL
     * load normally instead.
     */
    fun archivePathOf(absoluteUrl: String): String? {
        val prefix = "$SCHEME://$HOST/"
        if (!absoluteUrl.startsWith(prefix)) return null
        val path = absoluteUrl.removePrefix(prefix).substringBefore('#').substringBefore('?')
        return path.takeIf { it.isNotBlank() }
    }

    override fun openResource(absoluteUrl: String): InputStream? {
        val path = archivePathOf(absoluteUrl) ?: return null
        return openEntry(path)
    }

    /**
     * Opens an entry from whichever book is currently loaded.
     *
     * A single active reader is enough and is what the renderer sees in practice: one chapter is
     * displayed at a time. When more than one is registered (a chapter opened for preview while
     * another is being read) the first match wins, which is the one the visible page came from.
     */
    private fun openEntry(path: String): InputStream? {
        for (reader in readers.values) {
            reader.getInputStream(path)?.let { return it }
        }
        return null
    }

    override fun contentTypeFor(absoluteUrl: String): String? {
        val path = archivePathOf(absoluteUrl) ?: return null
        return contentTypeForPath(path)
    }

    companion object {
        /**
         * Reserved scheme for archive-internal resources.
         *
         * `.internal` is reserved by RFC 6761 and can never collide with a real site, so a page
         * that happens to link to a genuinely external image is never mistaken for an archive one.
         */
        const val SCHEME = "epub-archive"

        const val HOST = "book.internal"

        /** The base URL a document is loaded with. */
        const val BASE_URL = "$SCHEME://$HOST/"

        private const val URL_PREFIX = "$SCHEME://$HOST/"

        /**
         * True when [url] is one of ours and must be served from the archive.
         *
         * The renderer calls this before intercepting so a genuinely external URL is left alone —
         * a book that links to an image on a real server must still show it.
         */
        fun isArchiveUrl(url: String): Boolean = url.startsWith(URL_PREFIX)

        /**
         * Media type for an archive path, from its extension.
         *
         * Covers the types an EPUB actually contains. Anything else returns null so the host falls
         * back to its own guess, rather than claiming a type we do not know.
         */
        fun contentTypeForPath(path: String): String? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "xhtml", "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "opf" -> "application/oebps-package+xml"
                "ncx" -> "application/x-dtbncx+xml"
                "xml" -> "application/xml"
                "mp3", "m4a" -> "audio/mpeg"
                "mp4", "m4v" -> "video/mp4"
                else -> null
            }
        }
    }
}

/** Context the renderer needs to open a chapter's archive. */
internal fun contextBookProvider(
    context: android.content.Context,
    fileSystem: EpubSourceFileSystem,
    chapter: SChapter,
): EpubReader? {
    val parts = chapter.url.split('/', limit = 2)
    if (parts.size != 2) return null
    val file = fileSystem.getSeriesDirectory(parts[0])
        ?.findFile(parts[1])
        ?.takeIf { fileSystem.isEpub(it) }
        ?: return null
    return file.epubReader(context)
}

/** Convenience: reads a chapter's pages while the resource reader for it stays open. */
suspend fun EpubResourceReader.readPages(
    source: eu.kanade.tachiyomi.source.text.TextSource,
    chapter: SChapter,
): List<TextPage> = withIOContext { source.getTextPageList(chapter) }
