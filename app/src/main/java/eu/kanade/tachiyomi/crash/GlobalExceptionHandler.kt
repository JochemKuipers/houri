package eu.kanade.tachiyomi.crash

import android.content.Context
import android.content.Intent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<*>,
) : Thread.UncaughtExceptionHandler {

    object ThrowableSerializer : KSerializer<Throwable> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Throwable", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Throwable =
            Throwable(message = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Throwable) =
            encoder.encodeString(value.stackTraceToString())
    }

    /**
     * Crash-loop guard. The old code used a single `lastCrashMs` with a 3s window, which only
     * suppressed *adjacent* crashes; a crash in CrashActivity itself (or the :error_handler
     * process re-entering onCreate) could still re-crash without limit.
     *
     * We keep a small ring of recent crash timestamps and only launch the crash activity if we
     * haven't exceeded [MAX_CRASHES_PER_WINDOW] crashes inside [CRASH_WINDOW_MS]. Once the
     * threshold is crossed we stop launching the activity and let the default handler kill the
     * process — the user gets a normal "app keeps stopping" dialog instead of a crash-screen
     * loop.
     */
    private val recentCrashTimes = ArrayDeque<Long>(MAX_CRASHES_PER_WINDOW + 1)

    @Synchronized
    private fun shouldLaunchCrashActivity(now: Long): Boolean {
        recentCrashTimes.addLast(now)
        while (recentCrashTimes.size > MAX_CRASHES_PER_WINDOW) {
            recentCrashTimes.removeFirst()
        }
        if (recentCrashTimes.size < MAX_CRASHES_PER_WINDOW) {
            return true
        }
        val windowStart = recentCrashTimes.first()
        return now - windowStart > CRASH_WINDOW_MS
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        logcat(priority = LogPriority.ERROR, throwable = exception)
        val now = System.currentTimeMillis()
        if (shouldLaunchCrashActivity(now)) {
            try {
                val report = CrashReport.from(
                    throwable = exception,
                    thread = thread,
                    debugInfo = runCatching { CrashDiagnostics.debugInfo(applicationContext) }
                        .getOrDefault("debugInfo unavailable"),
                    extensionsInfo = runCatching { CrashDiagnostics.extensionsInfo() }
                        .getOrNull(),
                )
                val file = CrashLogWriter.write(applicationContext, report)
                launchActivityWithReport(applicationContext, activityToBeLaunched, exception, file.absolutePath)
            } catch (_: Exception) {
                launchActivity(applicationContext, activityToBeLaunched, exception)
            }
        }
        try {
            defaultHandler.uncaughtException(thread, exception)
        } catch (_: Exception) {
        }
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    private fun launchActivityWithReport(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
        reportPath: String,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            putExtra(INTENT_EXTRA_REPORT_PATH, reportPath)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"
        const val INTENT_EXTRA_REPORT_PATH = "crash_report_path"

        /** Max number of crashes tolerated inside [CRASH_WINDOW_MS] before we stop launching the
         * crash activity and let the process die. */
        private const val MAX_CRASHES_PER_WINDOW = 3
        private const val CRASH_WINDOW_MS = 10_000L

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing is GlobalExceptionHandler) return
            val fallback = existing ?: Thread.UncaughtExceptionHandler { _, _ -> }
            val handler = GlobalExceptionHandler(
                applicationContext,
                fallback,
                activityToBeLaunched,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? {
            return try {
                Json.decodeFromString(ThrowableSerializer, intent.getStringExtra(INTENT_EXTRA)!!)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Wasn't able to retrieve throwable from intent" }
                null
            }
        }

        fun getReportPathFromIntent(intent: Intent): String? = intent.getStringExtra(INTENT_EXTRA_REPORT_PATH)
    }
}
