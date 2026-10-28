package eu.kanade.tachiyomi.extension

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects extension failures and decides which one the user should be asked to report.
 *
 * A failing extension keeps failing, so every rule here exists to stop the prompt from repeating:
 * a shown-prompt set keyed by the exact failure, a suppression set keyed by package and version
 * code, and the user's own switch for the whole system. A new version of the same extension is a
 * new thing that can break a new way, so it clears neither set implicitly.
 */
@Inject
@SingleIn(AppScope::class)
class ExtensionErrorReporter(
    private val sourcePreferences: SourcePreferences,
) {

    private val _pendingPrompt = MutableStateFlow<ExtensionErrorReport?>(null)
    val pendingPrompt: StateFlow<ExtensionErrorReport?> = _pendingPrompt.asStateFlow()

    /**
     * Latest failure per package, for subtext on the extensions list. Keyed by package name alone
     * because that is what the list row knows; install and load errors both land here.
     */
    private val _errorsByPkg = MutableStateFlow<Map<String, ExtensionErrorReport>>(emptyMap())
    val errorsByPkg: StateFlow<Map<String, ExtensionErrorReport>> = _errorsByPkg.asStateFlow()

    /**
     * Records a failure. Safe to call from any thread and for the same failure repeatedly: only a
     * failure that has not been prompted for yet can raise a prompt.
     *
     * The failure is always recorded in [errorsByPkg] even when no prompt may follow, so the
     * extensions list keeps explaining why a source returns nothing. Turning the watchdog off, or
     * [prompt] being false, is about interrupting the user - not about hiding the evidence.
     *
     * @param prompt false to record the failure without interrupting. For the multi-source
     * fan-outs - the global search and the source feed - which hit several sources at once and
     * would otherwise raise a burst of popups over the results.
     */
    fun report(report: ExtensionErrorReport, prompt: Boolean = true) {
        _errorsByPkg.update { it + (report.pkgName to report) }

        if (!prompt) return
        if (!sourcePreferences.extensionWatchdogEnabled().get()) return

        // Checking the shown/suppressed sets and taking the prompt slot must be one step.
        // report() runs on whatever thread the failure landed on - a paging worker, a download
        // thread, a reader coroutine - and two failures arriving together could both find the
        // slot empty, both mark themselves prompted, and one silently overwrite the other's
        // popup without ever being seen.
        synchronized(this) {
            if (isSuppressed(report) || hasPrompted(report)) return
            if (_pendingPrompt.value != null) return

            _pendingPrompt.value = report
            markPrompted(report)
        }
    }

    /** The user acknowledged. The failure stays visible as subtext and may prompt again later. */
    fun dismissPrompt() {
        synchronized(this) {
            _pendingPrompt.value = null
        }
    }

    /**
     * The user asked not to be told about this again until the extension is updated.
     *
     * Takes the report rather than reading it off [pendingPrompt], because the popup that offered
     * this is dismissed before the confirmation is answered - the report has to survive that.
     */
    fun suppressCurrentVersion(report: ExtensionErrorReport) {
        synchronized(this) {
            if (_pendingPrompt.value?.dedupeKey == report.dedupeKey) _pendingPrompt.value = null
        }
        val suppressed = sourcePreferences.extensionErrorPromptsSuppressed()
        suppressed.set(suppressed.get() + report.versionKey)
    }

    /**
     * The user turned the watchdog off for good, not just for this extension.
     *
     * Covers every extension rather than one, because the thing prompting is the system as a whole:
     * an extension that fails silently is normal, and a user who has seen three of these popups in a
     * row wants them to stop, not to keep being told about each source separately. Re-enabling lives
     * in settings.
     */
    fun disableSystem() {
        synchronized(this) {
            _pendingPrompt.value = null
        }
        sourcePreferences.extensionWatchdogEnabled().set(false)
    }

    fun errorFor(pkgName: String): ExtensionErrorReport? = _errorsByPkg.value[pkgName]

    /**
     * Drops the recorded failure for [pkgName], and any prompt still waiting for it.
     *
     * Called both when a source call succeeds - whatever was recorded no longer holds - and when
     * the extension is gone. The user's own answers are kept either way: [dismissPrompt] and
     * [suppressCurrentVersion] are about being interrupted, and a repeat of a failure the user
     * acknowledged should not start popping up again just because the source answered once.
     * [forget] is the one that drops those.
     */
    fun clear(pkgName: String) {
        _errorsByPkg.update { it - pkgName }
        synchronized(this) {
            if (_pendingPrompt.value?.pkgName == pkgName) {
                _pendingPrompt.value = null
            }
        }
    }

    /**
     * Forgets an extension entirely: the recorded failure, any prompt still waiting for it, and
     * the persisted shown/suppressed bookkeeping for it.
     *
     * Called on uninstall, the one moment those persisted keys are known dead. They would
     * otherwise only ever grow - every distinct failure adds one, and nothing else removes any -
     * and each key outlives the extension that earned it. A reinstall of the same version starts
     * with a clean sheet, which is the right reading: whatever the user answered before, they
     * answered about a copy they chose to remove.
     */
    fun forget(pkgName: String) {
        clear(pkgName)
        // Both key shapes - versionKey and dedupeKey - start with "pkgName:", and the trailing
        // colon is what keeps one package name from matching another that shares a prefix.
        val prefix = "$pkgName:"
        val shown = sourcePreferences.extensionErrorPromptsShown()
        shown.set(shown.get().filterNot { it.startsWith(prefix) }.toSet())
        val suppressed = sourcePreferences.extensionErrorPromptsSuppressed()
        suppressed.set(suppressed.get().filterNot { it.startsWith(prefix) }.toSet())
    }

    private fun hasPrompted(report: ExtensionErrorReport): Boolean {
        return report.dedupeKey in sourcePreferences.extensionErrorPromptsShown().get()
    }

    private fun markPrompted(report: ExtensionErrorReport) {
        val shown = sourcePreferences.extensionErrorPromptsShown()
        shown.set(shown.get() + report.dedupeKey)
    }

    private fun isSuppressed(report: ExtensionErrorReport): Boolean {
        return report.versionKey in sourcePreferences.extensionErrorPromptsSuppressed().get()
    }
}

/**
 * A single extension failure, with enough identity to name the repository it came from so the user
 * reports it to that repository rather than to the app's own support channels.
 */
data class ExtensionErrorReport(
    val pkgName: String,
    val extensionName: String,
    val versionName: String,
    val versionCode: Long,
    val reason: String,
    val repoName: String? = null,
    val repoWebsiteUrl: String? = null,
    val sourceName: String? = null,
    /** Full trace, kept out of the prompt text but included in a copied report. */
    val stackTrace: String? = null,
    /** Issue search scoped to the repository that ships this extension, not to this app. */
    val searchUrl: String,
) {
    /** Suppression granularity: one version of one extension. */
    val versionKey: String get() = "$pkgName:$versionCode"

    /** Prompt granularity: one version failing one particular way. */
    val dedupeKey: String get() = "$pkgName:$versionCode:$reason"
}
