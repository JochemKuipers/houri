package mihon.core.migration.migrations

import eu.kanade.tachiyomi.network.NetworkPreferences
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Mullvad has discontinued its public DNS-over-HTTPS service. Users still configured
 * with the Mullvad provider (id 9, `PREF_DOH_MULLVAD` before its removal) would
 * silently lose DoH resolution, so reset them to the default provider.
 */
class RemoveMullvadDnsMigration : Migration {
    override val version: Float = 219f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val networkPreferences = migrationContext.get<NetworkPreferences>() ?: return@withIOContext false

        val dohProvider = networkPreferences.dohProvider()
        if (dohProvider.isSet() && dohProvider.get() == 9) {
            dohProvider.delete()
        }

        return@withIOContext true
    }
}
