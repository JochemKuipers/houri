package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.EntryType
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TocEntry
import logcat.LogPriority
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubReader
import mihon.core.archive.EpubResourceServer
import mihon.core.archive.EpubTextSource
import mihon.core.archive.epubReader
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.InputStream

/**
 * Serves the `.epub` chapters of the **manga** library as books.
 *
 * ### Why this is not a [TextSource]
 *
 * It cannot be one: `TextSource` extends `Source`, and this object is a helper, not a source. The
 * manga source itself implements the text interfaces and delegates here — which is also what keeps
 * the routing honest, because a host can then see one object that is both a `Source` and a
 * `MixedContentSource` and know the source-level answer is not the whole story.
 *
 * ### Why the manga path is untouched
 *
 * `LocalSource` treats an `.epub` chapter as `Format.Epub`, which the loader chain turns into
 * `EpubPageLoader` — one page per illustration, the art-book behaviour that `source-epub/AGENTS.md`
 * records as relied upon. That is unchanged and is still what happens for an entry resolved as
 * [EntryType.COMIC]. What this adds is the other answer: one document per spine item, the
 * publisher's stylesheet kept, its illustrations inline. Nothing about the file changes — only which
 * renderer claims it.
 *
 * ### Sharing
 *
 * The URL namespace, the refcounted archive lease and the book parsing all live in `core:archive`, so
 * a book reads identically here and in the built-in EPUB library. Two implementations would drift,
 * and the drift would show as a book that works in one library and not the other.
 */
internal class LocalEpubTextAdapter(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    /**
     * Archive leases for the chapters currently on screen.
     *
     * Owned here rather than by the manga loader because this is the object that opens them for the
     * renderer to stream images out of; the release comes from the same place either way.
     */
    private val resources = EpubResourceServer<SChapter>(
        keyOf = { it.url },
        bookProvider = { chapter -> openEpub(chapter) },
    )

    private val text = EpubTextSource { chapter ->
        resources.readerFor(chapter) ?: openEpub(chapter)?.let { resources.adopt(chapter.url, it) }
    }

    /** What a book reader can rely on. */
    val textCapabilities: TextCapabilities = TextCapabilities.EPUB

    /**
     * The `.epub` file a chapter URL names, opened for reading.
     *
     * A manga chapter URL is `"<series>/<file>"`, and it is *stored* data — it reaches this from the
     * database, so from a sync, a restored backup or another device. Both halves are checked rather
     * than trusted to `UniFile`; without the check, a url of `"../novels/Other/secret.epub"`
     * resolves to a folder other than the one it names.
     */
    private fun openEpub(chapter: SChapter): EpubReader? {
        val parts = chapter.url.split('/', limit = 2)
        if (parts.size != 2) return null
        val series = parts[0]
        val file = parts[1]
        if (!isEntryName(series) || !isEntryName(file)) {
            logcat(LogPriority.ERROR) { "LocalSource: refusing epub chapter url ${chapter.url}" }
            return null
        }
        return fileSystem.getBaseDirectory()
            ?.findFile(series)
            ?.findFile(file)
            ?.takeIf { isEpub(it) }
            ?.epubReader(context)
    }

    fun textPages(chapter: SChapter): List<TextPage> = text.textPages(chapter)

    /**
     * The chapter's documents without their text, so the reader opens a novel at the cost of the
     * document it shows rather than the cost of the whole book.
     */
    suspend fun textPageIndex(chapter: SChapter): List<TextPage> = text.getTextPageIndex(chapter)

    /** One document's markup, read on demand. */
    suspend fun textPageText(chapter: SChapter, page: TextPage): String? =
        text.getTextPageText(chapter, page)

    suspend fun tableOfContents(chapter: SChapter): List<TocEntry> = text.getTableOfContents(chapter)

    /**
     * The URL each document must be loaded at.
     *
     * `TextPage.baseHref` is an in-archive path, which is meaningless to a layout engine on its own.
     * Without this every relative reference in the book resolves against the archive root rather than
     * the document's own directory, and the publisher's stylesheets, images and fonts all 404 —
     * silently, leaving the book's text wearing only the reader's styling.
     */
    fun baseUrlFor(page: TextPage): String = EpubArchiveUrls.documentBaseUrl(page.baseHref)

    fun openResource(absoluteUrl: String): InputStream? = resources.openResource(absoluteUrl)

    fun contentTypeFor(absoluteUrl: String): String? = resources.contentTypeFor(absoluteUrl)

    /** Retires the archive for [chapterUrl]. See `EpubResourceServer.release`. */
    fun releaseResourceReader(chapterUrl: String) = resources.release(chapterUrl)

    /** Retires every archive. */
    fun releaseAllResourceReaders() = resources.releaseAll()

    /**
     * Whether this manga's chapters should be read as a book.
     *
     * Declared per entry rather than answering for the source, because this source serves both: a
     * folder of comics and a file that is a novel are the same kind of thing to it.
     *
     * Answers BOOK only when every chapter in the folder is an `.epub`. A *mixed* folder is a comic
     * with a stray file in it, and calling that a book would hand the reader a prose view of
     * something that is mostly pictures. Everything else answers null and leaves the decision to the
     * host's detector and to the user's override.
     */
    fun entryTypeOf(manga: SManga): EntryType? =
        if (allChaptersAreEpub(manga.url)) EntryType.BOOK else null

    /**
     * Whether this chapter's book reads as prose rather than as a strip of illustrations.
     *
     * The last word when nobody has declared the entry's type. Opens the archive, so it costs a map;
     * a host calls it once per chapter and never when a user has already chosen.
     */
    fun detectBookEntry(chapter: SChapter): Boolean =
        openEpub(chapter)?.use { it.readsAsProse() } ?: false

    private fun allChaptersAreEpub(seriesUrl: String): Boolean {
        val dir = seriesDirectory(seriesUrl) ?: return false
        val files = dir.listFiles().orEmpty()
            .filter { !it.isDirectory && !it.name.orEmpty().startsWith('.') }
        return files.isNotEmpty() && files.all { isEpub(it) }
    }

    private fun seriesDirectory(seriesUrl: String): UniFile? {
        if (!isEntryName(seriesUrl)) return null
        return fileSystem.getBaseDirectory()?.findFile(seriesUrl)?.takeIf { it.isDirectory }
    }

    private companion object {
        fun isEpub(file: UniFile): Boolean =
            !file.isDirectory && file.extension.equals("epub", ignoreCase = true)

        /**
         * A single path segment, and one this source produced.
         *
         * Stored urls reach the source from the database, so from a sync or a restored backup; `..`
         * would resolve outside the library and a NUL truncates the string on its way to the storage
         * provider, so both would name something other than what was asked for.
         */
        fun isEntryName(name: String): Boolean =
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                name.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }
    }
}
