package mtl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import mtl.context.ContextStore
import mtl.core.ProviderKind
import mtl.core.TranslationRequest
import mtl.core.TranslationResult
import mtl.sched.ChapterKey
import mtl.sched.MtlScheduler
import mtl.sched.PageOutcome
import mtl.sched.PagePriority
import mtl.sched.PageWork
import mtl.select.ProviderSelector
import mtl.status.StatusHub
import mtl.store.CacheIdentity
import mtl.store.PageStore

/** Per-manga translation toggles. App supplies the persistence; the spine reads values. */
interface PerMangaToggles {
    fun isEnabled(mangaId: Long): Boolean
    fun setEnabled(mangaId: Long, enabled: Boolean)
}

/** App-level privacy gates (incognito, censor). When gated, translation never runs. */
fun interface PrivacyGates {
    fun isGated(): Boolean
}

/** A translated series title/description, stamped with the identity that produced it. */
data class MangaInfoTranslation(
    val title: String,
    val description: String?,
    val identity: CacheIdentity,
    /**
     * Hash of the source text + languages this translation came from. A stored entry is
     * valid only while this matches the manga's current title/description — the metadata
     * equivalent of the page cache's content hash.
     */
    val sourceFingerprint: String = "",
)

/** Persistence for [MangaInfoTranslation]; the app supplies the disk implementation. */
interface MangaInfoStore {
    suspend fun get(mangaId: Long): MangaInfoTranslation?
    suspend fun put(mangaId: Long, translation: MangaInfoTranslation)
    fun clear(mangaId: Long)
}

/** The manga's declared language normalized the way the metadata path always normalized it. */
fun mangaInfoSourceLang(declaredLang: String?): String {
    val clean = declaredLang?.trim()?.take(20)?.takeIf { it.isNotBlank() } ?: return "JA"
    return clean.uppercase()
}

/**
 * The app-facing facade over the MTL spine: gates, per-manga toggles, page translation,
 * metadata translation, status, and lifecycle commands.
 *
 * Deliberately thin — gates and API shape live here, every decision lives in the selector,
 * pipeline, or scheduler. The old TranslationManager was 547 lines because this class's
 * predecessors kept absorbing pipeline internals; those now have their own homes.
 */
