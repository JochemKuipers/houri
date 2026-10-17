package tachiyomi.source.epub

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import mihon.core.archive.EpubReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.util.lang.withIOContext
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Streams a book's images, stylesheets and fonts to the renderer on demand.
 *
 * ### The scheme
 *
 * Pages are handed to the renderer with a base URL of [SCHEME], so every relative reference in the
 * publisher's markup resolves to something like `epub-archive://book.internal/OEBPS/images/plate.jpg`.
 * The renderer intercepts those and calls back here. That is what makes `<img src="../images/x.jpg">`
 * work inside a zip file — without a scheme the WebView resolves it against the app's own URL space
 * and every image 404s.
 *
 * The base URL is **per document**, not per book: see [documentBaseUrl] for why that distinction is
 * the difference between a book rendering correctly and a book rendering without its own styling.
 *
 * ### Caching
 *
 * One [EpubReader] is kept per chapter, keyed by chapter URL. An mmap of a 40 MB book plus its file
 * descriptor is not something to hold per page, and re-opening it for every image would re-parse the
 * OPF on each request. Entries are dropped when [release] is called at the end of a chapter.
 *
 * ### Releasing is not unmapping
 *
 * [release] cannot simply close the reader, and the reason is the one thing in this module that
 * crashes rather than misbehaves.
 *
 * The renderer does not read a resource on the thread that asked for it. It asks here from its own
 * network threads, gets a live [InputStream] positioned inside a memory-mapped archive, and reads
 * from it over the following milliseconds. `ArchiveReader.close()` is an `Os.munmap` — it does not
 * wait, it does not reference-count, and nothing in libarchive knows a stream is still open. So
 * closing the book while one of its streams is mid-read turns the next read into a read of
 * unmapped memory, which is a `SIGSEGV` in native code: no catchable exception, no log line, the
 * process simply dies, and it does so on a device, in a book, while the user is reading.
 *
 * [HeldReader] is therefore a lease. It counts the streams it has handed out and unmaps when the last
 * one closes — whether that is right now or after the renderer has finished with it. [release] only
 * marks the entry retired; it never tears down memory another thread can still reach.
 *
 * The count is taken *before* the stream is opened, not after, because the alternative leaves a
 * window in which [release] observes zero outstanding streams, unmaps, and the thread that is
 * halfway through opening one then returns a stream pointing at freed memory. Claiming first means
 * an unmap can never overtake a handout.
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

    private val readers = ConcurrentHashMap<String, HeldReader>()

    /**
     * The reader backing [chapter], opening it on first use.
     *
     * `putIfAbsent` rather than `getOrPut`: `getOrPut` is not atomic, so two threads arriving at a
     * cold entry would each open a book and one of the two readers would be discarded without ever
     * being closed - leaking an mmap and a file descriptor per race. `putIfAbsent` hands the loser
     * the winner's instance, which it then retires.
     *
     * Returns null when the book cannot be opened, and does not cache that: a file that appears
     * later (a sync finishing mid-session) should still be picked up.
     */
    fun readerFor(chapter: SChapter): EpubReader? {
        readers[chapter.url]?.let { return it.reader }

        val opened = bookProvider(chapter) ?: return null
        val fresh = HeldReader(opened)
        val existing = readers.putIfAbsent(chapter.url, fresh)
        if (existing != null) {
            // Lost the race; retire ours so its mmap is not leaked.
            fresh.retire()
            return existing.reader
        }
        return opened
    }

    /**
     * Retires the reader for [chapterUrl].
     *
     * Called when the reader leaves a chapter. Without it a session that opens fifty books holds
     * fifty mmaps for the lifetime of the process.
     *
     * Returns as soon as the book is unreachable. The unmap itself happens when the last stream
     * handed out for it is closed, so this is safe to call from anywhere, at any time, including
     * while the renderer is still reading out of it.
     */
    fun release(chapterUrl: String) {
        readers.remove(chapterUrl)?.retire()
    }

    /** Retires every reader. */
    fun releaseAll() {
        readers.keys.toList().forEach(::release)
    }

    /**
     * The URL a document must be **loaded at** so its own relative references resolve.
     *
     * ### The one thing that is easy to get wrong
     *
     * A spine item at `OEBPS/Text/chapter1.xhtml` links to `../styles/main.css`. Loaded against the
     * **archive root** that resolves to `styles/main.css` — not `OEBPS/styles/main.css` — so the
     * stylesheet is never found and the book renders with the publisher's typography, illustrations
     * and ruby annotations all silently missing. The document itself still shows its text, which is
     * exactly what makes it read as "the reader is broken" rather than "one file is missing".
     *
     * So the base URL is the **directory of the document**, not the root of the archive.
     * [baseHref][eu.kanade.tachiyomi.source.text.TextPage.baseHref] is that document's in-archive
     * path; this turns it into a URL. The trailing slash is what makes the last segment a directory
     * rather than a file — without it the engine drops `chapter1.xhtml` and every sibling reference
     * resolves one level up.
     *
     * Normalised the same way an `href` is before a lookup, so a `..` inside a producer's href
     * cannot walk the base URL out of the archive host and leave the reader's own relative
     * resolution pointing at something it does not serve.
     */
    fun documentBaseUrl(baseHref: String?): String {
        val path = baseHref?.trim()?.substringBefore('#')?.substringBefore('?')?.trim('/').orEmpty()
        if (path.isEmpty()) return BASE_URL
        val slash = path.lastIndexOf('/')
        if (slash < 0) return BASE_URL
        val dir = normalizeArchivePath(path.substring(0, slash))
        if (dir.isEmpty()) return BASE_URL
        return BASE_URL + dir + "/"
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
     *
     * The result is capped at [MAX_RESOURCE_BYTES]. A zip entry's size is whatever the file claims and
     * its compression ratio is unbounded, so streaming one into the renderer with no ceiling turns a
     * 40 KB entry into an allocation the WebView cannot survive. Capping here rather than at the
     * WebView means the bound travels with the source: every host that streams from an archive gets
     * it, and an extension serving its own resources is untouched.
     */
    private fun openEntry(path: String): InputStream? {
        for (held in readers.values) {
            val raw = held.open { reader.getInputStream(path) } ?: continue
            return LimitedInputStream(raw, MAX_RESOURCE_BYTES)
        }
        return null
    }

    override fun contentTypeFor(absoluteUrl: String): String? {
        val path = archivePathOf(absoluteUrl) ?: return null
        return contentTypeForPath(path)
    }

    /**
     * One open book, and the streams currently being read out of it.
     *
     * The invariant, in one line: **the archive is unmapped if and only if no stream handed out for
     * it is still open.** Everything else here exists to make that true when [retire] races an open.
     */
    private class HeldReader(val reader: EpubReader) {

        private val lock = Any()

        /** Streams handed out and not yet closed. */
        private var outstanding = 0

        /** Set by [retire]; no new stream is opened after this. */
        private var retired = false

        /** Set once the unmap has actually happened, so it can never happen twice. */
        private var unmapped = false

        /**
         * Opens a stream from this book, counted from before the open.
         *
         * Takes the count *first*. Claiming afterwards would leave a window in which [retire] sees
         * nothing outstanding, unmaps, and this thread then returns a stream into freed memory —
         * which is the exact crash the counting exists to prevent.
         *
         * Null once retired, and null when the block finds nothing, so a caller can simply try the
         * next book.
         *
         * [block] is a receiver lambda rather than an argument-taking one so that `reader` inside it
         * resolves to this book's reader and not to anything in scope at the call site — the earlier
         * form took no parameters at all, which made a one-parameter lambda a compile error at every
         * call.
         */
        fun open(block: HeldReader.() -> InputStream?): InputStream? {
            synchronized(lock) {
                if (retired) return null
                outstanding++
            }

            val stream = try {
                this.block()
            } catch (e: Throwable) {
                release()
                throw e
            }
            if (stream == null) {
                release()
                return null
            }
            return TrackedInputStream(stream)
        }

        /** Retires this book. The unmap is deferred to the last outstanding stream's close. */
        fun retire() {
            val unmapNow = synchronized(lock) {
                retired = true
                claimUnmap()
            }
            if (unmapNow) unmap()
        }

        /** One stream finished. The last one to leave unmaps, if the book was already retired. */
        private fun release() {
            val unmapNow = synchronized(lock) {
                outstanding--
                claimUnmap()
            }
            if (unmapNow) unmap()
        }

        /** Caller must hold [lock]. */
        private fun claimUnmap(): Boolean {
            if (!retired || outstanding > 0 || unmapped) return false
            unmapped = true
            return true
        }

        private fun unmap() {
            // Nothing here is worth propagating: the caller is a renderer thread whose only useful
            // move on failure is to show the resource as missing.
            runCatching { reader.close() }
        }

        /** Closes the underlying stream once, then hands the count back. */
        private inner class TrackedInputStream(delegate: InputStream) : FilterInputStream(delegate) {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    release()
                }
            }
        }
    }

    companion object {
        /**
         * Strips the [SCHEME] prefix off [absoluteUrl], returning the archive path.
         *
         * Returns null for anything that is not one of ours, which is the host's signal to let the
         * URL load normally instead.
         *
         * The host is matched loosely rather than against one exact prefix: the path is whatever
         * follows the first `/` after the authority, so a document loaded at a different reserved host
         * still resolves. The path is percent-decoded here because the engine hands it back in its
         * encoded form (`My%20Image.jpg`), and the archive stores the decoded name (`My Image.jpg`).
         *
         * The result is normalised, so `..` cannot climb out of whatever directory the renderer
         * resolved against. What survives that is still only ever looked up as a literal entry name
         * inside the open archive — there is no filesystem beneath it — but a base URL the reader
         * generated should not be the thing deciding where a request lands.
         *
         * On the companion rather than the instance because it is pure URL parsing with no archive
         * behind it: a host that wants to ask "which document is this link pointing at?" *before*
         * deciding whether to navigate has no reader of its own, and that is exactly the host that
         * must not treat a book-supplied link as a reason to navigate somewhere.
         */
        fun archivePathOf(absoluteUrl: String): String? {
            if (!isArchiveUrl(absoluteUrl)) return null
            val afterAuthority = absoluteUrl.substringAfter("$SCHEME://")
            val path = afterAuthority.substringAfter('/', "")
                .substringBefore('#')
                .substringBefore('?')
            return percentDecode(path)
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeArchivePath)
                ?.takeIf { it.isNotBlank() }
        }

        /**
         * Reserved scheme for archive-internal resources.
         *
         * `.internal` is reserved by RFC 6761 and can never collide with a real site, so a page
         * that happens to link to a genuinely external image is never mistaken for an archive one.
         */
        const val SCHEME = "epub-archive"

        const val HOST = "book.internal"

        /**
         * The root of the archive in URL form.
         *
         * A valid base URL for a document that lives at the archive root, and the fallback when a
         * source cannot say where its pages live. It is **not** the base URL for a document that
         * lives below the root — see [documentBaseUrl], which is what the reader uses.
         */
        const val BASE_URL = "$SCHEME://$HOST/"

        /**
         * Ceiling on a single resource streamed to the renderer.
         *
         * Above anything a real EPUB holds: the largest illustration in a commercial illustrated
         * novel is a few megabytes and the largest embedded font a few hundred kilobytes. Above the
         * heap a low-end device will surrender before the process is killed, so a crafted entry —
         * or a store-bought 400 MB scan someone dropped in the folder — cannot take the app down.
         */
        const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024

        /**
         * True when [url] is one of ours and must be served from the archive.
         *
         * The renderer calls this before intercepting so a genuinely external URL is left alone —
         * a book that links to an image on a real server must still show it.
         *
         * Matches on the scheme alone rather than one exact `scheme://host/` prefix: the renderer
         * normalises the URL before handing it over, and an exact-prefix test silently stops
         * intercepting the moment a document is loaded at a base URL with a different path shape.
         */
        fun isArchiveUrl(url: String): Boolean = url.startsWith("$SCHEME://", ignoreCase = true)

        /**
         * Percent-decodes a URL path segment-by-segment, preserving `/`.
         *
         * Not `java.net.URLDecoder`: that implements HTML form encoding and turns a literal `+` into
         * a space, which corrupts every EPUB whose file names contain one.
         */
        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    val hi = value[i + 1].hexDigit()
                    val lo = value[i + 2].hexDigit()
                    if (hi >= 0 && lo >= 0) {
                        out.append(((hi shl 4) or lo).toChar())
                        i += 3
                        continue
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        private fun Char.hexDigit(): Int = when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> -1
        }

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

        /**
         * Collapses `.` and `..`, clamping at the archive root.
         *
         * The same rule `EpubPaths.normalize` applies to a manifest href, reached through a copy
         * rather than through `EpubPaths` itself because the renderer hands this class an
         * already-percent-decoded URL rather than an href — but the two converge on one spelling,
         * which is what makes a `..` mean the same thing wherever it arrives from.
         */
        private fun normalizeArchivePath(path: String): String {
            val out = ArrayDeque<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeLast()
                    else -> out.addLast(segment)
                }
            }
            return out.joinToString("/")
        }
    }
}

