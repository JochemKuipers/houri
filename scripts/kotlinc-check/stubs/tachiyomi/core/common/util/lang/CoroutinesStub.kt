package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Harness-only stand-in for core:common's IO dispatcher helper.
 *
 * The real file (`CoroutinesExtensions.kt`) also owns UI-thread helpers that pull in Android, so
 * check.sh never compiles it. Without this declaration every caller of `withIOContext` reported
 * "suspension functions can only be called within coroutine body" — the compiler could not tell an
 * unresolved function from a non-suspending one — which buried the real errors in files that wrap
 * their IO in it. The body is never executed by the checker; it only has to have the right shape.
 */
suspend fun <T> withIOContext(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Unconfined, block)
