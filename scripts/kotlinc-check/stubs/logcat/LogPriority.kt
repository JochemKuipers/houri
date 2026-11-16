package logcat

/**
 * Offline stub for the logcat facade's priority enum.
 *
 * `LogcatStub.kt` already mirrors the `logcat(...)` call surface but references this type without
 * defining it, because `check.sh` tolerates unresolved references. A harness that *runs* cannot, so
 * the enum lives here. The real one is `com.squareup.logcat.LogPriority` behind the `logcat`
 * facade; the constants only ever reach a stub that ignores them.
 *
 * Never compiled into the app.
 */
enum class LogPriority {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ASSERT,
}