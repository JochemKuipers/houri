package mtl.store

import java.security.MessageDigest

/**
 * Everything about the current configuration that changes what a translated page *is*.
 *
 * This is a spine-owned value: every cache key is derived from it here and nowhere else. The
 * old system had three call sites deriving identities and they had already drifted — a
 * metadata entry could be stamped with one provider while pages translated with another, and
 * switching target language served permanently stale saved pages because language was absent
 * from the key entirely.
 */
data class CacheIdentity(
    val providerId: String,
    val model: String,
    val sourceLang: String,
    val targetLang: String,
    val promptFingerprint: String,
) {
    /** Stable, collision-safe string folded into every key. */
    fun encoded(): String = "$providerId|$model|$sourceLang>$targetLang|$promptFingerprint"
}

/**
 * Pure key derivation for both page stores.
 *
 * The content-addressed store keys on the page's own bytes (a page translated once under a
 * configuration is the same translation for any chapter that shows those bytes); the
 * saved-page store additionally keys on position so a chapter revisits its own files.
 */
object CacheKeys {

    fun contentHash(imageBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(imageBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Key into the content-addressed store. */
    fun forContent(imageBytes: ByteArray, identity: CacheIdentity): String =
        "${contentHash(imageBytes)}|${identity.encoded()}"

    /** Key into the saved-page store. */
    fun forSavedPage(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        imageBytes: ByteArray,
        identity: CacheIdentity,
    ): String = "$mangaId/${chapterId}_$pageIndex|${contentHash(imageBytes)}|${identity.encoded()}"
}
