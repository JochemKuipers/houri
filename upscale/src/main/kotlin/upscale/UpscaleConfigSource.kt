package upscale

import upscale.policy.UpscaleConfig

/**
 * The app's live view of the persisted configuration. The spine never reads a pref store; the
 * app rebuilds a snapshot whenever it needs one. [overrideForManga] is the suspend leg of the
 * enablement ladder (per-manga store first, then category overrides, then the default for new
 * entries) and is allowed to hit the database — the controller caches nothing, so implementers
 * should keep it cheap and memoize internally.
 */
interface UpscaleConfigSource {
    fun current(): UpscaleConfig

    /**
     * The enablement override for [mangaId] from categories, or null when no category carries an
     * opinion (the per-manga stored value is NOT read here — the spine consults
     * [UpscaleConfig.perMangaEnabled] first).
     */
    suspend fun overrideForManga(mangaId: Long): Boolean?
}
