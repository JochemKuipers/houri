package upscale.app

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import upscale.UpscaleConfigSource
import upscale.core.BackendPref
import upscale.core.ModelFamily
import upscale.core.SimpleAlgo
import upscale.core.UpscaleMode
import upscale.policy.UpscaleConfig

/**
 * Translates the preference store into the spine's immutable [UpscaleConfig] snapshot.
 * Enum values parse with fallbacks, so an unknown persisted value degrades to the default
 * instead of crashing the reader.
 *
 * Category overrides need the manga's categories; that lookup is memoized per manga in a
 * small LRU, because this runs on the reader's per-page path. The cache holds
 * [CACHED_NO_OVERRIDE] as an explicit value so a manga with no opinion is not re-queried;
 * [UpscaleMangaStore.categoryOverridesVersion] bumps on any override change and clears it.
 */
@SingleIn(AppScope::class)
@Inject
class PrefsUpscaleConfigSource(
    private val preferences: UpscalePreferences,
    private val categoryRepository: CategoryRepository,
) : UpscaleConfigSource {

    private val store: UpscaleMangaStore get() = preferences.mangaStore

    init {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            store.categoryOverridesVersion.collect {
                synchronized(categoryCache) { categoryCache.clear() }
            }
        }
    }

    // ---- config snapshot ----

    override fun current(): UpscaleConfig {
        val prefs = preferences
        return UpscaleConfig(
            enabled = prefs.enabled().get(),
            mode = parseMode(prefs.mode().get()),
            preset = parseEnum("preset", prefs.preset().get(), upscale.core.UpscalePreset.BALANCED),
            backendPref = parseEnum("backend", prefs.backend().get(), BackendPref.AUTO),
            modelFamily = parseEnum("model", prefs.model().get(), ModelFamily.REAL_CUGAN),
            factor = prefs.upscaleFactor().get(),
            simpleAlgo = parseEnum("simple algo", prefs.simpleAlgo().get(), SimpleAlgo.BICUBIC),
            cacheEnabled = prefs.cacheEnabled().get(),
            prefetchDepth = prefs.prefetchDepth().get().coerceIn(0, 5),
            tileRefinement = prefs.tileRefinement().get(),
            defaultForNewEntries = prefs.defaultForNewEntries().get(),
            perMangaEnabled = { mangaId -> store.isEnabled(mangaId) },
        )
    }

    /**
     * The category leg of the enablement ladder. Any opted-in category wins; otherwise an
     * explicit opt-out wins; otherwise null (the spine falls through to the new-entry default).
     */
    override suspend fun overrideForManga(mangaId: Long): Boolean? {
        if (mangaId <= 0) return null
        synchronized(categoryCache) { categoryCache[mangaId] }?.let { cached ->
            return (cached as? Boolean)
        }
        return try {
            val categories = categoryRepository.getCategoriesByMangaId(mangaId)
            var explicitFalse = false
            var result: Boolean? = null
            for (category in categories) {
                when (store.categoryOverride(category.id)) {
                    true -> {
                        result = true
                        break
                    }
                    false -> explicitFalse = true
                    null -> {}
                }
            }
            val answer = result ?: if (explicitFalse) false else null
            synchronized(categoryCache) {
                if (categoryCache.size >= CACHE_MAX) categoryCache.clear()
                categoryCache[mangaId] = answer ?: CACHED_NO_OVERRIDE
            }
            answer
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Upscale category lookup failed for $mangaId: ${e.message}" }
            null
        }
    }

    private val categoryCache = object : LinkedHashMap<Long, Any>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean = size > CACHE_MAX
    }

    private fun parseMode(raw: String): UpscaleMode = when (raw) {
        "NATIVE" -> UpscaleMode.NATIVE
        "SIMPLE" -> UpscaleMode.SIMPLE
        "AUTO" -> UpscaleMode.AUTO
        else -> UpscaleMode.AUTO
    }

    private inline fun <reified T : Enum<T>> parseEnum(what: String, raw: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default.also {
            if (raw.isNotEmpty()) {
                logcat(LogPriority.WARN) { "Unknown upscale $what '$raw'; using ${it.name}" }
            }
        }

    companion object {
        private const val CACHE_MAX = 128
        private val CACHED_NO_OVERRIDE = Any()
    }
}
