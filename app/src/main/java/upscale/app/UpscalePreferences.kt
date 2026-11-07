package upscale.app

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Preference store for the upscaling stack. Keys are **byte-identical** to the pre-rewrite
 * stack so every existing opt-in survives the cutover; only NEW settings get new keys.
 *
 * The spine never reads this class — [PrefsUpscaleConfigSource] translates it into a
 * [upscale.policy.UpscaleConfig] snapshot. Enums here mirror the spine's
 * [upscale.core] value names; `parseStable`-style fallbacks keep unknown values harmless.
 */
/**
 * Preference store for the upscaling stack. Keys are **byte-identical** to the pre-rewrite
 * stack so every existing opt-in survives the cutover; only NEW settings get new keys.
 *
 * Provided only through [UpscaleBindings] (with its manga store), so this is not @Inject.
 *
 * The spine never reads this class — [PrefsUpscaleConfigSource] translates it into a
 * [upscale.policy.UpscaleConfig] snapshot.
 */
class UpscalePreferences(
    private val preferenceStore: PreferenceStore,
    val mangaStore: UpscaleMangaStore,
) {
    companion object {
        const val KEY_ENABLED = "pref_upscale_enabled"
        const val KEY_PRESET = "pref_upscale_preset"
        const val KEY_BACKEND = "pref_upscale_backend"
        const val KEY_MODEL = "pref_upscale_model"
        const val KEY_CACHE_ENABLED = "pref_upscale_cache_enabled"
        const val KEY_FACTOR = "pref_upscale_factor"
        const val KEY_SIMPLE_ALGO = "pref_upscale_simple_algo"
        const val KEY_MODE = "pref_upscale_mode"

        // KMK --> new in the spine rewrite
        const val KEY_DEFAULT_FOR_NEW = "pref_upscale_default_for_new"
        const val KEY_PREFETCH_DEPTH = "pref_upscale_prefetch_depth"
        const val KEY_TILE_REFINEMENT = "pref_upscale_tile_refinement"
        // KMK <--

        private const val KEY_LEGACY_PER_SERIES = "pref_upscale_per_series"
        private const val KEY_LEGACY_PER_SERIES_BACKUP = "pref_upscale_per_series_backup"
    }

    init {
        migrateLegacyPerSeries()
    }

    /**
     * Moves legacy per-series upscale opt-ins into per-manga prefs instead of dropping them.
     * Ported verbatim from the pre-rewrite prefs: the legacy value is a free-form string, each
     * token that parses to a positive id becomes an opt-in, and the raw value is stashed under a
     * backup key before the original is cleared so a future migration can retry anything skipped.
     */
    private fun migrateLegacyPerSeries() {
        try {
            val legacyPref = preferenceStore.getString(KEY_LEGACY_PER_SERIES, "")
            val legacy = legacyPref.get()
            if (legacy.isBlank()) return
            legacy.split(',', ';', '\n', ' ', '\t')
                .mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0 } }
                .toSet()
                .forEach { mangaId ->
                    try {
                        upscaleMangaStore.setEnabled(mangaId, true)
                    } catch (_: Exception) {}
                }
            try {
                preferenceStore.getString(KEY_LEGACY_PER_SERIES_BACKUP, "").set(legacy)
            } catch (_: Exception) {}
            legacyPref.set("")
        } catch (_: Exception) {}
    }

    fun enabled() = preferenceStore.getBoolean(KEY_ENABLED, false)
    fun preset() = preferenceStore.getString(KEY_PRESET, "BALANCED")
    fun backend() = preferenceStore.getString(KEY_BACKEND, "AUTO")
    fun model() = preferenceStore.getString(KEY_MODEL, "REAL_CUGAN")
    fun cacheEnabled() = preferenceStore.getBoolean(KEY_CACHE_ENABLED, true)
    fun upscaleFactor() = preferenceStore.getFloat(KEY_FACTOR, 2f)
    fun simpleAlgo() = preferenceStore.getString(KEY_SIMPLE_ALGO, "BICUBIC")
    fun mode() = preferenceStore.getString(KEY_MODE, "AUTO")

    // KMK --> new in the spine rewrite
    fun defaultForNewEntries() = preferenceStore.getBoolean(KEY_DEFAULT_FOR_NEW, false)
    fun prefetchDepth() = preferenceStore.getInt(KEY_PREFETCH_DEPTH, 2)
    fun tileRefinement() = preferenceStore.getBoolean(KEY_TILE_REFINEMENT, true)
    // KMK <--
}