class MtlController(
    private val scheduler: MtlScheduler,
    private val selector: ProviderSelector,
    private val status: StatusHub,
    private val store: PageStore,
    private val contextStore: ContextStore,
    private val configSource: ConfigSource,
    private val toggles: PerMangaToggles,
    private val gates: PrivacyGates,
    private val infoStore: MangaInfoStore,
) {

    fun isEnabled(): Boolean = configSource.current().enabled

    /** The privacy gate alone (incognito / censor) — consumers that run regardless of the toggle still honor it. */
    fun isGated(): Boolean = gates.isGated()

    // ---- provider status (settings surfaces) ----

    /** A provider's current readiness, or null when no provider with [providerId] exists. */
    fun providerReadiness(providerId: String): mtl.core.Readiness? =
        selector.registry.byId(mtl.core.ProviderId(providerId))?.readiness?.value

    /** Re-probes a provider's expensive readiness check. No-op when the provider is unknown. */
    fun refreshProviderReadiness(providerId: String) {
        selector.registry.byId(mtl.core.ProviderId(providerId))?.refreshReadiness()
    }

    /** Whether this build registered any provider at all (no-MTL builds register none). */
    fun hasProviders(): Boolean = selector.registry.all.isNotEmpty()

    /** The one enablement gate: on globally, not gated, and on for this manga. Synchronous — the reader consults it on every page bind. */
    fun isEnabledForManga(mangaId: Long): Boolean =
        isEnabled() && !gates.isGated() && toggles.isEnabled(mangaId)

    fun setPerMangaEnabled(mangaId: Long, enabled: Boolean) = toggles.setEnabled(mangaId, enabled)

    fun isPerMangaEnabled(mangaId: Long): Boolean = toggles.isEnabled(mangaId)

    // ---- page translation ----

    /**
     * Translates one page; completes with the outcome (bytes on [PageOutcome.Done] /
     * [PageOutcome.Cached]). The pipeline's cache fast-path runs inside the job, so callers
     * submit unconditionally and a cache hit answers immediately.
     */
    fun translatePage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        sourceLangHint: String = "JA",
        priority: PagePriority = PagePriority.VISIBLE,
    ): Deferred<PageOutcome> {
        val config = configSource.current()
        if (!config.enabled || gates.isGated() || !toggles.isEnabled(mangaId)) {
            return CompletableDeferred(PageOutcome.Skipped("translation disabled"))
        }
        return scheduler.submit(
            PageWork(
                key = ChapterKey(mangaId, chapterId),
                pageIndex = pageIndex,
                imageBytes = imageBytes,
                sourceLangHint = sourceLangHint,
                priority = priority,
            ),
        )
    }

    /** Declares the page count up front so chapter progress is accurate while translating. */
    fun setChapterTotalPages(mangaId: Long, chapterId: Long, total: Int) =
        status.setTotalPages(mangaId, chapterId, total)

    fun cancelChapter(mangaId: Long, chapterId: Long) {
        scheduler.cancelChapter(ChapterKey(mangaId, chapterId))
        status.resetChapter(mangaId, chapterId)
    }

    fun pauseChapter(mangaId: Long, chapterId: Long) = scheduler.pauseChapter(ChapterKey(mangaId, chapterId))

    fun resumeChapter(mangaId: Long, chapterId: Long) = scheduler.resumeChapter(ChapterKey(mangaId, chapterId))

    /**
     * Clears retryable failures so the reader re-requests those pages on bind. Skips that
     * were provider-caused are retryable too; pages with genuinely no text are not.
     */
    fun retryChapter(mangaId: Long, chapterId: Long) {
        status.updateForRetry(mangaId, chapterId, status.retryablePages(mangaId, chapterId))
    }

    fun clearAll() {
        scheduler.cancelAll()
        status.clearAll()
    }

    // ---- metadata translation ----

    /**
     * Translates title + description with the active text provider. Independent of the
     * per-manga page toggle: only the global switch and privacy gates apply. Strict: a
     * missing or blank field is a failure, never padded with source text.
     */
    suspend fun translateMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLang: String = "JA",
    ): MangaInfoTranslation? {
        val config = configSource.current()
        if (!config.enabled || gates.isGated()) return null
        val lines = listOfNotNull(title.trim().takeIf { it.isNotEmpty() }, description?.trim()?.takeIf { it.isNotEmpty() })
        if (lines.isEmpty()) return null

        val requirement = mtl.select.CallRequirement(
            kind = ProviderKind.TEXT_LINES,
            sourceLang = sourceLang,
            targetLang = config.targetLang,
            needsContext = true,
        )
        val chain = selector.chain(requirement, ignoreReadiness = false)
        if (chain.isEmpty()) return null

        for (provider in chain) {
            val session = try {
                provider.open()
            } catch (_: Exception) {
                continue
            }
            val translated = try {
                session.translate(
                    TranslationRequest.TextLines(
                        sourceLang = sourceLang,
                        targetLang = config.targetLang,
                        context = mtl.core.TranslationContext(
                            mangaGrounding = contextStore.mangaGrounding(mangaId).orEmpty(),
                        ),
                        policy = config.promptPolicy,
                        lines = lines,
                    ),
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { session.close() }
            }
            val fields = (translated as? TranslationResult.Lines)?.translations
                ?.takeIf { it.size == lines.size && it.all(String::isNotBlank) }
                ?: continue

            val identity = CacheIdentity(
                providerId = provider.id.value,
                model = provider.modelId(),
                sourceLang = sourceLang,
                targetLang = config.targetLang,
                promptFingerprint = config.promptPolicy.fingerprint(),
            )
            val result = MangaInfoTranslation(
                title = fields[0],
                description = if (lines.size > 1) fields[1] else null,
                identity = identity,
                sourceFingerprint = mangaInfoFingerprint(sourceLang, title, description),
            )
            infoStore.put(mangaId, result)
            return result
        }
        return null
    }

    /** How ready the text-provider chain is to translate metadata. */
    enum class MangaInfoProviderState { READY, NOT_CONFIGURED, UNSUPPORTED }

    /**
     * Whether a text provider can serve metadata translation right now. The full-page image
     * service reports [MangaInfoProviderState.UNSUPPORTED] (it has no text endpoint); a chain
     * with no provider, or whose best provider is not ready, is
     * [MangaInfoProviderState.NOT_CONFIGURED].
     */
    fun mangaInfoProviderState(): MangaInfoProviderState {
        val config = configSource.current()
        val requirement = mtl.select.CallRequirement(
            kind = ProviderKind.TEXT_LINES,
            sourceLang = "JA",
            targetLang = config.targetLang,
            needsContext = true,
        )
        // ignoreReadiness answers "who would serve this", so the distinction between
        // "configured but not ready" and "nothing configured" survives.
        val primary = selector.chain(requirement, ignoreReadiness = true).firstOrNull()
            ?: return MangaInfoProviderState.NOT_CONFIGURED
        if (primary.id.value == "ichigo") return MangaInfoProviderState.UNSUPPORTED
        return if (primary.readiness.value is mtl.core.Readiness.Ready) {
            MangaInfoProviderState.READY
        } else {
            MangaInfoProviderState.NOT_CONFIGURED
        }
    }

    /**
     * Validated metadata cache read: returns the stored entry only when the source text,
     * languages, provider, model and prompt policy all still match — the same staleness
     * rules the old manager's validated read enforced.
     */
    suspend fun getValidCachedMangaInfo(
        mangaId: Long,
        title: String,
        description: String?,
        sourceLang: String = "JA",
    ): MangaInfoTranslation? {
        val config = configSource.current()
        val stored = infoStore.get(mangaId) ?: return null
        val requirement = mtl.select.CallRequirement(
            kind = ProviderKind.TEXT_LINES,
            sourceLang = sourceLang,
            targetLang = config.targetLang,
            needsContext = true,
        )
        // A cache entry answers offline, so identity uses who WOULD serve, not who can now.
        val primary = selector.chain(requirement, ignoreReadiness = true).firstOrNull()
        val identity = stored.identity
        val currentFingerprint = config.promptPolicy.fingerprint()
        return stored.takeIf {
            it.sourceFingerprint == mangaInfoFingerprint(sourceLang, title, description) &&
                identity.targetLang == config.targetLang &&
                identity.promptFingerprint == currentFingerprint &&
                primary != null &&
                identity.providerId == primary.id.value &&
                identity.model == primary.modelId()
        }
    }
}

/**
 * Stable fingerprint of the source metadata a translation was produced from — bundled into
 * [MangaInfoTranslation.sourceFingerprint] so a cached entry can be accepted only when the
 * page's title/description/source-language still hash to the same thing.
 */
fun mangaInfoFingerprint(sourceLang: String, title: String, description: String?): String {
    val raw = listOf(sourceLang.trim().uppercase(), title.trim(), description?.trim().orEmpty())
        .joinToString("\u0000")
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
