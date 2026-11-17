package eu.kanade.tachiyomi.data.achievement

import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.connections.service.WebhookPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.log.xLogE
import exh.yakuyomi.TranslationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.achievement.service.AchievementManager
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Unlocks "you turned this on" achievements from the preference behind them.
 *
 * The reachability audit found these ids defined with no unlock site anywhere, so they could
 * never be earned. Each is a setting the user controls, which made one generic watcher cheaper
 * and less error prone than a bespoke call in every settings screen - and it also catches
 * values that only ever arrive from a backup restore or a migration.
 *
 * Two properties the previous version got wrong, and which are the reason this is not just a
 * `collect`:
 *
 * - **Seeded, not change-only.** [Preference.changes] never emits the current value, so a
 *   preference that was already set before the hooks were installed - including one restored
 *   from a backup - could never unlock. Each watcher now evaluates the value it starts with.
 *   Exception: a preference whose satisfied state is the shipped default (`cutout_enthusiast`,
 *   `search_ast`) is watched change-only, or the seed itself unlocks for every user at startup.
 * - **Installed unconditionally.** The old `if (!enabled) return` meant enabling achievements
 *   later left no watchers at all for the rest of the process. [AchievementManager] already
 *   gates every unlock on the same preference, so the watchers can be installed either way.
 */
class FeatureAchievementHooks(
    private val manager: AchievementManager,
    private val uiPreferences: UiPreferences,
    private val connectionsPreferences: ConnectionsPreferences,
    private val webhookPreferences: WebhookPreferences,
    private val readerPreferences: ReaderPreferences,
    private val translationPreferences: TranslationPreferences,
    private val libraryPreferences: LibraryPreferences,
    // KMK --> Tracker-connect achievements: they count the trackers the DB actually holds.
    private val trackRepository: TrackRepository,
    // KMK <--
) {

    fun install(scope: CoroutineScope) {
        // KMK --> The tracker-connect tiers are otherwise driven by login events alone, which a
        // backup restore never fires: rows for AniList/MAL/etc. land in manga_track with no
        // login in this process, so a user's whole restored tracking setup counted as zero.
        // The distinct-id list re-emits on every table change, so a restore landing mid-session
        // counts too. onTrackerConnected is idempotent, so repeat emissions are harmless.
        scope.launch {
            runCatching {
                trackRepository.getTrackerIdsAsFlow().collect { ids ->
                    manager.onTrackerConnected(ids.size)
                }
            }.onFailure { xLogE("Tracker-connect achievement watcher failed", it) }
        }
        // KMK <--
        watch(scope, { uiPreferences.censorLewdManga() }, { it }, "censor_toggle")
        watch(scope, { connectionsPreferences.enableDiscordRPC() }, { it }, "discord_rpc")
        watch(scope, { readerPreferences.chapterCompletionSound() }, { it }, "moan_enabled")
        watch(scope, { translationPreferences.autoTranslateOnDownload() }, { it }, "mtl_auto_download")
        watch(scope, { webhookPreferences.enabled() }, { it }, "webhook")
        watch(scope, { libraryPreferences.smartScanlatorMerge() }, { it }, "smart_filter")
        watch(
            scope,
            { libraryPreferences.displayMode() },
            { it == LibraryDisplayMode.StaggeredGrid },
            "staggered_grid",
        )
        // A model path or glossary JSON is the only evidence either was ever configured.
        watch(scope, { translationPreferences.localModel() }, { it.isNotBlank() }, "mtl_local")
        watch(scope, { translationPreferences.glossaryJson() }, { it.isNotBlank() }, "mtl_glossary")
        // Reading with the cutout left on, and turning the AST search parser on: both are
        // settings, so both belong with the rest of them rather than in a screen's onClick.
        // KMK --> NOT seeded: both preferences default to true, so a seed read fires on every
        // app start and hands out the achievement to users who never touched the setting.
        // Only a user's own change to true (or a restored-off value being re-enabled) unlocks.
        watch(scope, { readerPreferences.drawUnderCutout() }, { it }, "cutout_enthusiast", seeded = false)
        watch(scope, { libraryPreferences.librarySearchAstEnabled() }, { it }, "search_ast", seeded = false)
        // KMK <--
        // Offline fallback only means anything while Gemini Nano is switched on too - the
        // description names both halves, and the fallback alone is a preference nobody sets.
        // Watched off the fallback itself, so the combination is also caught when it was
        // already true before these hooks were installed.
        watch(
            scope,
            { translationPreferences.offlineFallback() },
            { it && translationPreferences.geminiNanoEnabled().get() },
            "mtl_offline",
        )
    }

    private fun <T> watch(
        scope: CoroutineScope,
        pref: () -> Preference<T>,
        satisfied: (T) -> Boolean,
        id: String,
        // KMK --> Seeded by default; a default-true preference must not be, or the seed fires
        // for every user at every start (see the cutout/AST callers).
        seeded: Boolean = true,
        // KMK <--
    ) {
        scope.launch {
            runCatching {
                val p = pref()
                // Seed from the current value so a preference that was satisfied before
                // installation - or arrived via restore - still unlocks exactly once.
                // KMK --> Skipped for default-true preferences (cutout, AST): their satisfied
                // state is the shipped default, so a seed would unlock without any user action.
                if (seeded && satisfied(p.get())) manager.tryUnlockDirect(id)
                // KMK <--
                p.changes().collect { if (satisfied(it)) manager.tryUnlockDirect(id) }
            }.onFailure {
                xLogE("Achievement watcher for $id failed", it)
            }
        }
    }
}
