package mtl.core

/**
 * Platform image operations the pipeline needs, injected so the spine stays pure.
 *
 * Implemented over android.graphics in the module's Android wiring; tests use fakes. Every
 * failure mode is a return value (null), never a throw — the pipeline maps nulls to page
 * outcomes directly.
 */
interface PageCodec {
    /** Bounds decode: dimensions without allocating pixels. */
    fun boundsOf(imageBytes: ByteArray): ImageBounds?

    /** Full decode, honoring the power-of-two [sampleSize] from [PageDecision]. */
    fun decode(imageBytes: ByteArray, sampleSize: Int): PageImage?

    /** Encoded WEBP for output and cache. */
    fun encodeWebp(image: PageImage, quality: Int): ByteArray?

    /** Encoded JPEG for vision-capable providers that need encoded input. */
    fun encodeJpeg(image: PageImage, quality: Int): ByteArray?
}
