package eu.kanade.domain.sync.models

data class SyncSettings(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val tracking: Boolean = true,
    val history: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,

    // SY -->
    val customInfo: Boolean = true,
    val readEntries: Boolean = true,
    val savedSearchesFeeds: Boolean = true,
    // SY <--
    // KMK -->
    /**
     * Include each entry's custom cover. Off by default: the covers are image bytes, and a sync
     * carrying them pays for them on every sync rather than once like a backup
     * (see `BackupOptions.customCovers`).
     */
    val customCovers: Boolean = false,
    // KMK <--
)
