package eu.kanade.tachiyomi.data.download

import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetNonLibraryManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Bulk deletion of downloaded chapters, for the two maintenance actions in Settings → Data and
 * storage (issue #82): everything on disk, or only the entries that are not in the library.
 *
 * Each downloaded directory is resolved against the database first. Entries with a manga row go
 * through [DownloadManager.deleteManga], which also drops their queued downloads and their cache
 * bookkeeping; directories that match no row (a removed extension, a cleared library) are deleted
 * raw and forgotten by name, the same way the "Downloaded & cached content" screen handles them.
 *
 * The local source is never touched: its "downloads" are the user's own files.
 */
class DownloadBulkDelete(
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val getNonLibraryManga: GetNonLibraryManga,
) {

    data class Result(
        /** Number of entries (manga directories) removed. */
        val deletedEntries: Int,
        /** Whether any entry could not be removed. */
        val failed: Boolean,
    )

    /** Removes every download, library entries included. */
    suspend fun deleteAll(): Result = withIOContext {
        // A running download would recreate the directory this sweep is about to delete.
        downloadManager.clearQueue()
        delete(onlyNotInLibrary = false)
    }

    /** Removes only the downloads whose entry is not in the library. */
    suspend fun deleteNotInLibrary(): Result = withIOContext {
        delete(onlyNotInLibrary = true)
    }

    private suspend fun delete(onlyNotInLibrary: Boolean): Result {
        val favorites = getFavorites.await()
        val libraryDirs = favorites.mapTo(HashSet()) {
            it.source to downloadProvider.getMangaDirName(it.ogTitle)
        }
        // Favorites first so a library entry is always resolved to its own row; the non-library
        // list is only consulted for directories the library does not claim.
        val knownBySource = (favorites + getNonLibraryManga.await()).groupBy { it.source }

        var deleted = 0
        var failed = false
        downloadCache.snapshotDownloadedMangaDirs().forEach { dir ->
            if (dir.sourceId == 0L) return@forEach
            val inLibrary = (dir.sourceId to dir.mangaDirName) in libraryDirs
            if (onlyNotInLibrary && inLibrary) return@forEach

            val source = sourceManager.getOrStub(dir.sourceId)
            val manga = knownBySource[dir.sourceId]?.firstOrNull {
                downloadProvider.getMangaDirName(it.ogTitle) == dir.mangaDirName
            }

            try {
                if (manga != null) {
                    downloadManager.deleteManga(manga, source)
                } else {
                    downloadProvider.findMangaDirByDirName(dir.mangaDirName, source)?.delete()
                    downloadCache.removeMangaDirByIdName(dir.sourceId, dir.mangaDirName)

                    val sourceDir = downloadProvider.findSourceDir(source)
                    if (sourceDir?.listFiles()?.isEmpty() == true) {
                        sourceDir.delete()
                        downloadCache.removeSource(source)
                    }
                }
                deleted++
            } catch (e: Throwable) {
                failed = true
                logcat(LogPriority.ERROR, e) { "Failed to delete downloads of ${dir.mangaDirName}" }
            }
        }

        return Result(deleted, failed)
    }
}
