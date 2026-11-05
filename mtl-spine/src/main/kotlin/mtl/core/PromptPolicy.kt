package mtl.core

import java.security.MessageDigest

/**
 * Global, provider-neutral translation instructions shared by every text backend.
 *
 * Direct port of the old `TranslationPromptPolicy` — these fields ARE the prompt's meaning,
 * so [fingerprint] is what the cache identity carries: changing any field here invalidates
 * cached translations, exactly as it must.
 */
data class PromptPolicy(
    val customInstructions: String = "",
    val formality: String = "auto",
    val sfxPolicy: String = "preserve",
    /** Forced term → translation pairs the user pinned. */
    val glossary: Map<String, String> = emptyMap(),
    val fewShotSource: String = "",
    val fewShotTarget: String = "",
) {
    fun fingerprint(): String {
        val glossaryText = glossary.entries
            .sortedBy { it.key }
            .joinToString("|") { "${it.key}=>${it.value}" }
        val raw = listOf(
            customInstructions.trim(),
            formality.trim().lowercase(),
            sfxPolicy.trim().lowercase(),
            glossaryText,
            fewShotSource.trim(),
            fewShotTarget.trim(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        val DEFAULT = PromptPolicy()
    }
}
