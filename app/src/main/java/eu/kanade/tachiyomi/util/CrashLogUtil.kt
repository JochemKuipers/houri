package eu.kanade.tachiyomi.util

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext

/**
 * Rich, one-tap log dump + share.
 *
 * [dumpLogs] captures everything a field report needs in a single file:
 *  - the debug-info header (device/build state, memory, storage, battery, WebView, locale)
 *  - extension health (problematic/outdated extensions)
 *  - the always-on [eu.kanade.tachiyomi.crash.LogcatCapture] ring (every level, not just ERROR)
 *  - a pointer to the previous crash report, if any
 *
 * This is the single entry point behind the crash screen's "Share crash logs" and the Advanced
 * settings "Share crash logs" row — no uncaught exception required.
 */
@Inject
@SingleIn(AppScope::class)
class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager = globalAppGraph.extensionManager,
) {

    /**
     * Build a full dump file (debug info + extensions + logcat ring + previous crash) and open the
     * share sheet on it. If [exception] is non-null it is included as the crash body.
     */
    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val timestamp = System.currentTimeMillis()
            // Build the full dump off the main thread (disk I/O + logcat exec).
            val file = withIOContext {
                val out = context.createFileInCacheDir("houri_dump_$timestamp.log")
                val report = eu.kanade.tachiyomi.crash.CrashReport.from(
                    throwable = exception ?: Throwable("Manual dump"),
                    thread = Thread.currentThread(),
                    debugInfo = getDebugInfo(),
                    extensionsInfo = getExtensionsInfo(),
                )
                val previous = eu.kanade.tachiyomi.crash.CrashDiagnostics
                    .previousCrashPointer(context, timestamp)

                out.writeText(
                    buildString {
                        appendLine("=== HOURI LOG DUMP ===")
                        appendLine("Timestamp: ${java.time.Instant.ofEpochMilli(timestamp)}")
                        appendLine("Process: ${android.os.Process.myPid()}")
                        appendLine()
                        appendLine(report.debugInfo)
                        appendLine()
                        report.extensionsInfo?.let {
                            appendLine(it)
                            appendLine()
                        }
                        previous?.let {
                            appendLine("Previous crash report: $it")
                            appendLine()
                        }
                        if (exception != null) {
                            appendLine("Thread: ${report.threadName}")
                            appendLine("Exception: ${report.type}: ${report.message ?: ""}")
                            appendLine(report.stackTrace)
                            appendLine()
                        }
                        val ring = eu.kanade.tachiyomi.crash.LogcatCapture.snapshot()
                        appendLine("=== LOG CAT (last ${minOf(ring.size, 1000)} lines of ring) ===")
                        ring.takeLast(1000).forEach { appendLine(it) }
                    },
                )
                out
            }
            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (e: Throwable) {
            withUIContext { context.toast("Failed to get logs: ${e.message}") }
        }
    }

    /** The debug-info header. Delegates to the cached [eu.kanade.tachiyomi.crash.CrashDiagnostics]. */
    fun getDebugInfo(): String = eu.kanade.tachiyomi.crash.CrashDiagnostics.debugInfo(context)

    /** The extension-health section, or null. Delegates to the cached diagnostics. */
    internal fun getExtensionsInfo(): String? = eu.kanade.tachiyomi.crash.CrashDiagnostics.extensionsInfo()

    /**
     * Delete every crash/dump file, clear the always-on logcat ring, and clear the system logcat
     * buffer. Returns a human-readable summary for the UI.
     */
    fun clearLogs(): String {
        return try {
            val deleted = eu.kanade.tachiyomi.crash.CrashLogWriter.deleteAll(context)
            val ringLines = eu.kanade.tachiyomi.crash.LogcatCapture.size()
            eu.kanade.tachiyomi.crash.LogcatCapture.clear(includeSystem = true)
            "Cleared $deleted crash/dump files and $ringLines ring lines"
        } catch (e: Exception) {
            "Failed to clear logs: ${e.message}"
        }
    }
}
