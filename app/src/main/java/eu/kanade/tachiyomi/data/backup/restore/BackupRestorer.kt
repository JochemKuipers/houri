package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.concurrency.AppDispatchersHolder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MANGA_CHUNK_SIZE = 250

private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 250L

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    // SY <--
    // KMK -->
    private val feedRestorer: FeedRestorer = FeedRestorer(),
    // KMK <--
) {

    private val restoreAmount = AtomicInteger(0)
    private val restoreProgress = AtomicInteger(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    private val lastProgressPost = AtomicLong(0L)

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        // Suppress organic achievement increments for DB imports — tracker/history counts should not inflate organic stats
        val achievementPrefs = try {
            mihon.app.di.globalAppGraph.achievementPreferences
        } catch (_: Exception) {
            null
        }
        val prevSuppress = achievementPrefs?.suppressOrganicForImport
        // A restore rewrites the whole unlocked-achievements preference in one write, so every
        // id the user ever earned arrives as a single "new" change. Notifying per id queued one
        // toast and one sound per achievement — on a fresh install that is the entire catalogue,
        // staggered 900ms apart. Bulk mode collapses the restore into one summary; it has to
        // bracket the whole restore, because the restore is exactly what produces the burst.
        val unlockNotifier = try {
            mihon.app.di.globalAppGraph.achievementNotifier
        } catch (_: Exception) {
            null
        }
        unlockNotifier?.setBulkMode(true)
        try {
            // Separate function so the bulk-mode bracket stays one indentation level out and
            // its `finally` cannot be skipped by anything in the restore body.
            restoreFromFileBulk(uri, options, achievementPrefs, prevSuppress)
        } finally {
            // A Throwable escaping the restore must not leave bulk mode latched, which would
            // silence toasts for the rest of the process. Whatever the restore unlocked is
            // summarised in exactly one line on the way out.
            runCatching { unlockNotifier?.setBulkMode(false) }
        }
    }

    private suspend fun restoreFromFileBulk(
        uri: Uri,
        options: RestoreOptions,
        achievementPrefs: tachiyomi.domain.achievement.service.AchievementPreferences?,
        prevSuppress: Boolean?,
    ) {
        try {
            achievementPrefs?.suppressOrganicForImport = true
            val decodeStart = SystemClock.elapsedRealtime()
            val backup = BackupDecoder(context).decode(uri)
            logcat(LogPriority.INFO) {
                "Restore decoded ${backup.backupManga.size} entries in ${SystemClock.elapsedRealtime() - decodeStart}ms"
            }

            // Store source mapping for error messages
            val backupMaps = backup.backupSources
            sourceMapping = backupMaps.associate { it.sourceId to it.name }

            if (options.libraryEntries) {
                restoreAmount.addAndGet(backup.backupManga.size)
            }
            if (options.categories) {
                restoreAmount.incrementAndGet()
            }
            // SY -->
            if (options.savedSearchesFeeds) {
                restoreAmount.incrementAndGet()
            }
            // SY <--
            if (options.appSettings) {
                restoreAmount.incrementAndGet()
            }
            if (options.extensionStores) {
                restoreAmount.addAndGet(backup.backupExtensionStores.size)
            }
            if (options.sourceSettings) {
                restoreAmount.incrementAndGet()
            }

            coroutineScope {
                // KMK --> Needed before restoreManga: a backup's category reference only means
                // anything once the tree exists and each backup id has its new database id.
                val restoredCategoryIds = if (options.categories) {
                    restoreCategories(backup.backupCategories)
                } else {
                    emptyMap()
                }
                // KMK <--
                // SY -->
                if (options.savedSearchesFeeds) {
                    restoreSavedSearches(
                        backup.backupSavedSearches,
                        // KMK -->
                        backup.backupFeeds,
                        // KMK <--
                    )
                }
                // SY <--
                if (options.appSettings) {
                    restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
                }
                if (options.sourceSettings) {
                    restoreSourcePreferences(backup.backupSourcePreferences)
                }
                if (options.libraryEntries) {
                    // KMK -->
                    restoreManga(
                        backup.backupManga,
                        if (options.categories) backup.backupCategories else emptyList(),
                        restoredCategoryIds,
                    )
                    // KMK <--
                }
                if (options.extensionStores) {
                    restoreExtensionStores(backup.backupExtensionStores)
                }

                // KMK --> Inside the try, so a restore that threw does not count.
                runCatching {
                    mihon.app.di.globalAppGraph.achievementManager.tryUnlockDirect("backup_restored")
                }
                // KMK <--
            }

            // KMK --> After the scope, not inside it. The restore above is a `launch`, so
            // anything enqueued from inside the scope started while the restore was still
            // writing. A full library refresh reads and writes every entry on the same
            // single-writer database the restore is using, so for a large backup the two
            // spent their time contending rather than the restore finishing first.
            if (options.libraryEntries) {
                LibraryUpdateJob.startNow(context)
            }
            // KMK <--
        } finally {
            if (prevSuppress != null) achievementPrefs?.suppressOrganicForImport = prevSuppress
            // KMK --> credit imported library entries towards library/backlog achievements
            // (restore runs with organic increments suppressed, so library_X unlocks
            // would otherwise never fire for DB imports).
            try {
                if (options.libraryEntries) {
                    val graph = mihon.app.di.globalAppGraph
                    val count = try {
                        graph.mangaRepository.getLibraryManga().size.toLong()
                    } catch (_: Exception) {
                        -1L
                    }
                    if (count >= 0) graph.achievementManager.onLibraryCountChanged(count)
                    // KMK --> Restore rewrites every category link, so the stale-unstarted set
                    // changes wholesale. Forced because a restore must be exact immediately.
                    runCatching { graph.achievementManager.refreshBacklog(force = true) }
                    // KMK <--
                }
            } catch (_: Exception) {}
            // KMK <--
        }
    }

    context(scope: CoroutineScope)
    private /* KMK --> */suspend /* KMK <-- */ fun restoreCategories(backupCategories: List<BackupCategory>): Map<Long, Long> {
        scope.ensureActive()
        val restoredIdsByBackupId = categoriesRestorer(backupCategories)

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.categories),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
        return restoredIdsByBackupId
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(
        backupSavedSearches: List<BackupSavedSearch>,
        // KMK -->
        backupFeeds: List<BackupFeed>,
        // KMK <--
    ) = launch {
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)
        // KMK -->
        feedRestorer.restoreFeeds(backupFeeds)
        // KMK <--

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(KMR.strings.saved_searches_feeds),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
        // KMK -->
        restoredCategoryIds: Map<Long, Long> = emptyMap(),
        // KMK <--
    ) = launch {
        val semaphore = Semaphore(4)
        val mangaStart = SystemClock.elapsedRealtime()
        val sorted = mangaRestorer.sortByNew(backupMangas)
        mangaRestorer.prepare()

        sorted
            // Chunked so the edits [MangaRestorer] buffers are published per chunk
            // instead of per entry, and so the deferred list stays bounded. Building
            // one deferred per entry up front allocated the whole library's worth of
            // coroutines at once.
            .chunked(MANGA_CHUNK_SIZE)
            .forEach { chunk ->
                try {
                    chunk
                        .map {
                            async(AppDispatchersHolder.get().backgroundOps) {
                                semaphore.withPermit {
                                    ensureActive()

                                    try {
                                        mangaRestorer.restore(it, backupCategories, restoredCategoryIds)
                                    } catch (e: Exception) {
                                        val sourceName = sourceMapping[it.source] ?: it.source.toString()
                                        errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                                    }

                                    val progress = restoreProgress.incrementAndGet()
                                    reportProgress(it.title, progress)
                                }
                            }
                        }
                        .awaitAll()
                } finally {
                    // Also on cancellation: the edits written so far are already in the
                    // database transaction, so dropping them would leave the restored
                    // rows without the titles the backup carried.
                    mangaRestorer.flushPendingEdits()
                }
            }

        logcat(LogPriority.INFO) {
            "Restore wrote ${sorted.size} entries in ${SystemClock.elapsedRealtime() - mangaStart}ms"
        }
    }

    /**
     * Posts the progress notification, at most once per [PROGRESS_NOTIFICATION_INTERVAL_MS].
     *
     * A restore posts one notification per entry, so a library-sized backup used to
     * rebuild and repost the notification tens of thousands of times. Each post is a
     * binder call that SystemUI renders, which is more expensive than the entry's own
     * database work once the entries are small. Rate limiting keeps the bar moving
     * while bounding posts by elapsed time instead of by entry count.
     */
    private suspend fun reportProgress(title: String, progress: Int) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastProgressPost.get()
        val isLast = progress >= restoreAmount.get()
        if (!isLast) {
            if (now - previous < PROGRESS_NOTIFICATION_INTERVAL_MS) return
            if (!lastProgressPost.compareAndSet(previous, now)) return
        } else {
            lastProgressPost.set(now)
        }

        with(notifier) {
            showRestoreProgress(title, progress, restoreAmount.get(), isSync)
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.app_settings),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress.incrementAndGet()
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.source_settings),
                restoreProgress.get(),
                restoreAmount.get(),
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .forEach {
                ensureActive()

                try {
                    extensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error adding extension store: ${it.name} : ${e.message}")
                }

                restoreProgress.incrementAndGet()
                // KMK -->
                with(notifier) {
                    // KMK <--
                    showRestoreProgress(
                        context.stringResource(MR.strings.extensionStores),
                        restoreProgress.get(),
                        restoreAmount.get(),
                        isSync,
                    )
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("houri_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
