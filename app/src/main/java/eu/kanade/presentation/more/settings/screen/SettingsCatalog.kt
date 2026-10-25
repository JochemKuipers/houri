package eu.kanade.presentation.more.settings.screen

// KMK -->
import eu.kanade.tachiyomi.BuildConfig

/**
 * Single source of truth for the list of searchable settings screens.
 *
 * Previously this list was hardcoded in `SettingsSearchScreen` while the settings hub
 * (`SettingsMainScreen.items`) kept its own parallel list, so adding a screen meant
 * editing two files. Screen registration now happens here:
 *
 * - Full category entries (hub + search): add the screen here AND an `Item` in
 *   `SettingsMainScreen.items` (which additionally carries icon/subtitle UI metadata).
 * - Search-only coverage is automatic: `SettingsSearchScreen` indexes this list.
 *
 * Keep this list in sync with `SettingsMainScreen.items` — every hub screen that
 * implements [SearchableSettings] must appear here.
 */
object SettingsCatalog {

    /**
     * AI feature screens, dropped from the no-MTL build.
     *
     * Filtered out of [searchableScreens] rather than branched around, so search cannot reach a
     * screen the settings hub hides on that flavor.
     *
     * [SettingsUpscalerRoute] is deliberately *not* in this set: the no-MTL build forces the
     * upscaler into Simple mode (`UpscalePreferences.isSimpleMode` is flavor-true there), which
     * needs no MTL runtime and no downloaded weights, and the screen gates its native-only rows
     * off themselves (`mode` selector hidden, model rows `mtlOnly`). Hiding a screen nothing
     * depends on a missing runtime for would only strand the per-manga Upscale toggle, which
     * MangaScreen offers on every flavor.
     */
    private val aiScreens: Set<SearchableSettings> = setOf(
        SettingsYakuyomiRoute,
        SettingsYakuyomiProviderRoute,
        SettingsYakuyomiModelsRoute,
        SettingsYakuyomiBehaviorRoute,
        SettingsYakuyomiPromptRoute,
    )

    val searchableScreens: List<SearchableRoute> = listOfNotNull(
        SettingsAppearanceRoute,
        SettingsLibraryRoute,
        SettingsReaderRoute,
        // KMK -->
        // The book reader's typography. Also reachable as the reader's own *Reading appearance* tab;
        // this is the copy a reader can find without a book open.
        SettingsBookReadingRoute,
        // KMK <--
        SettingsDownloadRoute,
        SettingsTrackingRoute,
        // AM (CONNECTIONS) -->
        SettingsConnectionRoute,
        // <-- AM (CONNECTIONS)
        SettingsBrowseRoute,
        SettingsDataRoute,
        SettingsSecurityRoute,
        // SY -->
        SettingsEhRoute,
        SettingsMangadexRoute,
        // SY <--
        // KMK -->
        SettingsYakuyomiRoute,
        SettingsYakuyomiProviderRoute,
        SettingsYakuyomiModelsRoute,
        SettingsYakuyomiBehaviorRoute,
        SettingsYakuyomiPromptRoute,
        SettingsUpscalerRoute,
        // KMK <--
        SettingsAdvancedRoute,
    ).filterNot { BuildConfig.IS_NOMTL && it in aiScreens }
}
// KMK <--
