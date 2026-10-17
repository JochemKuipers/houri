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

    /**
     * A series folder by name, or null when it does not exist or the name is not one of ours.
     *
     * [isEntryName]-checked because [name] is not always something this class produced. A series
     * URL comes from the browse surface, but a *chapter* URL comes from the database and can
     * arrive from a sync, a backup restore or another device — and that one is split on `/` and
     * fed straight back in here as [LocalEpubSource.resolveChapterFile] does. A name of `..` or
     * `../novels/Other` therefore has to be refused here rather than trusted to `UniFile`.
     */
    fun getSeriesDirectory(name: String): UniFile? =
        if (!isEntryName(name)) {
            null
        } else {
            getBaseDirectory()?.findFile(name)?.takeIf { it.isDirectory }
        }

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

        /**
         * True when [name] is safe to resolve as a **single** entry inside the library.
         *
         * Every segment of a library path is a name this class read off a directory listing, except
         * the ones that come back out of a chapter URL — and a chapter URL is stored data, which
         * means it is whatever a sync or a restored backup put there. So the check is on the shape
         * of the name rather than on where it came from:
         *
         *  - no `/` or `\`, so one name is one segment and cannot reach a sibling;
         *  - not `.` or `..`, which are the two names that mean "here" and "above";
         *  - no control character, and in particular no NUL, which truncates the string on its way to
         *    the storage provider, so a name carrying one resolves as the part before it — which is
         *    not a name anyone asked for.
         *
         * The point is not that a traversal is reachable afterwards; the names it could reach are
         * the user's own files. The point is that a name which came off the network must not decide
         * which of them.
         */
        fun isEntryName(name: String): Boolean =
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                name.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }
    }
}
