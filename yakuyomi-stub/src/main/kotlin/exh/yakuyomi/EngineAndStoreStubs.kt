package exh.yakuyomi

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** No-op stub of [YakuyomiEngine] for the no-MTL APK variant. */
@SingleIn(AppScope::class)
@Inject
class YakuyomiEngine {

    val notEnoughMemoryReason: String
        get() = "AI translation is not available in this build"

    fun isHardwareSupported(): Boolean = false

    fun prewarm(): Boolean = false

    fun bitmapToWebP(bitmap: android.graphics.Bitmap, quality: Int = 85): ByteArray = ByteArray(0)
}

/** Functional store for the no-MTL variant: per-manga translate toggle still works for off-device services. */
@SingleIn(AppScope::class)
@Inject
class TranslateMangaStore(
    private val preferenceStore: PreferenceStore,
) {
    private fun pref(mangaId: Long): Preference<Boolean> =
        preferenceStore.getBoolean("yakuyomi_translate_manga_$mangaId", false)

    fun isEnabled(mangaId: Long): Boolean = pref(mangaId).get()

    fun setEnabled(mangaId: Long, enabled: Boolean) {
        pref(mangaId).set(enabled)
    }

    fun toggle(mangaId: Long): Boolean {
        val next = !isEnabled(mangaId)
        setEnabled(mangaId, next)
        return next
    }

    fun asFlow(mangaId: Long): Flow<Boolean> = pref(mangaId).changes()

    fun getPreference(mangaId: Long): Preference<Boolean> = pref(mangaId)

    fun clear(mangaId: Long) {
        runCatching { pref(mangaId).delete() }
    }
}

/** Functional breadcrumb notes for the no-MTL variant: keeps off-device context. */
@SingleIn(AppScope::class)
@Inject
class BreadcrumbNotes(
    private val context: android.content.Context,
) {
    private fun file(mangaId: Long): java.io.File = java.io.File(context.filesDir, "yakuyomi_notes/$mangaId.json").apply { parentFile?.mkdirs() }
    fun buildContextPrompt(mangaId: Long, budget: Int = 1000): String {
        return try {
            file(mangaId).takeIf { it.exists() }?.readText()?.take(budget) ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    fun buildContextPrompt(mangaId: Long): String = buildContextPrompt(mangaId, 1000)
    fun appendFromTranslation(mangaId: Long, chapterId: Long, texts: List<String>) {
        try {
            val f = file(mangaId)
            val existing = f.takeIf { it.exists() }?.readText() ?: ""
            val appended = (existing + "\n" + texts.joinToString("\n")).take(8000)
            f.writeText(appended)
        } catch (_: Exception) {}
    }
}
