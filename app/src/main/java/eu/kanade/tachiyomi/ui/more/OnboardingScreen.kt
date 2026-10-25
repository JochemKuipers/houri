package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.onboarding.OnboardingScreen
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.SettingsDataRoute
import eu.kanade.tachiyomi.ui.setting.addSettingsRoute
import mihon.app.di.globalAppGraph
import mihon.core.navigation.SettingsRoute
import mihon.core.navigation.domain.SettingsDestination
import mihon.core.navigation.util.LocalBackStack
import mihon.presentation.core.util.isTabletUi
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun OnboardingScreen() {
    val backStack = LocalBackStack.current
    val isTabletUi = isTabletUi()

    val basePreferences = remember { globalAppGraph.basePreferences }
    val shownOnboardingFlow by basePreferences.shownOnboardingFlow().collectAsState()

    val finishOnboarding: () -> Unit = {
        basePreferences.shownOnboardingFlow().set(true)
        // KMK --> Releases the achievement hold: anything unlocked from here on was enabled by
        // a completed onboarding, so it is the reader's choice and not a default that happened
        // to be true. See AchievementPreferences.unlocksHeldForOnboarding.
        runCatching { globalAppGraph.achievementPreferences.unlocksHeldForOnboarding = false }
        // KMK <--
        backStack.removeLastOrNull()
    }

    val restoreSettingKey = stringResource(SettingsDataRoute.restorePreferenceKeyString)

    BackHandler(
        enabled = !shownOnboardingFlow,
        onBack = {
            // Prevent exiting if onboarding hasn't been completed
        },
    )

    OnboardingScreen(
        onComplete = finishOnboarding,
        onRestoreBackup = {
            finishOnboarding()
            SearchableSettings.highlightKey = restoreSettingKey
            backStack.addSettingsRoute(SettingsRoute(SettingsDestination.DataAndStorage), isTabletUi)
        },
    )
}
