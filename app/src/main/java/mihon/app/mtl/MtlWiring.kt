package mihon.app.mtl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import exh.yakuyomi.BreadcrumbNotes
import exh.yakuyomi.TranslateMangaStore
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.core.concurrency.AppDispatchersHolder
import mtl.ConfigSource
import mtl.MangaInfoStore
import mtl.MangaInfoTranslation
import mtl.MtlBootstrap
import mtl.MtlConfig
import mtl.MtlController
import mtl.PerMangaToggles
import mtl.PrivacyGates
import mtl.context.ContextStore
import mtl.core.PromptPolicy
import mtl.core.ProviderId
import mtl.provider.MtlComponents
import mtl.select.PolicySource
import mtl.select.SelectionPolicy
import mtl.store.DiskPageStore
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.File

// KMK --> App-side adapters for the MTL spine: thin, one-to-one mappings from the app's
// persisted prefs and services onto the spine's seam interfaces. No decisions live here —
// they live in the spine (ConfigSource/PolicySource/...). The providers and vision stages
// arrive via [mtl.provider.MtlComponents]: the mtl flavor's RealMtlComponents builds the real
// ones (see yakuyomi-engine), the no-MTL flavor supplies empty lists and the spine reports
// "not available" cleanly.

// ---------------------------------------------------------------------------
// Preference bridges — same keys the settings screens already write
// ---------------------------------------------------------------------------

/** Live source of [MtlConfig] values from the translation preferences. */
class PrefsConfigSource(
    private val prefs: TranslationPreferences,
) : ConfigSource {

    override fun current(): MtlConfig {
        val prompt = prefs.promptPolicy()
        return MtlConfig(
            enabled = prefs.enabled().get(),
            sourceLang = "JA", // the old pipeline never had a source-language preference
            targetLang = prefs.targetLang().get(),
            longPageSlicingEnabled = prefs.longPageSlicingEnabled().get(),
            offlineFallback = prefs.offlineFallback().get(),
            promptPolicy = PromptPolicy(
                customInstructions = prompt.customInstructions,
                formality = prompt.formality,
                sfxPolicy = prompt.sfxPolicy,
                glossary = prompt.glossary,
                fewShotSource = prompt.fewShotSource,
                fewShotTarget = prompt.fewShotTarget,
            ),
        )
    }
}

/**
 * Maps the old single "provider" preference onto the selector's manual pick + fallback
 * priority: image service → Gemini Nano → local llama.cpp → cloud.
 */
class PrefsPolicySource(
    private val prefs: TranslationPreferences,
) : PolicySource {

    override fun current(): SelectionPolicy {
        val pick = prefs.provider().get()
        val providerId = providerIdFor(pick)
        val priority = listOf(ICHIGO, NANO, LOCAL, CLOUD)
        return SelectionPolicy(
            mode = SelectionPolicy.Mode.MANUAL,
            manualPick = ProviderId(providerId),
            fallbacks = priority.filter { it != providerId }.map(::ProviderId),
        )
    }

    companion object {
        const val CLOUD = "cloud-llm"
        const val NANO = "gemini-nano"
        const val LOCAL = "local"
        const val ICHIGO = "ichigo"

        /** Old provider pref value → spine provider id. Cloud endpoints all map to one provider. */
        fun providerIdFor(oldProvider: String): String = when (oldProvider.lowercase()) {
            "local" -> LOCAL
            "gemini_nano" -> NANO
            "mangatranslator" -> ICHIGO
            else -> CLOUD
        }
    }
}

// ---------------------------------------------------------------------------
// Per-manga toggles and privacy gates
// ---------------------------------------------------------------------------

class PrefsPerMangaToggles(
    private val store: TranslateMangaStore,
) : PerMangaToggles {
    override fun isEnabled(mangaId: Long): Boolean = store.isEnabled(mangaId)
    override fun setEnabled(mangaId: Long, enabled: Boolean) = store.setEnabled(mangaId, enabled)
}

/** Privacy gates: the same incognito/censor prefs the old pipeline honoured. */
class PrefsPrivacyGates(
    preferenceStore: PreferenceStore,
) : PrivacyGates {

    private val incognito = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)
    private val censor = preferenceStore.getBoolean("pref_censor_lewd_manga", false)

    override fun isGated(): Boolean = incognito.get() || censor.get()
}

// ---------------------------------------------------------------------------
// Breadcrumbs from the note store the old pipeline maintained, grounding from the DB.
// ---------------------------------------------------------------------------

