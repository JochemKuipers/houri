package eu.kanade.tachiyomi.network

import tachiyomi.core.common.preference.Preference

/** Offline stub for check.sh: only the members migrations touch. Never compiled into the app. */
class NetworkPreferences {
    fun dohProvider(): Preference<Int> = error("stub")
}
