package mtl.select

import mtl.core.ProviderId
import mtl.core.ProviderKind

/**
 * What a call needs, in terms the selector can match capabilities against.
 */
data class CallRequirement(
    val kind: ProviderKind,
    val sourceLang: String,
    val targetLang: String,
    /** The caller has breadcrumb/glossary context and wants it honored. */
    val needsContext: Boolean,
)

/**
 * How providers are chosen.
 *
 * [fallbacks] is the user-visible chain: it is presented in settings, logged with every page,
 * and walked in order — nothing falls back silently.
 */
data class SelectionPolicy(
    val mode: Mode = Mode.AUTO,
    /** Provider preferred in [Mode.MANUAL]. */
    val manualPick: ProviderId? = null,
    /** Ordered providers tried after the primary fails or is unavailable. */
    val fallbacks: List<ProviderId> = emptyList(),
) {
    enum class Mode { AUTO, MANUAL }

    companion object {
        val DEFAULT = SelectionPolicy()
    }
}
