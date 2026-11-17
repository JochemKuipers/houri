package eu.kanade.tachiyomi.crash

import android.content.Context
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.logFileDir
import exh.log.EHLogLevel
import logcat.LogPriority
import logcat.LogcatLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-private logcat ring buffer, gated on the user's log-level preference.
 *
 * This is the answer to "the app didn't crash, but something went wrong and the logcat buffer
 * has already rolled over". [LogcatLogger.log] is invoked for every line the app logs via
 * `logcat { }` / `xLog*`, and we keep the most recent lines in memory so the tail survives
 * process death even when nothing crashed.
 *
 * **Storage discipline:** the ring only captures at the level the user has opted into. At the
 * default `MINIMAL` tier warnings and errors are kept; raising the level (Settings → Advanced →
 * Log level) adds debug and verbose detail. Nothing here touches disk until a dump is actually
 * requested, so a normal user who never enables verbose logging writes no log files at all.
 *
 * **Memory discipline:** the ring is bounded twice over — by line count *and* by an approximate
 * byte budget — and both bounds shrink on low-RAM devices, because a 2 GB device cannot afford a
 * multi-megabyte log buffer competing with the reader's page cache. A line is charged as
 * `length * 2` bytes (Java `String` is UTF-16), which is the real in-memory cost rather than a
 * character count.
 *
 * Register it with [LogcatLogger.loggers] (in `App.onCreate`), after `LogcatLogger.install()`.
 */
object LogcatCapture : LogcatLogger {

    /** Ring capacity on a normal device: ~2k lines, hard-capped by [DEFAULT_MAX_BYTES]. */
    const val DEFAULT_CAPACITY = 2_000

    /** Ring capacity on a low-RAM device (< 3 GB total, which covers the 2 GB floor). */
    const val LOW_RAM_CAPACITY = 500

    private const val MAX_LINE_LENGTH = 4_000
    private const val LOW_RAM_MAX_LINE_LENGTH = 2_000

    /** Byte budget on a normal device. Typical lines are ~200 chars, so this is rarely reached. */
    private const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024

    /** Byte budget on a low-RAM device — a megabyte, not four. */
    private const val LOW_RAM_MAX_BYTES = 1L * 1024 * 1024

    /** Java `String` is UTF-16, so one character costs two bytes in memory. */
    private const val BYTES_PER_CHAR = 2L

    private val lock = Any()
    private val lines = ArrayDeque<String>(DEFAULT_CAPACITY)
    private var capacity = DEFAULT_CAPACITY
    private var maxLineLength = MAX_LINE_LENGTH
    private var maxBytes = DEFAULT_MAX_BYTES
    private var ringBytes = 0L

    @Volatile
    private var installed = false

    /**
     * Install this logger on the global [LogcatLogger] bus. Safe to call multiple times; only the
     * first call installs. Must be called after `LogcatLogger.install()` so that the `logcat { }`
     * calls actually reach the bus.
     *
     * [context] is used once, here, to size the ring for the device — no `Context` is retained.
     */
    fun install(context: Context) {
        synchronized(lock) {
            if (installed) {
                return
            }
            val lowRam = runCatching { DeviceUtil.isLowRamDevice(context) }.getOrDefault(false)
            this.capacity = if (lowRam) LOW_RAM_CAPACITY else DEFAULT_CAPACITY
            this.maxLineLength = if (lowRam) LOW_RAM_MAX_LINE_LENGTH else MAX_LINE_LENGTH
            this.maxBytes = if (lowRam) LOW_RAM_MAX_BYTES else DEFAULT_MAX_BYTES
            if (this !in LogcatLogger.loggers) {
                LogcatLogger.loggers += this
            }
            installed = true
        }
    }

    /**
     * Gate on the user's log-level preference: at `MINIMAL` warnings and errors are captured, at
     * `EXTRA` everything except verbose network logs, at `EXTREME` everything.
     */
    override fun isLoggable(priority: LogPriority, tag: String): Boolean = shouldCapture(priority)

    private fun shouldCapture(priority: LogPriority): Boolean {
        val level = EHLogLevel.currentLogLevel
        return when (level) {
            EHLogLevel.EXTREME -> true
            EHLogLevel.EXTRA -> priority != LogPriority.VERBOSE
            EHLogLevel.MINIMAL ->
                priority == LogPriority.WARN ||
                    priority == LogPriority.ERROR ||
                    priority == LogPriority.ASSERT
            else -> false
        }
    }

    override fun log(priority: LogPriority, tag: String, message: String) {
        val line = buildLine(priority, tag, message) ?: return
        synchronized(lock) {
            lines.addLast(line)
            ringBytes += line.length * BYTES_PER_CHAR
            // Evict oldest until both bounds hold. A single line is capped at maxLineLength, so
            // it can never exceed maxBytes on its own and this always terminates.
            while (lines.isNotEmpty() && (lines.size > capacity || ringBytes > maxBytes)) {
                ringBytes -= lines.removeFirst().length * BYTES_PER_CHAR
            }
        }
    }

    private fun buildLine(priority: LogPriority, tag: String?, message: String?): String? {
        val msg = message?.take(maxLineLength) ?: return null
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val p = priority.shortName
        val t = tag?.take(32) ?: "?"
        return "$time $p/$t: $msg"
    }

    private val LogPriority.shortName: String
        get() = when (this) {
            LogPriority.VERBOSE -> "V"
            LogPriority.DEBUG -> "D"
            LogPriority.INFO -> "I"
            LogPriority.WARN -> "W"
            LogPriority.ERROR -> "E"
            LogPriority.ASSERT -> "A"
        }

    /** Snapshot of the current ring, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** Number of lines currently held. */
    fun size(): Int = synchronized(lock) { lines.size }

    /**
     * Clear the ring. Does not touch logcat itself — call [clear] with `includeSystem = true`
     * to clear the system buffer too.
     */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            ringBytes = 0L
        }
    }

    /**
     * Clear the ring **and**, optionally, the system logcat buffer. Best-effort; the `-c` call
     * fails silently on devices where the app can't clear the buffer.
     */
    fun clear(includeSystem: Boolean) {
        clear()
        if (includeSystem) {
            try {
                Runtime.getRuntime().exec("logcat -c").waitFor()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Write the current ring to [file] as a plain-text log. Returns the file, or null on failure.
     * The caller is responsible for the file's lifecycle (share, retention, etc.).
     */
    fun writeTo(file: File): File? {
        val snapshot = snapshot()
        return try {
            file.writeText(
                buildString {
                    appendLine("— logcat ring (${snapshot.size} lines) —")
                    snapshot.forEach { appendLine(it) }
                },
            )
            file
        } catch (_: Exception) {
            null
        }
    }

    /** Write the ring to a fresh file in the app's log dir. */
    fun dumpToNewFile(context: Context, timestampMs: Long = System.currentTimeMillis()): File? {
        val file = File(context.logFileDir(), "houri_logcat_$timestampMs.log")
        return writeTo(file)
    }
}