/**
 * Stops handing out bytes past [limit], by ending the stream rather than throwing.
 *
 * Ending rather than throwing is deliberate: the consumer is the WebView's resource loader, and a
 * truncated body it can lay out is strictly better than an exception it has to turn into a failed
 * load. A clipped image renders as a broken image and a clipped stylesheet applies the rules before
 * the cut — which is how a hostile entry costs one resource instead of the document.
 *
 * `close` still closes the delegate: the byte budget is a limit on *reading*, never a way to leave a
 * file descriptor and a map open.
 */
private class LimitedInputStream(
    delegate: InputStream,
    private val limit: Long,
) : FilterInputStream(delegate) {

    private var delivered = 0L

    override fun read(): Int {
        if (delivered >= limit) return -1
        val value = super.read()
        if (value >= 0) delivered++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (delivered >= limit) return -1
        // Never read past the budget, so the underlying archive is not asked to decompress bytes that
        // would be discarded anyway — which is the expensive half of a decompression bomb.
        val allowed = minOf(len.toLong(), limit - delivered).coerceAtLeast(1L).toInt()
        val read = super.read(b, off, allowed)
        if (read > 0) delivered += read
        return read
    }

    override fun skip(n: Long): Long {
        if (delivered >= limit || n <= 0) return 0
        val skipped = super.skip(minOf(n, limit - delivered))
        if (skipped > 0) delivered += skipped
        return skipped
    }

    override fun available(): Int {
        val left = limit - delivered
        if (left <= 0) return 0
        return minOf(super.available().toLong(), left).toInt()
    }
}
