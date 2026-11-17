// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import exh.log.xLogE
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// A per-session recorder for what the reader actually did, so a field report can carry numbers
// instead of an adjective.
//
// The problem this solves is not "we have no logs" - the folder has an extensive trace, a stall
// watchdog and a symptom table. It is that all of it goes to logcat, and the person who sees the
// bug is holding a phone with no logcat. `WebGpuHealth.recordStrike` already learned this: the one
// line that reaches a phone-only user is the one written to the in-app log. This is the same
// lesson applied to a whole session rather than to one event.
//
// So: bounded counters and gauges, aggregated as the session runs, rendered as a few lines at the
// end. No per-page records (that is what the trace is for), no unbounded growth, and nothing here
// can alter the reader's behaviour - the same contract DecodeTrace has, for the same reason.

/**
 * How many samples each bounded ring keeps.
 *
 * Small on purpose: the rings exist to answer "was it one page or every page", and a handful of
 * recent keys answers that. A ring long enough to hold a whole chapter would be a second copy of
 * the page cache.
 */
private const val MAX_RECENT_KEYS = 12

/** How many distinct budget descriptions to keep, so a thrashing monitor is visible. */
private const val MAX_BUDGET_SAMPLES = 6

/**
 * Whether a session should be written to the in-app log when it ends.
 *
 * A clean session says nothing: the reader is not a diagnostic tool, and a healthy session writing
 * a block every time would bury the in-app log it is trying to be useful in.
 */
internal object WebGpuDiagnostics {

    private const val NOT_STARTED = "no session"

    @Volatile
    private var header: String = NOT_STARTED

    @Volatile
    private var startedAtMs: Long = 0L

    /** Decodes by terminal outcome - the same vocabulary the stuck ladder logs. */
    private val decodeOutcomes = LinkedHashMap<String, Int>()

    @Volatile
    private var decodeCount: Int = 0

    @Volatile
    private var decodeTotalMs: Long = 0L

    @Volatile
    private var decodeMaxMs: Long = 0L

    @Volatile
    private var decodeMaxLabel: String = "none"

    @Volatile
    private var downsampledCount: Int = 0

    /**
     * Decode stalls the watchdog reported - the single most useful number in a field report.
     *
     * Counted rather than logged: the watchdog already writes each one to logcat, which is exactly
     * what a phone-only user cannot see. A session that stalled once is a slow network; a session
     * that stalled twelve times is a device or a source worth chasing.
     */
    @Volatile
    private var stallCount: Int = 0

    @Volatile
    private var evictions: Int = 0

    /** Page keys evicted most recently, so a repeated eviction of one page is visible. */
    private val recentEvictions = ArrayDeque<String>()

    @Volatile
    private var deviceLosses: Int = 0

    @Volatile
    private var recoveries: Int = 0

    @Volatile
    private var failedRecoveries: Int = 0

    @Volatile
    private var parks: Int = 0

    private val ladder = LinkedHashMap<String, Int>()

    private val budgets = ArrayDeque<String>()

    @Volatile
    private var lastBudget: String = "none"

    /**
     * Opens a session. The header is built by the caller because only the viewer knows the tier,
     * the preload window and the renderer's own flags - this object deliberately knows nothing
     * about any of them.
     */
    fun beginSession(sessionHeader: String) {
        synchronized(this) {
            header = sessionHeader
            startedAtMs = System.currentTimeMillis()
            decodeOutcomes.clear()
            decodeCount = 0
            decodeTotalMs = 0L
            decodeMaxMs = 0L
            decodeMaxLabel = "none"
            downsampledCount = 0
            stallCount = 0
            evictions = 0
            recentEvictions.clear()
            deviceLosses = 0
            recoveries = 0
            failedRecoveries = 0
            parks = 0
            ladder.clear()
            budgets.clear()
            lastBudget = "none"
        }
    }

    /**
     * How many pages this session finished decoding.
     *
     * Read by the viewer's close-out to separate "the reader never got that far" (a chapter
     * closed before anything resolved - nothing to say) from "pages decoded and the display still
     * showed nothing" (the black-screen shape, which deserves a health strike).
     */
    fun decodedPages(): Int = decodeCount

    /** How long the session has been open, 0 before [beginSession]. */
    fun sessionDurationMs(): Long =
        if (startedAtMs == 0L) 0L else (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)

    /** One finished decode: how it ended, how long it took, and whether it had to be downsampled. */
    fun recordDecode(outcome: String, totalMs: Long, label: String, downsampled: Boolean) {
        synchronized(this) {
            decodeOutcomes[outcome] = (decodeOutcomes[outcome] ?: 0) + 1
            decodeCount++
            decodeTotalMs += totalMs.coerceAtLeast(0L)
            if (totalMs > decodeMaxMs) {
                decodeMaxMs = totalMs
                decodeMaxLabel = label
            }
            if (downsampled) downsampledCount++
        }
    }

    /** One decode the watchdog reported as sitting in a single stage past its deadline. */
    fun recordStall() {
        synchronized(this) { stallCount++ }
    }

    /** One page dropped from the cache. [key] is the page key, so a repeat is spottable. */
    fun recordEviction(key: String) {
        synchronized(this) {
            evictions++
            recentEvictions.addLast(key)
            while (recentEvictions.size > MAX_RECENT_KEYS) recentEvictions.removeFirst()
        }
    }

    /** One rung of the stuck ladder, as `stuckLadderActionFor` named it. */
    fun recordLadder(action: String) {
        synchronized(this) {
            ladder[action] = (ladder[action] ?: 0) + 1
        }
    }

