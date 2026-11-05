package mtl.core

/**
 * A raster the pipeline hands to vision-capable stages and providers.
 *
 * The spine owns the underlying platform image and closes it at the end of the page job; a
 * receiver must never close it. [platform] is the native representation
 * (`android.graphics.Bitmap` on Android) — vision code casts it, and that cast is the one
 * deliberate Android coupling in the SPI, the same trade the old `Translator` interface made.
 */
interface PageImage : AutoCloseable {
    val width: Int
    val height: Int
    val platform: Any
}

/** One point of a line's rotated quadrilateral, in page pixels. */
data class QuadPoint(val x: Float, val y: Float)

/**
 * One OCR'd line: where it is on the page and what was read there.
 *
 * [quad] is the detector's rotated quadrilateral (empty for an axis-aligned-only detector);
 * renderers that typeset vertical or tilted text need it, so stages carry it through instead
 * of flattening to boxes. [direction] is the line's writing direction ("h"/"v", OCR-determined)
 * and [angle] the region tilt in degrees — both feed typesetting.
 */
data class OcrLine(
    /** Region index this line belongs to (grouping output); -1 = ungrouped. */
    val regionIndex: Int,
    /** Bounding box in page pixels. */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val confidence: Float,
    val quad: List<QuadPoint> = emptyList(),
    /** Detection rectangle before DBNet's unclip expansion — glyph sizing uses this, not [quad]. */
    val tightQuad: List<QuadPoint>? = null,
    val direction: String = "h",
    val angle: Float = 0f,
)

/**
 * Lines the OCR stage grouped into one translatable unit — a speech bubble's sentence, a
 * sign's caption. Grouping is the OCR stage's output ([OcrLine.regionIndex]); the pipeline
 * only regroups lines that share an index, falling back to one-region-per-line when the
 * stage did not group. [sourceText] is the lines' text concatenated in reading order —
 * what the text provider translates as one string.
 */
data class RegionGroup(
    val regionIndex: Int,
    /** Member lines in reading order. */
    val lines: List<OcrLine>,
    val sourceText: String,
    /** Union bounding box of the member lines, in page pixels. */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val direction: String = "h",
    val angle: Float = 0f,
)

/** A translated region: the group's box plus what to typeset into it. */
data class TranslatedRegion(
    val regionIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val translatedText: String,
    /** Member lines carrying their quads, direction and angle for typesetting. */
    val lines: List<OcrLine> = emptyList(),
    val direction: String = "h",
    val angle: Float = 0f,
)

/**
 * Everything the translator is told about where this page sits, so pronouns and names stay
 * consistent across a chapter. Dynamic per-chapter state only — deliberately NOT part of the
 * cache identity: breadcrumbs change constantly and must not invalidate translated pages.
 */
data class TranslationContext(
    /** Series title / description / tags grounding, first line = title. */
    val mangaGrounding: String = "",
    /** Rolling cross-page notes ("the MC is called X", etc.). */
    val breadcrumbs: String = "",
) {
    val isBlank: Boolean get() = mangaGrounding.isBlank() && breadcrumbs.isBlank()
}
