package eu.kanade.tachiyomi.ui.main

import android.app.SearchManager
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.ResultEventBus
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.ui.home.TabEvent
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import mihon.core.navigation.DeepLinkRoute
import mihon.core.navigation.ExtensionStoresRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.RestoreBackupRoute
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.util.popUntilRoot
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.Constants

internal fun MainActivity.handleIntentAction(
    intent: Intent,
    backStack: NavBackStack<NavKey>,
    resultEventBus: ResultEventBus,
    isTabletUi: Boolean,
): Boolean {
    // KMK --> Release the splash as soon as launch-intent routing runs. Every exit
    // from this function - a deep link, a shortcut, or the plain launcher MAIN
    // intent that matches no branch below - has decided the start destination, so
    // the splash has nothing left to wait for. Without this, the unmatched action
    // left `ready` false and the keepOnScreen condition pinned the splash for the
    // full SPLASH_MAX_DURATION (+ the 900ms exit animation) on every ordinary
    // cold start - ~6s of splash before the app became visible.
    ready = true
    // KMK <--
    val notificationId = intent.getIntExtra("notificationId", -1)
    if (notificationId > -1) {
        NotificationReceiver.dismissNotification(
            applicationContext,
            notificationId,
            intent.getIntExtra("groupId", 0),
        )
    }

    val tabToOpen = when (intent.action) {
        Constants.SHORTCUT_LIBRARY -> TabEvent.Library()
        Constants.SHORTCUT_MANGA -> {
            val idToOpen = intent.extras?.getLong(Constants.MANGA_EXTRA) ?: return false
            backStack.popUntilRoot()
            TabEvent.Library(idToOpen)
        }
        Constants.SHORTCUT_UPDATES -> TabEvent.Updates
        Constants.SHORTCUT_HISTORY -> TabEvent.History
        Constants.SHORTCUT_SOURCES -> TabEvent.Browse(false)
        Constants.SHORTCUT_EXTENSIONS -> TabEvent.Browse(true)
        Constants.SHORTCUT_DOWNLOADS -> {
            backStack.popUntilRoot()
            TabEvent.More(toDownloads = true)
        }
        // KMK -->
        Constants.SHORTCUT_LIBRARY_UPDATE_ERRORS -> {
            backStack.popUntilRoot()
            TabEvent.More(toDownloads = false, toLibraryUpdateErrors = true)
        }
        // KMK <--
        Intent.ACTION_APPLICATION_PREFERENCES -> {
            backStack.popUntilRoot()
            backStack.addSettingsRoute(SettingsRoute(), isTabletUi)
            null
        }
        Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
            // If the intent match the "standard" Android search intent
            // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

            // Get the search query provided in extras, and if not null, perform a global search with it.
            val query = intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!query.isNullOrEmpty()) {
                backStack.popUntilRoot()
                backStack.add(DeepLinkRoute(query))
            }
            null
        }
        MainActivity.INTENT_SEARCH -> {
            val query = intent.getStringExtra(MainActivity.INTENT_SEARCH_QUERY)
            if (!query.isNullOrEmpty()) {
                val filter = intent.getStringExtra(MainActivity.INTENT_SEARCH_FILTER)
                backStack.popUntilRoot()
                backStack.add(GlobalSearchRoute(query, filter))
            }
            null
        }
        Intent.ACTION_VIEW -> {
            // Handling opening of backup files
            if (intent.data.toString().endsWith(".tachibk")) {
                backStack.popUntilRoot()
                backStack.add(RestoreBackupRoute(intent.data.toString()))
            }
            // Deep link to add extension store
            else if (intent.isMainActivityAddExtensionStoreIntent()) {
                intent.data?.getQueryParameter("url")?.let { repoUrl ->
                    backStack.popUntilRoot()
                    backStack.add(ExtensionStoresRoute(repoUrl))
                }
            }
            null
        }
        else -> return false
    }

    if (tabToOpen != null) {
        resultEventBus.sendResult(tabToOpen)
    }

    return true
}

internal fun Intent.isMainActivityAddExtensionStoreIntent(): Boolean {
    return (scheme == "tachiyomi" && data?.host == "add-repo") ||
        (scheme == "mihon" && data?.host == "extension-store")
}
