package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.ColorInt
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.MAX_FILE_NAME_BYTES
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import mihon.core.archive.EpubArchiveUrls
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Image-export actions of the reader, extracted from [ReaderViewModel]:
 * saving the current page (single or merged spread), sharing it, setting covers
 * (page, picked file, book illustration) and reading book-image bytes.
 */
private const val DATA_URI_PREFIX = "data:"

internal class ReaderImageActions(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val imageSaver: ImageSaver,
    private val readerPreferences: eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences,
    private val coverCache: CoverCache,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val provideManga: () -> Manga?,
    private val provideState: () -> ReaderViewModel.State,
    private val sendEvent: suspend (ReaderViewModel.Event) -> Unit,
) {
/**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.chapter.displayNumber(page)}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

/**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (provideState().dialog as? ReaderViewModel.Dialog.PageActions)?.extraPage
        } else {
            (provideState().dialog as? ReaderViewModel.Dialog.PageActions)?.page
        }
        // SY <--
        // KMK --> The page's bytes are the precondition for every action in this sheet, and a text
        // chapter's page is a document with no image stream — so the stream, not the page status, is
        // what decides whether there is anything to act on. `page.stream!!` below used to be the
        // check, which crashed on the IO worker with a bare NPE the first time a book reached here.
        val readyPage = page?.takeIf { it.status == Page.State.Ready } ?: return
        val stream = readyPage.stream ?: return
        // KMK <--
        val manga = provideManga() ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, readyPage)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga().get()) {
            DiskUtil.buildValidFilename(manga.title)
        } else {
            ""
        }

        // Copy file in background.
        scope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = stream,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    sendEvent(ReaderViewModel.Event.SavedImage(ReaderSaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                sendEvent(ReaderViewModel.Event.SavedImage(ReaderSaveImageResult.Error(e)))
            }
        }
    }

// SY -->
    fun saveImages() {
        val (firstPage, secondPage) = (provideState().dialog as? ReaderViewModel.Dialog.PageActions ?: return)
        val viewer = provideState().viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return

        val manga = provideManga() ?: return

        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Copy file in background.
        scope.launchNonCancellable {
            try {
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    matchHeights = viewer.config.matchDoublePageHeights,
                    location = Location.Pictures.create(DiskUtil.buildValidFilename(manga.title)),
                    manga = manga,
                )
                sendEvent(ReaderViewModel.Event.SavedImage(ReaderSaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                sendEvent(ReaderViewModel.Event.SavedImage(ReaderSaveImageResult.Error(e)))
            }
        }
    }

    private fun saveImages(
        page1: ReaderPage,
        page2: ReaderPage,
        isLTR: Boolean,
        @ColorInt bg: Int,
        matchHeights: Boolean,
        location: Location,
        manga: Manga,
    ): Uri {
        // KMK --> A missing stream is an error with a message, not a null dereference: this runs on
        // the IO worker inside a coroutine, so the caller's `try` is long gone and an NPE here takes
        // the process down with nothing in the log but a type name.
        val stream1 = page1.stream ?: throw Exception("Page ${page1.number} has no image stream")
        ImageUtil.findImageType(stream1) ?: throw Exception("Not an image")
        val stream2 = page2.stream ?: throw Exception("Page ${page2.number} has no image stream")
        ImageUtil.findImageType(stream2) ?: throw Exception("Not an image")
        val imageBitmap = ImageDecoder.newInstance(stream1())?.decode()!!
        val imageBitmap2 = ImageDecoder.newInstance(stream2())?.decode()!!

        val chapter = page1.chapter.chapter

        // Build destination file.
        val filenameSuffix = " - ${page1.number}-${page2.number}.jpg"
        val filename = DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix

        return imageSaver.save(
            image = Image.Page(
                inputStream = { ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, 0, bg, matchHeights).inputStream() },
                name = filename,
                location = location,
            ),
        )
    }
// SY <--

/**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(
        copyToClipboard: Boolean,
        // SY -->
        useExtraPage: Boolean,
        // SY <--
    ) {
        // SY -->
        val page = if (useExtraPage) {
            (provideState().dialog as? ReaderViewModel.Dialog.PageActions)?.extraPage
        } else {
            (provideState().dialog as? ReaderViewModel.Dialog.PageActions)?.page
        }
        // SY <--
        // KMK --> See `saveImage`: the bytes are the precondition, and a document has none. This is
        // the `stream!!` the crash report pointed at — a long press in a book opened this sheet, and
        // every option in it died here on the IO worker.
        val readyPage = page?.takeIf { it.status == Page.State.Ready } ?: return
        val stream = readyPage.stream ?: return
        // KMK <--
        val manga = provideManga() ?: return

        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, readyPage)

        try {
            scope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = stream,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                val event = if (copyToClipboard) {
                    ReaderViewModel.Event.CopyImage(uri)
                } else {
                    ReaderViewModel.Event.ShareImage(uri, readyPage)
                }
                sendEvent(event)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

// SY -->
    fun shareImages(copyToClipboard: Boolean) {
        val (firstPage, secondPage) = (provideState().dialog as? ReaderViewModel.Dialog.PageActions ?: return)
        val viewer = provideState().viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return
        val manga = provideManga() ?: return

        val destDir = context.cacheImageDir

        try {
            scope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    matchHeights = viewer.config.matchDoublePageHeights,
                    location = Location.Cache,
                    manga = manga,
                )
                sendEvent(if (copyToClipboard) ReaderViewModel.Event.CopyImage(uri) else ReaderViewModel.Event.ShareImage(uri, firstPage, secondPage))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }
// SY <--

/**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (provideState().dialog as? ReaderViewModel.Dialog.PageActions)?.extraPage
        } else {
            (provideState().dialog as? ReaderViewModel.Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = provideManga() ?: return
        val stream = page.stream ?: return

        scope.launchNonCancellable {
            val result = try {
                manga.editCover(coverManager, stream(), updateManga, coverCache)
                if (manga.isLocal() || manga.favorite) {
                    ReaderSetAsCoverResult.Success
                } else {
                    ReaderSetAsCoverResult.AddToLibraryFirst
                }
            } catch (_: Exception) {
                ReaderSetAsCoverResult.Error
            }
            sendEvent(ReaderViewModel.Event.SetCoverResult(result))
        }
    }

// KMK -->
/**
     * Writes the image of the selected page to a temporary cache file so it can
     * be cropped before being applied as cover. Returns null on failure.
     */
    suspend fun getCoverEditUri(useExtraPage: Boolean): Uri? = withIOContext {
        val dialog = provideState().dialog as? ReaderViewModel.Dialog.PageActions
        val page = if (useExtraPage) dialog?.extraPage else dialog?.page
        if (page?.status != Page.State.Ready) return@withIOContext null
        val stream = page.stream ?: return@withIOContext null

        runCatching {
            val dir = File(context.cacheDir, "cover_edit").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "source")
            file.outputStream().use { output -> stream().copyTo(output) }
            Uri.fromFile(file)
        }.getOrNull()
    }

    fun setCoverFromUri(uri: Uri) {
        val currentManga = provideManga() ?: return
        scope.launchNonCancellable {
            val result = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    currentManga.editCover(coverManager, stream, updateManga, coverCache)
                }
                if (currentManga.isLocal() || currentManga.favorite) {
                    ReaderSetAsCoverResult.Success
                } else {
                    ReaderSetAsCoverResult.AddToLibraryFirst
                }
            } catch (_: Exception) {
                ReaderSetAsCoverResult.Error
            }
            sendEvent(ReaderViewModel.Event.SetCoverResult(result))
        }
    }

