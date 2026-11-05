package mtl.core

/**
 * Whether a page's bytes are worth decoding, and at what sample size. Pure — the caller
 * (Android bridge) performs the bounds decode and passes the result in.
 *
 * These are the single definition of a translatable page: size, dimension, and slice gates
 * live here and nowhere else, so they cannot drift apart the way the old queue-check and
 * worker-check copies did.
 */
sealed interface PageCheck {
    data class Ok(
        val width: Int,
        val height: Int,
        /** Power-of-two decode downscale; 1 means full resolution. */
        val sampleSize: Int,
        /**
         * True when a sliceable tall page decodes at full resolution: the pipeline re-slices
         * it, and pre-downscaling would lose stroke detail before slicing ever sees it.
         */
        val isTallFullRes: Boolean,
    ) : PageCheck

    /** [reason] is user-facing wording for the page's error state. */
    data class Rejected(val reason: String) : PageCheck
}

/** Bounds of an image, as the platform's bounds decode reports them. */
data class ImageBounds(val width: Int, val height: Int)

object PageDecision {
    /** Below this a "page" is almost certainly a truncated or failed download. */
    const val MIN_BYTES = 1024L

    /** Above this the decode plus the pipeline's working copies risk an OOM. */
    const val MAX_BYTES = 30L * 1024 * 1024

    const val MAX_WIDTH = 10_000
    const val MAX_HEIGHT = 10_000

    /** Tall pages are allowed extra height, but only while the pixel count stays affordable. */
    const val MAX_TALL_HEIGHT = 30_000
    const val MAX_TALL_PIXELS = 36_000_000L

    private const val SAMPLE_4X_THRESHOLD = 6_000
    private const val SAMPLE_2X_THRESHOLD = 4_096

    /** Tall-page slicing gate (ported verbatim from the old slicer). */
    fun shouldSlice(width: Int, height: Int): Boolean {
        if (width <= 0 || height < TALL_MIN_HEIGHT) return false
        if (width > TALL_MAX_WIDTH) return false
        return height.toDouble() / width.toDouble() >= TALL_MIN_ASPECT
    }

    const val TALL_MIN_HEIGHT = 3600
    const val TALL_MAX_WIDTH = 4000
    const val TALL_MIN_ASPECT = 2.3

    /**
     * Size-only rejection, for callers that guard before enqueuing and want to avoid the
     * bounds decode. Returns the user-facing reason, or null when acceptable.
     */
    fun sizeRejection(imageBytes: ByteArray): String? = when {
        imageBytes.size > MAX_BYTES -> "Image too large"
        imageBytes.size < MIN_BYTES -> "Image too small or corrupted"
        else -> null
    }

    fun decide(
        imageBytes: ByteArray,
        bounds: ImageBounds?,
        longPageSlicingEnabled: Boolean,
    ): PageCheck {
        sizeRejection(imageBytes)?.let { return PageCheck.Rejected(it) }
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            val dims = bounds?.let { "${it.width}x${it.height}" } ?: "unreadable"
            return PageCheck.Rejected("Invalid image dimensions $dims")
        }
        val width = bounds.width
        val height = bounds.height

        val pixelCount = width.toLong() * height.toLong()
        val isTallFullRes = longPageSlicingEnabled &&
            shouldSlice(width, height) &&
            pixelCount in 1..MAX_TALL_PIXELS

        val heightLimit = if (isTallFullRes) MAX_TALL_HEIGHT else MAX_HEIGHT
        if (width !in 1..MAX_WIDTH || height !in 1..heightLimit) {
            return PageCheck.Rejected("Invalid image dimensions ${width}x$height")
        }

        val maxDimension = maxOf(width, height)
        val sampleSize = when {
            isTallFullRes -> 1
            maxDimension > SAMPLE_4X_THRESHOLD -> 4
            maxDimension > SAMPLE_2X_THRESHOLD -> 2
            else -> 1
        }
        return PageCheck.Ok(width, height, sampleSize, isTallFullRes)
    }
}
