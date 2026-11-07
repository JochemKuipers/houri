package upscale.identity

import java.security.MessageDigest

/**
 * The spine-owned cache identity. One place computes an upscaled page's key — the old bug class
 * (two call sites deriving different identities for the same configuration) is designed out by
 * making the key a spine value, exactly as the MTL spine did for its cache.
 *
 * **The algorithm is byte-compatible with the old `UpscaleCacheManager.cacheKeyDetailed` v3
 * scheme on purpose**: content spread-sampling, size, factor, model, preset and
 * backend:format:artifact-hashes normalize identically, so every entry written by the previous
 * stack still hits after the cutover. Changing anything here orphans the whole 200 MB cache —
 * if you must, bump [KEY_VERSION] and say why.
 */
object UpscaleIdentity {

    const val KEY_VERSION = "upscale-cache-v3"
    private const val FULL_HASH_MAX_BYTES = 256 * 1024

    /** Key for the simple resample path. Matches the old simple-mode composition. */
    fun simpleKey(
        bytes: ByteArray,
        factor: Float,
        algoName: String,
        modeName: String,
    ): String = key(
        bytes = bytes,
        factor = factor,
        model = "simple_$algoName",
        extra1 = algoName,
        extra2 = modeName,
    )

    /** Key for the native inference path. Matches the old native-mode composition. */
    fun nativeKey(
        bytes: ByteArray,
        factor: Float,
        modelId: String,
        presetName: String,
        backendName: String,
        formatName: String,
        artifactIdentities: List<String>,
    ): String = key(
        bytes = bytes,
        factor = factor,
        model = modelId,
        extra1 = presetName,
        extra2 = buildString {
            append(backendName)
            append(':')
            append(formatName)
            append(':')
            append(artifactIdentities.joinToString(","))
        },
    )

    fun key(
        bytes: ByteArray,
        factor: Float,
        model: String,
        extra1: String,
        extra2: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(KEY_VERSION.toByteArray())
        if (bytes.isNotEmpty()) {
            if (bytes.size <= FULL_HASH_MAX_BYTES) {
                digest.update(bytes)
            } else {
                // Five spread samples: pages from the same chapter share headers and
                // backgrounds, so clustered samples collide across distinct pages.
                sample(digest, bytes, 0, 64 * 1024)
                sample(digest, bytes, bytes.size / 4, 32 * 1024)
                sample(digest, bytes, bytes.size / 2, 32 * 1024)
                sample(digest, bytes, bytes.size * 3 / 4, 32 * 1024)
                val tailLen = minOf(32 * 1024, bytes.size)
                digest.update(bytes, bytes.size - tailLen, tailLen)
            }
        }
        digest.update(bytes.size.toString().toByteArray())
        val safeFactor = if (factor.isFinite()) factor else 2f
        val normalizedExtras = buildString {
            append(safeFactor)
            append('|')
            append(model.take(64))
            if (extra1.isNotEmpty()) {
                append('|')
                append(extra1.take(64))
            }
            if (extra2.isNotEmpty()) {
                append('|')
                append(extra2.take(64))
            }
        }
        digest.update(normalizedExtras.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sample(digest: MessageDigest, bytes: ByteArray, offset: Int, maxLen: Int) {
        if (offset < 0 || offset >= bytes.size) return
        val len = minOf(maxLen, bytes.size - offset)
        if (len > 0) digest.update(bytes, offset, len)
    }
}
