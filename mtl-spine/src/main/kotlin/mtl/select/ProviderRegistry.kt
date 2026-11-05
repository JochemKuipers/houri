package mtl.select

import mtl.core.MtlProvider
import mtl.core.ProviderId

/**
 * The set of providers registered with the spine.
 *
 * Wiring registers built-ins here; tests register fakes. Lookup is by id, iteration is in
 * registration order (which breaks ranking ties deterministically).
 */
class ProviderRegistry(providers: List<MtlProvider>) {

    private val byId = providers.associateBy { it.id }
    private val ordered = providers

    val all: List<MtlProvider> get() = ordered

    fun byId(id: ProviderId): MtlProvider? = byId[id]

    operator fun contains(id: ProviderId): Boolean = byId.containsKey(id)
}
