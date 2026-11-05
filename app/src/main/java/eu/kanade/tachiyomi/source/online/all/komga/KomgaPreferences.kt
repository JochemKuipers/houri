package eu.kanade.tachiyomi.source.online.all.komga

import android.content.SharedPreferences

/**
 * Per-source preferences for the baked-in Komga source, stored on the standard
 * `source_$id` SharedPreferences file (extension ABI). Keys mirror the official
 * Keiyoushi extension's so the two can share muscle memory, but the class layout
 * is our own and none of it crosses the extension boundary.
 */

object KomgaSettingKeys {
    const val ADDRESS = "Address"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val API_KEY = "API key"
    const val DISPLAY_NAME = "Source display name"
}

/** Configured server address with a single trailing slash removed, or blank when unset. */
fun SharedPreferences.serverBase(): String =
    getString(KomgaSettingKeys.ADDRESS, "")!!.trim().trimEnd('/')

/** Display name for the source list (e.g. "Komga (home)"), or blank. */
fun SharedPreferences.displayName(): String =
    getString(KomgaSettingKeys.DISPLAY_NAME, "")!!.trim()
