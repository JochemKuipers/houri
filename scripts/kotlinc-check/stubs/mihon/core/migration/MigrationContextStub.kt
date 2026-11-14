package mihon.core.migration

/** Offline stub for check.sh: MigrationContext's reified lookup. Never compiled into the app. */
class MigrationContext(val dryrun: Boolean) {
    inline fun <reified T> get(): T? = null
}

/** Offline stub for check.sh. Never compiled into the app. */
interface Migration {
    val version: Float
    suspend fun invoke(migrationContext: MigrationContext): Boolean
}