    fun recordDeviceLoss(reason: String) {
        synchronized(this) { deviceLosses++ }
        logcat(LogPriority.WARN) { "WebGpu diagnostics: device loss ($reason)" }
    }

    fun recordRecovery(ok: Boolean) {
        synchronized(this) {
            if (ok) recoveries++ else failedRecoveries++
        }
    }

    fun recordPark(count: Int = 1) {
        synchronized(this) { parks += count.coerceAtLeast(0) }
    }

    /** One published memory budget, kept only when it differs from the last. */
    fun recordBudget(description: String) {
        synchronized(this) {
            if (description == lastBudget) return
            lastBudget = description
            budgets.addLast(description)
            while (budgets.size > MAX_BUDGET_SAMPLES) budgets.removeFirst()
        }
    }

    /** How many times one key was evicted inside the recent window - the churn signal. */
    private fun churnCount(key: String): Int = recentEvictions.count { it == key }

    private fun worstChurn(): Int = recentEvictions.groupingBy { it }.eachCount().values.maxOrNull() ?: 0

    /** The whole session as a few lines. Never throws; a broken recorder must not break a dump. */
    fun render(): String = runCatching {
        val snapshot = synchronized(this) {
            Snapshot(
                header = header,
                startedAtMs = startedAtMs,
                decodeOutcomes = decodeOutcomes.toMap(),
                decodeCount = decodeCount,
                decodeTotalMs = decodeTotalMs,
                decodeMaxMs = decodeMaxMs,
                decodeMaxLabel = decodeMaxLabel,
                downsampled = downsampledCount,
                stalls = stallCount,
                evictions = evictions,
                worstChurn = worstChurn(),
                deviceLosses = deviceLosses,
                recoveries = recoveries,
                failedRecoveries = failedRecoveries,
                parks = parks,
                ladder = ladder.toMap(),
                budgets = budgets.toList(),
                lastBudget = lastBudget,
            )
        }
        buildString {
            val elapsedMs = (System.currentTimeMillis() - snapshot.startedAtMs).coerceAtLeast(0L)
            appendLine("WebGPU diagnostics - ${snapshot.header}")
            appendLine(
                "  elapsed=${elapsedMs}ms decodes=${snapshot.decodeCount} " +
                    "total=${snapshot.decodeTotalMs}ms max=${snapshot.decodeMaxMs}ms (${snapshot.decodeMaxLabel})",
            )
            if (snapshot.decodeOutcomes.isNotEmpty()) {
                appendLine("  outcomes=" + snapshot.decodeOutcomes.entries.joinToString(" ") { "${it.key}:${it.value}" })
            }
            appendLine(
                "  downsampled=${snapshot.downsampled} stalls=${snapshot.stalls} " +
                    "evictions=${snapshot.evictions} worstChurn=${snapshot.worstChurn}",
            )
            appendLine(
                "  deviceLosses=${snapshot.deviceLosses} recoveries=${snapshot.recoveries} " +
                    "failedRecoveries=${snapshot.failedRecoveries} parks=${snapshot.parks}",
            )
            if (snapshot.ladder.isNotEmpty()) {
                appendLine("  ladder=" + snapshot.ladder.entries.joinToString(" ") { "${it.key}:${it.value}" })
            }
            if (snapshot.budgets.isNotEmpty()) {
                snapshot.budgets.forEachIndexed { i, b -> appendLine("  budget[$i]=$b") }
            }
        }.trimEnd()
    }.getOrElse { "WebGPU diagnostics unavailable: ${it::class.simpleName}" }

    /**
     * Whether the session did anything worth writing down on its own.
     *
     * Deliberately excludes a clean run: the in-app log is read by someone chasing a problem, and a
     * healthy session's block is noise there.
     */
    fun isNotable(): Boolean = synchronized(this) {
        deviceLosses > 0 || failedRecoveries > 0 || parks > 0 ||
            downsampledCount > 0 || stallCount > 0 || worstChurn() >= 3 ||
            (ladder[STUCK_GIVE_UP] ?: 0) > 0
    }

    private const val STUCK_GIVE_UP = "GIVE_UP"

    /**
     * Closes the session, writing it to the in-app log when it was notable.
     *
     * Returns the rendered block either way, so a caller that is already reporting something (a
     * strike, a crash dump) can include it without asking twice.
     */
    fun endSession(): String {
        val report = render()
        val notable = runCatching { isNotable() }.getOrDefault(false)
        if (notable) {
            // xLogE rather than xLog: this is written for a report, and the in-app log's level is
            // what a bug report filter reads.
            xLogE(report)
        }
        logcat(LogPriority.DEBUG) { report }
        return report
    }

    /** A one-line summary for a caller that is already logging its own line. */
    fun summary(): String = synchronized(this) {
        "decodes=$decodeCount outcomes=${decodeOutcomes.size} evictions=$evictions " +
            "losses=$deviceLosses parks=$parks downsampled=$downsampledCount stalls=$stallCount"
    }

    private data class Snapshot(
        val header: String,
        val startedAtMs: Long,
        val decodeOutcomes: Map<String, Int>,
        val decodeCount: Int,
        val decodeTotalMs: Long,
        val decodeMaxMs: Long,
        val decodeMaxLabel: String,
        val downsampled: Int,
        val stalls: Int,
        val evictions: Int,
        val worstChurn: Int,
        val deviceLosses: Int,
        val recoveries: Int,
        val failedRecoveries: Int,
        val parks: Int,
        val ladder: Map<String, Int>,
        val budgets: List<String>,
        val lastBudget: String,
    )
}
// KMK <--
