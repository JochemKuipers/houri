package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.Downloader.Companion.TMP_DIR_SUFFIX
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import logcat.LogPriority
import mihon.core.archive.CbzCrypto
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import java.io.File
import java.util.Locale

/**
 * File-level operations of the download pipeline, extracted from [Downloader]:
 * copying page images out of the chapter cache, deriving extensions, splitting tall
 * images, verifying finished downloads and archiving a finished chapter as (encrypted) CBZ.
 */
internal class DownloadFileOps(
    private val context: Context,
    private val downloadPreferences: DownloadPreferences,
) {
    /**
     * Copies the image from the cache into the download's temporary directory.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages().get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(
                tmpDir,
                imageFile,
                filenamePrefix,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Archive the chapter pages as a CBZ.
     */
    fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        // SY -->
        val encrypt = CbzCrypto.getPasswordProtectDlPref() && CbzCrypto.isPasswordSet()
        // SY <--

        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")
        if (zip?.isFile != true) throw Exception("Failed to create CBZ file for downloaded chapter")
        ZipWriter(context, zip, /* SY --> */ encrypt /* SY <-- */).use { writer ->
            tmpDir.listFiles()?.forEach { file ->
                writer.write(file)
            }
        }
        // KMK --> The rename is the commit point and its result used to be ignored: on a
        // provider that refuses it (SAF quirks, storage full), the chapter was left as
        // ".cbz_tmp" with no ".cbz" beside it, and opening it later fell through to an empty
        // page list with no hint why. Fail the download instead - the retry can finalize it.
        // KMK <--
        val renamed = zip.renameTo("$dirname.cbz")
        if (!renamed) {
            throw Exception("Failed to finalize $dirname.cbz (rename failed) - the storage may be full or busy")
        }
        // KMK --> Validate the committed archive before the tmp dir is deleted. The zip writer
        // can complete while the underlying storage silently drops bytes (SAF, SD cards) or the
        // process dies mid-write; the result was a .cbz that failed to open on every read with
        // "Truncated ZIP file data" / "mmap failed: EINVAL" forever - the only recovery being
        // delete + re-download by hand. An index pass over the archive the moment it is written
        // costs one cheap seek-skip walk and guarantees the file that lands is readable; when it
        // is not, delete it (it would block the rename on retry) and fail the download so the
        // user is told to try again rather than owning a permanently broken chapter.
        // KMK <--
        val finalFile = mangaDir.findFile("$dirname.cbz")
        try {
            finalFile?.archiveReader(context).use { reader ->
                if (reader == null || reader.size <= 0L) {
                    throw Exception("Archived CBZ is empty")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Archived CBZ failed validation: $dirname" }
            finalFile?.delete()
            throw Exception(
                context.stringResource(KMR.strings.download_archive_corrupt_error, e.message ?: "unreadable archive"),
                e,
            )
        }
        tmpDir.delete()
    }
}
