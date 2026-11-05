package mtl.core

/**
 * What one provider call is asked to do. Requests are capability-shaped: a provider receives
 * exactly the variant its [ProviderKind] declares.
 */
sealed interface TranslationRequest {
    val sourceLang: String
    val targetLang: String
    val context: TranslationContext
    val policy: PromptPolicy

    /** Lines in, lines out — the [ProviderKind.TEXT_LINES] shape. */
    data class TextLines(
        override val sourceLang: String,
        override val targetLang: String,
        override val context: TranslationContext,
        override val policy: PromptPolicy,
        val lines: List<String>,
    ) : TranslationRequest

    /** Raster + grouped regions in, lines out — the [ProviderKind.VISION_PAGE] shape. */
    data class VisionPage(
        override val sourceLang: String,
        override val targetLang: String,
        override val context: TranslationContext,
        override val policy: PromptPolicy,
        val image: PageImage,
        /** OCR-grouped regions; [RegionGroup.sourceText] is what gets translated. */
        val regions: List<RegionGroup>,
    ) : TranslationRequest

    /** Encoded page in, encoded page out — the [ProviderKind.FULL_PAGE_IMAGE] shape. */
    data class FullPage(
        override val sourceLang: String,
        override val targetLang: String,
        override val context: TranslationContext,
        override val policy: PromptPolicy,
        val imageBytes: ByteArray,
        val imageMime: String,
    ) : TranslationRequest {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

/** What one provider call produced. */
sealed interface TranslationResult {
    /** One translated string per requested line, same order. */
    data class Lines(
        val translations: List<String>,
        /** The provider's raw output, kept for diagnostics. */
        val raw: String? = null,
    ) : TranslationResult

    /** Region-level output from a provider that did its own grouping/alignment. */
    data class Regions(val regions: List<TranslatedRegion>) : TranslationResult

    /** A finished, translated page (encoded image). */
    data class Page(val imageBytes: ByteArray, val mime: String) : TranslationResult

    /** The provider looked and found nothing to do. Not an error. */
    data class NothingToTranslate(val reason: String) : TranslationResult
}
