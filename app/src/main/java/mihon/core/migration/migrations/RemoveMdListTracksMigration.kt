package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.data.DatabaseHandler

/**
 * The MangaDex tracker (MDList, tracker id 60) has been removed along with its
 * source. Delete its `manga_track` rows; the id stays reserved in
 * [eu.kanade.tachiyomi.data.track.core.TrackerId] and is never reused.
 */
class RemoveMdListTracksMigration : Migration {
    override val version: Float = 219f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val handler = migrationContext.get<DatabaseHandler>() ?: return@withIOContext false

        handler.await { ehQueries.deleteBySyncId(60) }

        return@withIOContext true
    }
}
