// Runs the REAL WebGpuDiagnostics (compiled straight from app/.../WebGpuDiagnostics.kt).
//
// This is the one piece of the reader whose output is read by a *person*: the block a phone-only
// user sends with a bug report. Its contract is therefore two rules, and both are invisible when
// broken:
//
//   - a clean session writes nothing to the in-app log (otherwise the log is noise for whoever is
//     chasing a problem), and
//   - a notable one writes a block that actually names what went wrong.
//
// The logcat and xLog facades are stubbed (they are no-ops here), so nothing is written during the
// run.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuDiagnostics

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

fun main() {
    // --- a clean session is not notable, and renders a header anyway ------------------------
    WebGpuDiagnostics.beginSession("tier=MEDIUM budget=pressure=ROOMY")
    var report = WebGpuDiagnostics.render()
    check("the header is carried", report.contains("tier=MEDIUM budget=pressure=ROOMY"))
    check("a clean session says zero decodes", report.contains("decodes=0"))
    check("a clean session is not notable", !WebGpuDiagnostics.isNotable())

    // --- decode accounting ------------------------------------------------------------------
    WebGpuDiagnostics.recordDecode("decoded", 120L, "ch=1/i=0", downsampled = false)
    WebGpuDiagnostics.recordDecode("decoded", 300L, "ch=1/i=1", downsampled = false)
    WebGpuDiagnostics.recordDecode("no-stream", 40L, "ch=1/i=2", downsampled = true)
    report = WebGpuDiagnostics.render()
    check("decodes are counted", report.contains("decodes=3"))
    check("total duration is summed", report.contains("total=460ms"))
    check("max duration names its page", report.contains("max=300ms (ch=1/i=1)"))
    check("outcomes are aggregated", report.contains("outcomes=") && report.contains("decoded:2"))
    check("a downsample is counted", report.contains("downsampled=1"))
    // A downsample is a normal, expected event under memory pressure - but it is still worth
    // reporting, because a session that downsampled everything has a budget problem.
    check("a downsample makes a session notable", WebGpuDiagnostics.isNotable())

    // --- a decode that never finishes --------------------------------------------------------
    WebGpuDiagnostics.beginSession("tier=SMALL")
    check("a fresh session clears the previous one", !WebGpuDiagnostics.isNotable())
    WebGpuDiagnostics.recordStall()
    check("a stall is counted", WebGpuDiagnostics.render().contains("stalls=1"))
    check("a stall makes a session notable", WebGpuDiagnostics.isNotable())

    // --- device lifecycle --------------------------------------------------------------------
    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordDeviceLoss("probe")
    check("a device loss is counted", WebGpuDiagnostics.render().contains("deviceLosses=1"))
    check("a device loss makes a session notable", WebGpuDiagnostics.isNotable())

    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordRecovery(ok = true)
    check("a recovery is counted", WebGpuDiagnostics.render().contains("recoveries=1"))
    check("a recovery alone is not notable", !WebGpuDiagnostics.isNotable())

    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordRecovery(ok = false)
    check("a failed recovery is counted", WebGpuDiagnostics.render().contains("failedRecoveries=1"))
    check("a failed recovery makes a session notable", WebGpuDiagnostics.isNotable())

    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordPark(3)
    check("parks accumulate", WebGpuDiagnostics.render().contains("parks=3"))
    check("a park makes a session notable", WebGpuDiagnostics.isNotable())
    WebGpuDiagnostics.recordPark(-2)
    check("a negative park count is ignored", WebGpuDiagnostics.render().contains("parks=3"))

    // --- evictions, and the churn signal ------------------------------------------------------
    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordEviction("ch=1/i=0")
    WebGpuDiagnostics.recordEviction("ch=1/i=1")
    check("evictions are counted", WebGpuDiagnostics.render().contains("evictions=2"))
    check("a worst churn of one is quiet", WebGpuDiagnostics.render().contains("worstChurn=1"))
    check("a single eviction is not notable", !WebGpuDiagnostics.isNotable())

    // The same page evicted three times inside the recent window is the evict/recreate livelock's
    // signature, which is the one eviction pattern worth a report on its own.
    WebGpuDiagnostics.recordEviction("ch=1/i=0")
    WebGpuDiagnostics.recordEviction("ch=1/i=0")
    check("a repeated eviction raises the churn", WebGpuDiagnostics.render().contains("worstChurn=3"))
    check("eviction churn makes a session notable", WebGpuDiagnostics.isNotable())

    // The recent window is bounded, so a long clean stretch cannot keep a churn signal alive.
    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    for (i in 0 until 40) WebGpuDiagnostics.recordEviction("ch=1/i=0")
    check("the recent window is bounded", WebGpuDiagnostics.render().contains("worstChurn=12"))
    check("bounded churn is still notable", WebGpuDiagnostics.isNotable())

    // --- the stuck ladder ---------------------------------------------------------------------
    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordLadder("REDRIVEN")
    check("ladder rungs are counted", WebGpuDiagnostics.render().contains("ladder=") &&
        WebGpuDiagnostics.render().contains("REDRIVEN:1"))
    check("a re-drive alone is not notable", !WebGpuDiagnostics.isNotable())
    WebGpuDiagnostics.recordLadder("GIVE_UP")
    check("a give-up is reported", WebGpuDiagnostics.render().contains("GIVE_UP:1"))
    check("a give-up makes a session notable", WebGpuDiagnostics.isNotable())

    // --- the memory budget --------------------------------------------------------------------
    WebGpuDiagnostics.beginSession("tier=MEDIUM")
    WebGpuDiagnostics.recordBudget("pressure=ROOMY pages=288MB")
    WebGpuDiagnostics.recordBudget("pressure=ROOMY pages=288MB")
    check("a repeated budget is stored once", WebGpuDiagnostics.render().contains("budget[0]=") &&
        !WebGpuDiagnostics.render().contains("budget[1]="))
    WebGpuDiagnostics.recordBudget("pressure=TIGHT pages=158MB")
    check("a changed budget is stored", WebGpuDiagnostics.render().contains("budget[1]="))

    // --- the summary line, and that render never throws ---------------------------------------
    val summary = WebGpuDiagnostics.summary()
    check("the summary names the counters", summary.contains("decodes=") &&
        summary.contains("evictions=") && summary.contains("losses="))
    check("render is total on an empty session", runCatching {
        WebGpuDiagnostics.beginSession("")
        WebGpuDiagnostics.render()
    }.isSuccess)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}