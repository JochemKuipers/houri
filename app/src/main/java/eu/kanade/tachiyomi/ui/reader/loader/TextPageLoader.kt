package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.source.epub.EpubArchiveSource

/**
 * Loader for chapters served as text by a [TextSource] — EPUBs and web novels.
 *
 * ### Why text needs no loading state
 *
 * [PageLoader] exists because images arrive slowly, partially, and sometimes not at all: a status
 * machine, a progress flow and a retry path are all loaders for a network fetch. Text arrives with
 * the chapter list. So every page is born [Page.State.Ready], [loadPage] has nothing to do, and
 * [retryPage] is a no-op — the failure mode for text is "the whole chapter failed", which is an
 * exception from [getPages], not a per-page error state.
 *
 * Pages are marked [ReaderPage.textPage] rather than streamed, so the renderer can lay out and, more
 * importantly, measure the document without going back to the source.
 *
 * // KMK -->
 * @since komikku/1.0
 * // KMK <--
 */
internal class TextPageLoader(
    private val source: TextSource,
    private val chapter: Chapter,
) : PageLoader() {

    /** The chapter's pages as the source produced them, kept for length-based progress. */
    private var loadedPages: List<TextPage> = emptyList()

    /** True when the source is local and therefore needs no network for a reload. */
    override var isLocal: Boolean = false

    /** Marks the chapter as text so the viewer picks a text renderer over an image one. */
    val isText: Boolean get() = true

    /**
     * Serves the chapter's images, stylesheets and fonts, when the source can.
     *
     * Handed to the viewer so the archive stays open for as long as the chapter is on screen.
     * Null for a source that inlines everything or serves pages over plain HTTP.
     */
    val resourceSource: TextResourceSource? get() = source as? TextResourceSource

    /**
     * The book's table of contents, when the source publishes one.
     *
     * Narrow on purpose. The reader asks this to decide whether to offer a contents button at all, so
     * the answer it needs is "can you", not "here is an unbounded interface".
     */
    val tocSource: TextTocSource? get() = source as? TextTocSource

    /** In-chapter search, when the source supports it. See [tocSource]. */
    val searchSource: TextSearchSource? get() = source as? TextSearchSource

    /**
     * This chapter in the form the source API speaks.
     *
     * Cached rather than rebuilt: the reader's contents and search surfaces both ask for it, on top of
     * the page list itself, and the hop is not free. Null only for a chapter with no database row,
     * which [getPages] treats as fatal.
     */
    val sChapter: SChapter? by lazy {
        // toSChapter() is defined on the *domain* Chapter, while the loader is handed the legacy
        // database one, so the adapter hop is required - it is the same hop DownloadPageLoader does.
        chapter.toDomainChapter()?.toSChapter()
    }

    override suspend fun getPages(): List<ReaderPage> = withIOContext {
        val sChapter = sChapter
            ?: throw IllegalStateException("Chapter ${chapter.url} is missing its database id")
        val pages = source.getTextPageList(sChapter)
        loadedPages = pages

        pages.mapIndexed { index, textPage ->
            ReaderPage(index, url = textPage.url).apply {
                this.textPage = textPage
                status = Page.State.Ready
            }
        }
    }

    /**
     * Character length of each page, for [eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelProgress].
     *
     * Measured on the markup rather than the rendered text because the markup is what the loader
     * actually holds, and the ratio between documents is what weighting needs — an approximation
     * that is consistent within a chapter is enough.
     */
    fun documentLengths(): List<Int> = loadedPages.map { it.text.length }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    /**
     * Releases everything this chapter was holding.
     *
     * The archive lease goes first, and it is the reason this override exists at all. A source that
     * maps books keeps a memory map and a file descriptor per open one; before this call nothing in
     * the system ever gave it back, because the loader is the only object whose lifetime *is* the
     * chapter's — the viewer is rebuilt on an orientation change with the chapter still open, and the
     * source is asked for pages rather than told they are no longer wanted. A session that read fifty
     * books held fifty maps until the process died.
     *
     * Safe to call while the renderer is still streaming out of the archive: the release marks the
     * book unreachable and the unmap waits for the last open stream to close. See
     * `EpubResourceReader`.
     */
    override fun recycle() {
        super.recycle()
        sChapter?.url?.let { url ->
            (source as? EpubArchiveSource)?.releaseResourceReader(url)
        }
        // Not actually freeing the markup — every ReaderPage still holds its own TextPage — but it
        // stops the loader being a second, redundant reference to the whole chapter's text.
        loadedPages = emptyList()
    }
}
