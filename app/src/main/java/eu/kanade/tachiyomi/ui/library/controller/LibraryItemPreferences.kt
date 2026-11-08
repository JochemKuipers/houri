package eu.kanade.tachiyomi.ui.library.controller

import androidx.compose.runtime.Immutable
import tachiyomi.core.common.preference.TriState

/**
 * The snapshot of every library preference one display pass consumes: which badges to render
 * and which filters are active.
 *
 * Shared by [LibraryViewModel]'s preference flow, [LibraryFilterHandler] (filtering) and the
 * favorites projection (badges), so all three always agree on the same inputs. Previously the
 * ViewModel held a private copy and the filter handler needed a field-by-field shim to receive
 * its subset.
 */
@Immutable
internal data class LibraryItemPreferences(
    val downloadBadge: Boolean,
    val unreadBadge: Boolean,
    val localBadge: Boolean,
    val languageBadge: Boolean,
    // KMK -->
    val useLangIcon: Boolean,
    val sourceBadge: Boolean,
    // KMK <--
    val skipOutsideReleasePeriod: Boolean,

    val globalFilterDownloaded: Boolean,
    val filterDownloaded: TriState,
    val filterUnread: TriState,
    val filterStarted: TriState,
    val filterBookmarked: TriState,
    val filterCompleted: TriState,
    val filterIntervalCustom: TriState,
    // SY -->
    val filterLewd: TriState,
    // SY <--
    // KMK -->
    val filterCategories: Boolean,
    // KMK <--
)
