package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Creates the [PageLoader] for a chapter. The default [Default] chain handles
 * downloaded → merged → local → http sources; custom chapter types register
 * via [Registry.register] with a lower [PageLoaderProvider.order].
 */
interface PageLoaderProvider {
    val order: Int
    fun create(request: PageLoaderRequest): PageLoader?
}

data class PageLoaderRequest(
    val context: Context,
    val chapter: ReaderChapter,
    val manga: Manga,
    val source: Source,
    val sourceManager: SourceManager,
    val downloadManager: DownloadManager,
    val downloadProvider: DownloadProvider,
    val readerPrefs: ReaderPreferences,
    val mergedReferences: List<MergedMangaReference>,
    val mergedManga: Map<Long, Manga>?,
)

object PageLoaderRegistry {
    // KMK -->
    private val providers = mutableListOf<PageLoaderProvider>(TextPageLoaderProvider, DefaultPageLoaderProvider)
    // KMK <--

    fun register(provider: PageLoaderProvider) {
        providers.removeAll { it::class == provider::class }
        providers.add(provider)
        providers.sortBy { it.order }
    }

    fun create(request: PageLoaderRequest): PageLoader {
        // KMK --> One line per loader decision, because "a book opens as pictures" is otherwise
        // indistinguishable from "the reader ignored the book". The provider that claims a chapter
        // decides which half of the app it gets, and nothing about that choice is visible from the UI:
        // an EPUB served as images by EpubPageLoader looks exactly like a working comic reader.
        logcat {
            "Reader: choosing a page loader for '${request.chapter.chapter.name}' " +
                "(chapter url=${request.chapter.chapter.url}) from " +
                "source=${request.source::class.java.simpleName}#${request.source.id} " +
                "lang=${request.source.lang} isTextSource=${request.source is TextSource}"
        }
        for (provider in providers.sortedBy { it.order }) {
            val loader = runCatching { provider.create(request) }.getOrNull()
            logcat {
                "Reader:   ${provider::class.java.simpleName}(order=${provider.order}) " +
                    "-> ${loader?.let { it::class.java.simpleName } ?: "declined"}"
            }
            if (loader != null) {
                logcat {
                    "Reader: loader chosen = ${loader::class.java.simpleName} for " +
                        "source#${request.source.id} (${request.source.name})"
                }
                return loader
            }
        }
        logcat(LogPriority.ERROR) {
            "Reader: NO page loader provider claimed chapter url=${request.chapter.chapter.url} " +
                "from source#${request.source.id}"
        }
        error(request.context.stringResource(MR.strings.loader_not_implemented_error))
    }
    // KMK <--
}

// KMK --> Text sources (EPUB, web novel) are consulted first: they are a distinct contract with no
// image fallback, and letting the default chain try an image loader first would produce a confusing
// "page list empty" for every chapter.
private object TextPageLoaderProvider : PageLoaderProvider {
    override val order: Int = -10

    override fun create(request: PageLoaderRequest): PageLoader? {
        val source = request.source as? TextSource ?: return null
        // A downloaded chapter has no text to load — downloads only store images — so a text source
        // is always read live.
        return TextPageLoader(source, request.chapter.chapter).apply {
            isLocal = source !is HttpSource
        }
    }
}
// KMK <--

private object DefaultPageLoaderProvider : PageLoaderProvider {
    override val order: Int = 100

    override fun create(request: PageLoaderRequest): PageLoader? {
        val context = request.context
        val chapter = request.chapter
        val manga = request.manga
        val source = request.source
        val dbChapter = chapter.chapter
        fun localLoader(source: LocalSource): PageLoader {
            return source.getFormat(dbChapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
        }
        // SY --> merged sources resolve their own per-chapter source first
        if (source is MergedSource) {
            val mangaReference = request.mergedReferences.firstOrNull {
                it.mangaId == chapter.chapter.manga_id
            } ?: error("Merge reference null")
            val resolved = request.sourceManager.get(mangaReference.mangaSourceId)
                ?: error("Source ${mangaReference.mangaSourceId} was null")
            val mergedEntry = request.mergedManga?.get(chapter.chapter.manga_id)
                ?: error("Manga for merged chapter was null")
            val isMergedMangaDownloaded = request.downloadManager.isChapterDownloaded(
                chapterName = chapter.chapter.name,
                chapterScanlator = chapter.chapter.scanlator,
                chapterUrl = chapter.chapter.url,
                mangaTitle = mergedEntry.ogTitle,
                sourceId = mergedEntry.source,
                skipCache = true,
            )
            return when {
                isMergedMangaDownloaded -> DownloadPageLoader(
                    chapter = chapter,
                    manga = mergedEntry,
                    source = resolved,
                    downloadManager = request.downloadManager,
                    downloadProvider = request.downloadProvider,
                )
                resolved is HttpSource -> HttpPageLoader(chapter, resolved)
                resolved is LocalSource -> localLoader(resolved)
                else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
            }
        }
        // SY <--
        val isDownloaded = request.downloadManager.isChapterDownloaded(
            chapterName = dbChapter.name,
            chapterScanlator = dbChapter.scanlator,
            chapterUrl = dbChapter.url,
            // SY -->
            mangaTitle = manga.ogTitle,
            // SY <--
            sourceId = manga.source,
            skipCache = true,
        )
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                request.downloadManager,
                request.downloadProvider,
            )
            source is LocalSource -> localLoader(source)
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(
                context.stringResource(MR.strings.source_not_installed, source.toString()),
            )
            else -> null
        }
    }
}
