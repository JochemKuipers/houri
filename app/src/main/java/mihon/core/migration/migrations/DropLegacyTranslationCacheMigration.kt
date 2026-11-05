package mihon.core.migration.migrations

import android.app.Application
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File

/**
 * Drops the pre-rewrite translation page caches — they can never be read again after the
 * ground-up MTL rewrite changed the on-disk layout (the saved-page store is now keyed and
 * hashed differently, so stale files would only waste storage).
 */
class DropLegacyTranslationCacheMigration : Migration {
    override val version: Float = 83f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val context = migrationContext.get<Application>() ?: return@withIOContext false
        val targets = listOf(
            File(context.filesDir, "yakuyomi_saved"),
            File(context.cacheDir, "yakuyomi"),
        )
        targets.forEach { target ->
            runCatching {
                if (target.exists()) target.deleteRecursively()
            }
        }
        true
    }
}
