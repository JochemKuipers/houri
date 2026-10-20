package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import logcat.LogPriority
import mihon.core.archive.EpubReader
import tachiyomi.core.common.util.system.logcat

/**
 * Loader used to load a chapter from a .epub file.
 */
internal class EpubPageLoader(private val reader: EpubReader) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // `getImagesFromPages` collects paths out of the book's own markup, not out of the archive's
        // entry list — a document that names an image the archive does not contain is an ordinary
        // producer mistake, not a malformed book. Each surviving path is checked against the actual
        // entry table before it becomes a page, so a page this reader can never open is not a page
        // (the engine would otherwise spend a decode on a stream that has no other end to read).
        // Dense indexing keeps the page list contiguous — a gap in the indexes reads as a broken
        // translator neighbourhood far away from where the missing image is, and costs nothing here.
        var visible = 0
        val pages = reader.getImagesFromPages().mapNotNull { path ->
            if (!reader.contains(path)) {
                logcat(LogPriority.WARN) { "EPUB image not in archive: $path" }
                return@mapNotNull null
            }
            ReaderPage(visible++).apply {
                stream = { reader.getInputStream(path) ?: error("Missing EPUB image: $path") }
                status = Page.State.Ready
            }
        }
        return pages
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