class BreadcrumbContextStore(
    private val notes: BreadcrumbNotes,
    private val grounding: suspend (Long) -> String?,
) : ContextStore {

    override suspend fun mangaGrounding(mangaId: Long): String? = grounding(mangaId)

    override suspend fun breadcrumbs(mangaId: Long, chapterId: Long, budgetChars: Int): String =
        notes.buildContextPrompt(mangaId, budgetChars)

    override suspend fun appendNotes(mangaId: Long, chapterId: Long, translatedLines: List<String>) {
        notes.appendFromTranslation(mangaId, chapterId, translatedLines)
    }
}

// ---------------------------------------------------------------------------
// Manga-info (title/description) translations — file-backed store.
// The engine's MangaInfoTranslation is a runtime object, not a Serializable DTO, so the
// disk shape is a private DTO and the store maps between them at the boundary.
// ---------------------------------------------------------------------------

@kotlinx.serialization.Serializable
private data class MangaInfoEntry(
    val title: String,
    val description: String? = null,
    val sourceFingerprint: String = "",
    val providerId: String,
    val model: String,
    val sourceLang: String,
    val targetLang: String,
    val promptFingerprint: String,
)

class FileInfoMangaStore(
    context: Context,
) : MangaInfoStore {

    private val dir = File(context.filesDir, "mtl_manga_info").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(mangaId: Long): MangaInfoTranslation? {
        val file = File(dir, "$mangaId.json")
        if (!file.exists()) return null
        return runCatching {
            val entry = json.decodeFromString<MangaInfoEntry>(file.readText())
            MangaInfoTranslation(
                title = entry.title,
                description = entry.description,
                sourceFingerprint = entry.sourceFingerprint,
                identity = mtl.store.CacheIdentity(
                    entry.providerId,
                    entry.model,
                    entry.sourceLang,
                    entry.targetLang,
                    entry.promptFingerprint,
                ),
            )
        }.getOrNull()
    }

    override suspend fun put(mangaId: Long, translation: MangaInfoTranslation) {
        if (mangaId <= 0) return
        runCatching {
            val file = File(dir, "$mangaId.json")
            val tmp = File(dir, "$mangaId.json.tmp")
            tmp.writeText(
                json.encodeToString(
                    MangaInfoEntry(
                        title = translation.title,
                        description = translation.description,
                        sourceFingerprint = translation.sourceFingerprint,
                        providerId = translation.identity.providerId,
                        model = translation.identity.model,
                        sourceLang = translation.identity.sourceLang,
                        targetLang = translation.identity.targetLang,
                        promptFingerprint = translation.identity.promptFingerprint,
                    ),
                ),
            )
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    override fun clear(mangaId: Long) {
        runCatching { File(dir, "$mangaId.json").delete() }
    }
}

// ---------------------------------------------------------------------------
// The Metro container
// ---------------------------------------------------------------------------

@BindingContainer
object MtlBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideMtlPageStore(
        context: Context,
        prefs: TranslationPreferences,
    ): DiskPageStore = DiskPageStore(
        baseDir = File(context.filesDir, "mtl_pages"),
        persistence = when {
            prefs.saveTranslatedPages().get() -> DiskPageStore.Persistence.AUTO
            prefs.autoSaveWhileReading().get() -> DiskPageStore.Persistence.WHILE_READING
            else -> DiskPageStore.Persistence.NEVER
        },
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideMtlMangaInfoStore(context: Context): MangaInfoStore = FileInfoMangaStore(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideMtlBootstrap(
        context: Context,
        prefs: TranslationPreferences,
        preferenceStore: PreferenceStore,
        perMangaStore: TranslateMangaStore,
        notes: BreadcrumbNotes,
        grounding: suspend (Long) -> String?,
        client: OkHttpClient,
        components: MtlComponents,
        pageStore: DiskPageStore,
    ): MtlBootstrap {
        val parentScope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().default)
        val codec = mtl.provider.AndroidCodec()
        return MtlBootstrap(
            parentScope = parentScope,
            configSource = PrefsConfigSource(prefs),
            policySource = PrefsPolicySource(prefs),
            toggles = PrefsPerMangaToggles(perMangaStore),
            gates = PrefsPrivacyGates(preferenceStore),
            infoStore = FileInfoMangaStore(context),
            contextStore = BreadcrumbContextStore(notes, grounding),
            pageStore = pageStore,
            codec = codec,
            providers = components.providers(codec, client),
            detectStages = components.detectStages(),
            ocrStages = components.ocrStages(),
            inpaintStages = components.inpaintStages(),
            renderStages = components.renderStages(),
            modelResolver = components.modelResolver(),
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideMtlController(bootstrap: MtlBootstrap): MtlController = bootstrap.controller

    @Provides
    @SingleIn(AppScope::class)
    fun provideMtlStatus(bootstrap: MtlBootstrap): mtl.status.StatusHub = bootstrap.status
}
// KMK <--