/**
     * Sets the image [imageUrl] names — an illustration of the open book, long-pressed in the
     * viewer — as cover, and notifies the UI of the result.
     *
     * The stream is resolved from the loader rather than from the viewer on purpose: the dialog
     * outlives a viewer that an orientation change may already have rebuilt, and the loader is the
     * object whose lifetime *is* the open chapter. [launchNonCancellable] for the same reason the
     * other cover paths use it — a write that a cancelled flow would interrupt halfway is a cover
     * the user believed was set.
     */
    fun setBookImageAsCover(imageUrl: String) {
        val currentManga = provideManga() ?: return
        scope.launchNonCancellable {
            val result = try {
                if (!currentManga.isLocal() && !currentManga.favorite) {
                    ReaderSetAsCoverResult.AddToLibraryFirst
                } else {
                    val stream = withIOContext { bookImageStream(imageUrl) }
                    if (stream == null) {
                        ReaderSetAsCoverResult.Error
                    } else {
                        stream.use { currentManga.editCover(coverManager, it, updateManga, coverCache) }
                        ReaderSetAsCoverResult.Success
                    }
                }
            } catch (_: Exception) {
                ReaderSetAsCoverResult.Error
            }
            sendEvent(ReaderViewModel.Event.SetCoverResult(result))
        }
    }

/**
     * Reads the bytes of the image [imageUrl] names — the zoom viewer's payload.
     *
     * The bytes rather than a stream: the viewer holds its own copy and decodes it off the main
     * thread, so nothing here needs to stay open, and a stream left in a Compose state would be a
     * resource a recomposition has no reason to close.
     *
     * Suspending rather than posting back through dialog state: the viewer outlives the read —
     * dismisses, rotations and chapter swaps all arrive while the bytes are in flight — and a
     * cancelled read simply fails to produce an image, which the viewer already renders as "could
     * not decode".
     */
    suspend fun getBookImageBytes(imageUrl: String): ByteArray? = withIOContext {
        runCatching { bookImageStream(imageUrl)?.use { stream -> stream.readBytes() } }.getOrNull()
    }

/**
     * Opens an image inside the open book, or null when the URL is not one the chapter can answer.
     *
     * The hit test hands over exactly two spellings: a `book.internal` reference, which the
     * chapter's [eu.kanade.tachiyomi.source.text.TextResourceSource] streams out of the archive,
     * and a `data:` URI the document inliner embedded in the markup, which decodes into the bytes
     * the engine drew. Everything else — a plate the book links at from a real server — is not
     * this book's art to copy, and comes back as null.
     */
    private fun bookImageStream(imageUrl: String): InputStream? {
        val loader = provideState().currentChapter?.pageLoader as? TextPageLoader ?: return null
        return when {
            EpubArchiveUrls.isArchiveUrl(imageUrl) -> loader.resourceSource?.openResource(imageUrl)
            imageUrl.startsWith(DATA_URI_PREFIX, ignoreCase = true) -> dataUriStream(imageUrl)
            else -> null
        }
    }

/** Decodes a `data:` URI into the bytes it carries, or null when it is not one this reads. */
    private fun dataUriStream(dataUri: String): InputStream? {
        val payload = dataUri.substringAfter(',', "").trim()
        val header = dataUri.substringBefore(',').removePrefix("data:").lowercase()
        if (payload.isEmpty() || !header.endsWith(";base64")) return null
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return ByteArrayInputStream(bytes)
    }
// KMK <--
}

enum class ReaderSetAsCoverResult {
    Success,
    AddToLibraryFirst,
    Error,
}

sealed interface ReaderSaveImageResult {
    class Success(val uri: Uri) : ReaderSaveImageResult
    class Error(val error: Throwable) : ReaderSaveImageResult
}
