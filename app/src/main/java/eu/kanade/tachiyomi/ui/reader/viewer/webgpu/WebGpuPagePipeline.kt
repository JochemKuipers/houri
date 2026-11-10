// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

// The page pipeline: the decode path's stages as a first-class concept, with a listener that
// carries each stage to the shell's placeholder so the reader can see what a page is waiting on.
//
// decodeReaderPage has had a stage structure since the decode tracer landed, but it existed only
// as strings handed to DecodeTrace.enter - the log could name the stage a wedged page sat in, and
// nothing else could ask. This file makes the stages an enum with two consumers:
//
// - the trace, which keeps its own exact stage names (the folder AGENTS' symptom table greps
//   them; a rename there is a diagnostic regression masquerading as a refactor), and
// - the placeholder, which renders the stage under the loading percentage - "43%" over
//   "Decoding" - so a slow page answers "what is it doing" and not only "how far along".
//
// The stages map onto the steps decodeReaderPage and startPageLoad already perform, in the same
// order; the pipeline adds no work of its own. That is deliberate: a stage list that rearranged
// the decode would be a rewrite wearing a naming convention's clothes.
//
// ## The interceptor contract
//
// "Modify a page on the fly" is [WebGpuExtension]'s job, and the stages are where a plug-in lands.
// The mapping is fixed and is the whole extensibility contract:
//
// | Stage | Interceptor seam | Mechanism |
// |---|---|---|
// | ENHANCE | per-byte, chained, may replace the bytes | `transformBytes` via `applyByteTransforms` (ordered by `order`, isolated per extension, serialized on `byteTransformMutex`) |
// | after ADOPT | per-page, observe a finished image | `onPageDecoded` via `runPageDecodedExtensions` (ordered, isolated) |
//
// A new on-the-fly modifier implements one of those two members and installs itself into
// `WebGpuExtensions`; it is automatically ordered, automatically isolated (a throwing extension is
// logged and skipped, never propagated to the decode worker), and automatically named in the
// placeholder's stage line while it runs (ENHANCE shows for the whole chain, however many
// extensions are in it). Nothing else in the pipeline is pluggable on purpose: the stages before
// ENHANCE (fetch/load/read/validate) and after it (decode/prepare/upload/adopt) are the reader's
// own integrity path, and handing them to plug-ins would hand it the ways to stall the worker
// this folder spent a year digging out of.

/**
 * One step of turning a page into a GPU image, with the label a reader sees while it holds.
 *
 * [traceName] is the DecodeTrace stage this maps to. Where a stage replaces an existing
 * `traceStage` call the name is byte-identical to the old one; where the stage is new
 * (FETCH/LOAD belong to the load path, which has no trace; PREPARE names the EXIF/rotate/
 * downsample block that previously traced as nothing until a downsample fired) the name is
 * new and purely additive.
 */
internal enum class PageStage(
    /** What the placeholder shows while the page sits here. */
    val label: String,
    /** The [DecodeTrace] stage name entered alongside, kept stable for log greps. */
    val traceName: String,
) {
    FETCH("Fetching", "fetch"),
    LOAD("Loading", "loading"),
    READ("Reading", "readBytes"),
    VALIDATE("Validating", "validate"),
    ENHANCE("Enhancing", "upscale"),
    METADATA("Reading details", "metadata"),
    DECODE("Decoding", "decoderInit"),
    PREPARE("Preparing", "prepare"),
    UPLOAD("Uploading", "gpuUpload"),
    ADOPT("Finalizing", "adopt"),
}

/**
 * Moves [page]'s pipeline to [stage]: traces the transition under the stage's trace name and,
 * while the shell still shows its placeholder, publishes the stage for the next frame.
 *
 * [detail] lands in the trace line verbatim (it is what a stall report quotes); [status]
 * overrides what the placeholder shows, for the cases where the trace detail is for a
 * diagnostician and not a reader ("cap=40MB" is the former, never the latter). The write races
 * nothing but a frame: only the decode worker and the load path call this, and the placeholder
 * is replaced wholesale at adoption or on error.
 */
internal fun WebGpuViewer.enterPipelineStage(
    page: ViewerReaderPage,
    trace: DecodeTrace?,
    stage: PageStage,
    detail: String? = null,
    status: String? = null,
) {
    traceStage(trace, stage.traceName, detail)
    val shown = status ?: detail
    val placeholder = page.imagePage as? ProgressPage ?: return
    placeholder.stageText = if (shown.isNullOrBlank()) stage.label else "${stage.label} · $shown"
    try {
        placeholder.invalidate()
    } catch (_: Exception) {
    }
}

/**
 * Publishes a load-path stage onto [page]'s placeholder without touching a trace - FETCH and
 * LOAD run before any decode trace exists, and the download percentage ride the same
 * placeholder through [startPageLoad]'s progressFlow collector.
 */
internal fun WebGpuViewer.publishLoadStage(page: ViewerReaderPage, stage: PageStage) {
    val placeholder = page.imagePage as? ProgressPage ?: return
    placeholder.stageText = stage.label
    try {
        placeholder.invalidate()
    } catch (_: Exception) {
    }
}
// KMK <--
