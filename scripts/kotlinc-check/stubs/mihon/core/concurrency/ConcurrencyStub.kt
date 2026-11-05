package mihon.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Offline stub for check.sh: the real holder is a Metro-injected singleton on Android.
 * `get()` is the DI accessor the code base calls directly.
 */
object AppDispatchersHolder {
    fun get(): AppDispatchers = AppDispatchers()
}

/** Offline stub for check.sh. */
class AppDispatchers {
    val io: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    val default: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    val main: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
}
