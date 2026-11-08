package upscale.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Per-manga and per-category upscaling opt-ins.
 *
 * Per-manga keys are **byte-identical** to the pre-rewrite stack (`upscale_manga_$id`, stored as
 * a Boolean) so every existing opt-in survives the cutover. Category overrides are new:
 * `upscale_category_$id` as a String — `""` (absent) = no opinion, `"true"`/`"false"` = override.
 * The enablement ladder lives in the spine's [upscale.UpscaleController]; this store only persists.
 *
 * Provided only through [UpscaleBindings], never constructed elsewhere.
 */
class UpscaleMangaStore(
    private val preferenceStore: PreferenceStore,
) {
    private fun pref(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("upscale_manga_$mangaId", false)

    private fun categoryPref(categoryId: Long): Preference<String> =
        preferenceStore.getString("upscale_category_$categoryId", "")

    /** Change notifications for the category override cache in [PrefsUpscaleConfigSource]. */
    private val _categoryOverridesVersion = MutableStateFlow(0)
    val categoryOverridesVersion: StateFlow<Int> = _categoryOverridesVersion.asStateFlow()

    // ---- per-manga ----

    fun isEnabled(mangaId: Long): Boolean = pref(mangaId).get()

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        pref(mangaId).set(enabled)
    }

    fun toggle(mangaId: Long): Boolean {
        val cur = isEnabled(mangaId)
        setEnabled(mangaId, !cur)
        return !cur
    }

    fun asFlow(mangaId: Long): Flow<Boolean> = pref(mangaId).changes()

    fun getPreference(mangaId: Long): Preference<Boolean> = pref(mangaId)

    fun clear(mangaId: Long) {
        try {
            pref(mangaId).delete()
        } catch (_: Exception) {}
    }

    // ---- per-category ----

    /** null = no opinion; true/false = the category's override. */
    fun categoryOverride(categoryId: Long): Boolean? = when (categoryPref(categoryId).get()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun setCategoryOverride(categoryId: Long, value: Boolean?) {
        val pref = categoryPref(categoryId)
        if (value == null) {
            pref.delete()
        } else {
            pref.set(value.toString())
        }
        _categoryOverridesVersion.value = _categoryOverridesVersion.value + 1
    }

    fun categoryOverrideFlow(categoryId: Long): Flow<Boolean?> = categoryPref(categoryId).changes()
        .map {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}
