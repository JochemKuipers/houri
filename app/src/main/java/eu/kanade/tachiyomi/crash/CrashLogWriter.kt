package eu.kanade.tachiyomi.crash

import android.content.Context
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.logFileDir
import java.io.File

/**
 * Single-responsibility file writer for crash reports. Extracted from
 * `CrashLogUtil.dumpLogs()` which did `Runtime.exec("logcat ... -f file")`
 * inline (blocking, Android 13+ logcat permission fragile) and had no
 * rotation, size cap, or crash-loop guard.
 *
 * - Writes the structured [CrashReport] as human-readable text.
 * - Appends the [LogcatCapture] ring (gated on the user's log-level preference — errors only at
 *   the default MINIMAL tier, full detail once verbose logging is opted into), plus a bounded
 *   `logcat *:E` snapshot as a fallback when the ring is empty.
 * - Keeps the last 5 crash files (`houri_crash_*.log`) with 2MB cap each.
 * - Returns the written file so callers can share or launch CrashActivity with it.
 */
object CrashLogWriter {

    private const val MAX_FILES = 5
    private const val MAX_BYTES_PER_FILE = 2L * 1024 * 1024
    private const val FILE_PREFIX = "houri_crash_"
    private const val FILE_SUFFIX = ".log"

    /** Hard cap on the logcat section appended to a crash file. */
    private const val MAX_LOGCAT_BYTES = 600_000

    fun write(context: Context, report: CrashReport): File {
        val file = context.createFileInCacheDir("$FILE_PREFIX${report.timestampMs}$FILE_SUFFIX")
        val previousPointer = CrashDiagnostics.previousCrashPointer(context, report.timestampMs)
        file.writeText(render(report, previousPointer))
        if (file.length() > MAX_BYTES_PER_FILE) {
            val truncated = file.readText().take((MAX_BYTES_PER_FILE).toInt())
            file.writeText(truncated + "\n\n— truncated —\n")
        }
        pruneOld(context)
        appendLogcatSnapshot(context, file)
        return file
    }

    private fun render(r: CrashReport, previousPointer: String?): String = buildString {
        appendLine(r.debugInfo)
        appendLine()
        r.extensionsInfo?.let {
            appendLine(it)
            appendLine()
        }
        previousPointer?.let {
            appendLine("Previous crash report: $it")
            appendLine()
        }
        appendLine("Thread: ${r.threadName} @ ${r.timestampMs}")
        appendLine("Exception: ${r.type}: ${r.message ?: ""}")
        appendLine(r.stackTrace)
        r.cause?.let {
            appendLine("Caused by: ${it.type}: ${it.message ?: ""}")
            appendLine(it.stackTrace)
        }
        if (r.suppressedCount > 0) appendLine("Suppressed: ${r.suppressedCount}")
    }

    private fun crashFiles(context: Context): List<File> {
        val files = context.logFileDir().listFiles { f ->
            f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        }
        return files?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun pruneOld(context: Context) {
        try {
            crashFiles(context).drop(MAX_FILES).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun deleteAll(context: Context): Int = try {
        crashFiles(context).count { it.delete() }
    } catch (_: Exception) {
        0
    }

    /**
     * Find a previous crash report, newest first, excluding [excludeTimestampMs].
     * Returns a short pointer string (`<file> (<age>)`) or null.
     */
    internal fun previousReport(context: Context, excludeTimestampMs: Long): String? {
        return try {
            crashFiles(context)
                .firstOrNull { it.name != "$FILE_PREFIX$excludeTimestampMs$FILE_SUFFIX" }
                ?.let { "${it.name} (${humanAge(it.lastModified())})" }
        } catch (_: Exception) {
            null
        }
    }

    private fun humanAge(lastModified: Long): String {
        val seconds = (System.currentTimeMillis() - lastModified) / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    /**
     * Append a logcat section to [file]. Prefers the always-on [LogcatCapture] ring (full
     * priority range); falls back to a `logcat *:E -d` snapshot if the ring is empty. Both are
     * capped at [MAX_LOGCAT_BYTES] so a verbose session can't bloat the report.
     */
    private fun appendLogcatSnapshot(context: Context, file: File) {
        try {
            val ring = LogcatCapture.snapshot()
            if (ring.isNotEmpty()) {
                val text = ring.joinToString("\n")
                file.appendText("\n\n— logcat ring (last ${ring.size}) —\n")
                file.appendText(text.take(MAX_LOGCAT_BYTES))
                return
            }
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "*:E", "-d", "-t", "800", "-v", "brief"))
            val out = proc.inputStream.bufferedReader().readText().take(MAX_LOGCAT_BYTES)
            proc.waitFor()
            if (out.isNotBlank()) {
                file.appendText("\n\n— logcat (last 800 E) —\n$out")
            }
        } catch (_: Exception) {
        }
    }
}
