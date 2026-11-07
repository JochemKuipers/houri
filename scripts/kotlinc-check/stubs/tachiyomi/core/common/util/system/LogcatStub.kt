package tachiyomi.core.common.util.system

/**
 * Offline stub for check.sh: mirrors the mihon logcat helper's call surface
 * (`logcat { message }`, `logcat(priority) { message }`, and
 * `logcat(priority, throwable) { message }`). The real helper is an Any extension delegating to
 * com.squareup.logcat, whose JVM jar is not in the Gradle cache. Never compiled into the app.
 */
private const val INFO: Int = 4

fun logcat(priority: Int = INFO, vararg tags: String? = arrayOf(null), message: () -> String) { }

fun logcat(
    priority: logcat.LogPriority,
    throwable: Throwable? = null,
    tag: String? = null,
    message: () -> String,
) { }

fun logcatW(message: () -> String) { }
