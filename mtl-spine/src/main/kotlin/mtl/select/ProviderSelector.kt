package mtl.select

import mtl.core.MtlException
import mtl.core.MtlProvider
import mtl.core.ProviderId
import mtl.core.Readiness

/**
 * Supplies the current [SelectionPolicy]. The preferences bridge implements this; tests pass
 * a constant. Indirection keeps the selector pure.
 */
fun interface PolicySource {
    fun current(): SelectionPolicy
}

/**
 * The single answer to "who translates this call".
 *
 * The chain is ordered, filtered by capability and readiness, and every page reports which
 * entry served it — the old system's silent priority ladder (image service → Gemini Nano →
 * local GGUF → cloud, hardcoded, invisible, and re-derived in three places) is replaced by
 * one function whose output the user can see and edit.
 *
 * Ordering:
 * 1. The manual pick, when policy is MANUAL and it matches the requirement.
 * 2. The declared fallback chain, in order, matching entries only.
 * 3. Remaining ready, matching providers ranked by (costTier, latencyTier, registration order).
 */
class ProviderSelector(
    val registry: ProviderRegistry,
    private val policySource: PolicySource,
) {

    /**
     * Providers able to serve [requirement], best first. Empty means nothing can serve it —
     * the caller converts that to [MtlException.ProviderUnavailable].
     *
     * [ignoreReadiness] asks "who *would* serve this" rather than "who can *right now*" —
     * used by cache lookups, which must answer offline (a cached page is valid even when its
     * provider is unreachable), and by settings previews.
     */
    fun chain(requirement: CallRequirement, ignoreReadiness: Boolean = false): List<MtlProvider> {
        val policy = policySource.current()
        val matching = registry.all.filter { matches(it, requirement, ignoreReadiness) }

        val primary: List<MtlProvider> = when (policy.mode) {
            SelectionPolicy.Mode.MANUAL -> {
                val pick = policy.manualPick?.let(registry::byId)
                if (pick != null && matches(pick, requirement)) listOf(pick) else emptyList()
            }
            SelectionPolicy.Mode.AUTO -> emptyList()
        }

        val declared = policy.fallbacks.mapNotNull(registry::byId)
            .filter { it !in primary && matches(it, requirement, ignoreReadiness) }

        val ranked = if (policy.mode == SelectionPolicy.Mode.MANUAL) {
            // MANUAL means the user picked the chain: the pick plus declared fallbacks, never
            // an auto-ranked candidate behind their back — that was the silent-fallback bug.
            emptyList()
        } else {
            matching
                .filter { it !in primary && it !in declared }
                .sortedWith(
                    compareBy(
                        { it.capabilities.costTier },
                        { it.capabilities.latencyTier },
                        { registry.all.indexOf(it) },
                    ),
                )
        }

        return primary + declared + ranked
    }

    /**
     * The first provider that would serve [requirement] right now, or null. Used by the
     * settings UI and by cache-identity probes; the page pipeline always walks the full chain.
     */
    fun primary(requirement: CallRequirement): MtlProvider? = chain(requirement).firstOrNull()

    private fun matches(
        provider: MtlProvider,
        requirement: CallRequirement,
        ignoreReadiness: Boolean = false,
    ): Boolean {
        val caps = provider.capabilities
        if (caps.kind != requirement.kind) return false
        if (caps.sourceLangs.isNotEmpty() && requirement.sourceLang !in caps.sourceLangs) return false
        if (caps.targetLangs.isNotEmpty() && requirement.targetLang !in caps.targetLangs) return false
        if (requirement.needsContext && !caps.acceptsContext) return false
        if (ignoreReadiness) return true
        return when (provider.readiness.value) {
            is Readiness.Ready -> true
            is Readiness.Unavailable -> false
        }
    }

    /** Why the chain for [requirement] is empty, for user-facing messaging. */
    fun unavailableReason(requirement: CallRequirement): String {
        val policy = policySource.current()
        if (policy.mode == SelectionPolicy.Mode.MANUAL) {
            val pick = policy.manualPick
            val provider = pick?.let(registry::byId)
            if (pick == null || provider == null) return "Selected provider '$pick' is not installed"
            if (!matches(provider, requirement)) return "${provider.displayName} cannot handle this request"
            val r = provider.readiness.value
            if (r is Readiness.Unavailable) return r.userMessage
            return "${provider.displayName} is unavailable"
        }
        val anyKind = registry.all.filter { it.capabilities.kind == requirement.kind }
        if (anyKind.isEmpty()) return "No ${requirement.kind} provider is installed"
        val ready = anyKind.filter { it.readiness.value is Readiness.Ready }
        if (ready.isEmpty()) {
            return anyKind.joinToString { (it.readiness.value as? Readiness.Unavailable)?.userMessage ?: "${it.displayName} unavailable" }
        }
        return "No ready provider matches this request"
    }
}
