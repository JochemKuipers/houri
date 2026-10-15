package tachiyomi.source.epub

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.storage.extension
import tachiyomi.domain.storage.service.StorageManager

/**
 * Locates the on-disk EPUB library.
 *
 * Deliberately rooted at `<local>/novels` rather than `<local>` itself. The manga local source
 * already lists everything under `<local>` as a series, so sharing the directory would make the two
 * sources show each other's folders and fight over the same names. Nesting one level down keeps
 * them independent and still inherits the storage permission the user has already granted for the
 * local source directory.
 */
@Inject
@SingleIn(AppScope::class)
class EpubSourceFileSystem(
    private val storageManager: StorageManager,
) {

    /** The library root, created on demand. Null when the base directory is not accessible. */
    fun getBaseDirectory(): UniFile? =
        storageManager.getLocalSourceDirectory()?.createDirectory(NOVELS_PATH)

    /** Every series folder in the library. */
    fun getSeriesDirectories(): List<UniFile> =
        getBaseDirectory()?.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }

    /** A series folder by name, or null when it does not exist. */
    fun getSeriesDirectory(name: String): UniFile? =
        getBaseDirectory()?.findFile(name)?.takeIf { it.isDirectory }

    /** The `.epub` files inside a series, in the order they should appear as chapters. */
    fun getBooksInSeries(name: String): List<UniFile> =
        getSeriesDirectory(name)?.listFiles().orEmpty()
            .filter { !it.isDirectory && isEpub(it) }

    /** True when [file] is an EPUB. */
    fun isEpub(file: UniFile): Boolean =
        !file.isDirectory && file.extension.equals(EPUB_EXTENSION, ignoreCase = true)

    companion object {
        /** Sub-directory of the local source directory that holds the novel library. */
        const val NOVELS_PATH = "novels"

        const val EPUB_EXTENSION = "epub"
    }
}
