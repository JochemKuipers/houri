package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR

// KMK -->
/**
 * Provider setup subpage of the MTL/Yakuyomi settings hub.
 *
 * Hosts the Gemini Nano toggle/status, cloud provider API key/model/fetch
 * refresh, custom base URL/headers and MangaTranslator guidance/account
 * navigation. All rows and gating are reused from [SettingsYakuyomiScreen]
 * so preference keys and visibility behavior are unchanged.
 */
@Serializable
data object SettingsYakuyomiProviderRoute : NavKey, SearchableRoute {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KMR.strings.pref_yakuyomi_provider_setup

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.translationPreferences }
        return listOf(
            SettingsYakuyomiRoute.getProviderGroup(prefs),
            SettingsYakuyomiRoute.getMangaTranslatorGroup(prefs),
        )
    }
}
// KMK <--
