package mihon.core.migration.migrations

import eu.kanade.domain.source.service.SourcePreferences
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.model.ContentWarning
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Replaces the `show_nsfw_source` boolean with the `enabled_content_warnings` enum set.
 * Users who had NSFW sources hidden keep them hidden (only SAFE allowed); everyone else
 * gets the default (all warnings allowed).
 */
class ContentWarningMigration : Migration {
    override val version: Float = 219f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false
        val sourcePreferences = migrationContext.get<SourcePreferences>() ?: return@withIOContext false

        val showNsfwSource = preferenceStore.getBoolean("show_nsfw_source", true)
        if (!showNsfwSource.isSet()) return@withIOContext true

        if (!showNsfwSource.get()) {
            sourcePreferences.enabledContentWarnings().set(setOf(ContentWarning.SAFE))
        }
        showNsfwSource.delete()

        return@withIOContext true
    }
}
