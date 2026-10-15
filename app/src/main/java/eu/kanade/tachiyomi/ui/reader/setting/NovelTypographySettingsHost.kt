// KMK -->
package eu.kanade.tachiyomi.ui.reader.setting

import tachiyomi.core.common.preference.SettingHost
import tachiyomi.core.common.preference.SettingsRegistry

/**
 * Registers the novel reader's typography keys with [SettingsRegistry].
 *
 * The registry asserts global key uniqueness at startup, so a typography key that collides with one
 * from another feature fails immediately and visibly rather than silently shadowing that setting
 * depending on which class happened to load first.
 */
object NovelTypographySettingsHost : SettingHost {
    override val settingKeys = NovelTypographySettingKeys.all

    init {
        SettingsRegistry.register(this)
    }
}
// KMK <--
