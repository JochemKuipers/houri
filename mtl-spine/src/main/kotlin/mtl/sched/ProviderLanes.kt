package mtl.sched

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mtl.core.MtlProvider

/**
 * Per-provider concurrency lanes.
 *
 * [mtl.core.ProviderCapabilities.maxConcurrency] is the provider's own statement of how many
 * requests it accepts in flight; this class holds one semaphore per provider id and the page
 * pipeline acquires before every attempt. Local runtimes (max 1) serialize here — the old
 * design achieved the same with a single global native mutex, which also blocked cloud pages
 * that never touch native code.
 *
 * Lanes are created once per provider id and never resized: a provider that changes its
 * concurrency contract is a different provider configuration, and its id should change with it.
 */
class ProviderLanes {

    private val lanes = HashMap<String, Semaphore>()

    private fun laneFor(provider: MtlProvider): Semaphore = synchronized(lanes) {
        lanes.getOrPut(provider.id.value) {
            Semaphore(provider.capabilities.maxConcurrency.coerceAtLeast(1))
        }
    }

    /** Runs [block] holding the provider's lane. Cancellation releases the permit. */
    suspend fun <T> withLane(provider: MtlProvider, block: suspend () -> T): T =
        laneFor(provider).withPermit { block() }
}
