package tachiyomi.data.updates

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.BookmarkColor
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class UpdatesRepositoryImpl(
    private val databaseHandler: DatabaseHandler,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<UpdatesWithRelations> {
        return databaseHandler.awaitList {
            getUpdatesByReadStatus(read, after, limit)
        }
    }

    override fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
    ): Flow<List<UpdatesWithRelations>> {
        return databaseHandler.subscribeToList {
            updatesViewQueries.getRecentUpdatesWithFilters(
                after = after,
                limit = limit,
                // invert because unread in Kotlin -> read column in SQL
                read = unread?.let { !it },
                started = started?.toLong(),
                bookmarked = bookmarked,
                hideExcludedScanlators = hideExcludedScanlators.toLong(),
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<UpdatesWithRelations>> {
        return databaseHandler.subscribeToList {
            getUpdatesByReadStatus(read, after, limit)
        }
    }

    // KMK: Mihon's #4022 split - SQLite only uses a partial index when the query's own
    // condition matches it, which a bound parameter never does, so the read and unread
    // lookups are separate queries with the status written into each: the unread one is
    // served by the partial index over unread chapters instead of walking the whole
    // fetch-date index.
    private fun tachiyomi.data.Database.getUpdatesByReadStatus(
        read: Boolean,
        after: Long,
        limit: Long,
    ) = if (read) {
        updatesViewQueries.getReadUpdates(
            after = after,
            limit = limit,
            mapper = ::mapUpdatesWithRelations,
        )
    } else {
        updatesViewQueries.getUnreadUpdates(
            after = after,
            limit = limit,
            mapper = ::mapUpdatesWithRelations,
        )
    }

    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        // KMK --> Positional in the SELECT list, so it sits between `bookmark` and
        // `lastPageRead` exactly as updatesView orders it.
        bookmarkColor: Long,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        @Suppress("UNUSED_PARAMETER") dateUpload: Long,
        dateFetch: Long,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        // SY -->
        ogMangaTitle = mangaTitle,
        // SY <--
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        // KMK -->
        bookmarkColor = BookmarkColor.fromValue(bookmarkColor),
        // KMK <--
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            ogUrl = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
