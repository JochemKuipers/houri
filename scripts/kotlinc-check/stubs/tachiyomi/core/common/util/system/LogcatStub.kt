package tachiyomi.core.common.util.system

/**
 * Offline stub for check.sh: mirrors the mihon logcat helper's call surface
 * (`logcat { message }` and `logcat(priority) { message }`).
 */
private const val INFO: Int = 4
private const val WARN: Int = 5
private const val ERROR: Int = 6

fun logcat(priority: Int = INFO, vararg tags: String? = arrayOf(null), message: () -> String) { }

fun logcatW(message: () -> String) { }
