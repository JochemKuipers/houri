package eu.kanade.domain.source.service

import mihon.domain.extension.model.ContentWarning
import tachiyomi.core.common.preference.Preference

/** Offline stub for check.sh: only the members migrations touch. Never compiled into the app. */
class SourcePreferences {
    fun enabledContentWarnings(): Preference<Set<ContentWarning>> = error("stub")
}
