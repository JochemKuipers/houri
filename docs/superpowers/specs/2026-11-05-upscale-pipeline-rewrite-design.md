# Upscaling Pipeline Ground-Up Rewrite — Design

**Date:** 2026-11-05
**Status:** Approved (user picked: full MTL decoupling, all nine features, one policy chain)
**Delivery:** Big-bang, mirroring the MTL rewrite (2026-10-28 spec): the new stack is built additively, then one cutover deletes the old one. The tree stays green throughout.

## 1. Goals

Replace the entire on-device upscaling stack — orchestration, backends, model management, and its
reader/settings wiring — because the current code is a "reader setting" rather than a subsystem:

- **Decoupling**: native upscaling stops depending on the MTL engine module and on the
  "translation enabled" gate. The `nomtl` flavor gains native upscaling; `IS_NOMTL` no longer
  forces Simple mode.
- **Structure**: one spine that owns the decision (which backend, in what order), the identity
  (cache keys), the schedule (read-ahead), and the truth (status). Readers execute; they never
  decide.
- **Features**: prefetch/read-ahead, status hub + reader HUD, one backend concept incl. the
  WebGPU ArtCNN tile upscaler, auto fallback chain, modernized settings, defaults beyond
  per-manga, stats with achievements extracted, model catalog expansion, user-imported ONNX,
  auto-download of models on first native enable.

## 2. Architecture

```
:upscale (new Gradle module, both flavors; namespace "upscale")
  upscale.core      SPI: UpscalerBackend (BYTE | RENDER_TILE), requests, outcomes, readiness
  upscale.policy    UpscalePolicy — the one decision site; UpscaleConfig snapshot; enablement
  upscale.identity  cache identity (spine-owned, byte-compatible with the old v3 keys)
  upscale.store     UpscalePageCache (moved UpscaleCacheManager)
  upscale.model     catalog/manifest/download/verify + imported ONNX (moved UpscaleModelManager)
  upscale.sched     PrefetchScheduler — read-ahead warming, priorities, memory gate
  upscale.status    UpscaleStatusHub — per-page truth + events; aggregates
  upscale           UpscaleController — the app-facing facade (mirrors MtlController)
  upscale.native    NcnnUpscaler (JNI), OrtUpscaleSession, SimpleResampler, PageBitmap,
                    DeviceProbe, and the three BYTE backends
  upscale/src/main/cpp  upscale_jni.cpp + CMakeLists.txt (moved from yakuyomi-engine;
                    NCNN prebuilts still come from external/yakuyomi-engine submodule)

app/ (wiring only — mihon/app/upscale/UpscaleWiring.kt, mirrors MtlWiring.kt)
  PrefsUpscaleConfigSource   prefs → spine UpscaleConfig (no translation dep)
  UpscaleBindings            @BindingContainer: cache, model manager, probe, backends,
                             controller, status, scheduler
  UpscaleAchievementObserver hub events → achievements (the spine never sees achievements)
  ReaderUpscaler (ui/reader/viewer)  the reader seam object, successor of UpscaleReaderHook
  ArtCnnTileBridge (webgpu)          RENDER_TILE registration + viewer sampler decision
```

**No flavor seam**: `:upscale` compiles into both flavors, so upscaling needs no stub module.
The JNI lib name stays `upscale_ncnn`; JNI symbols move to `Java_upscale_native_NcnnUpscaler_*`.

### Simplification vs. the presented design

The two-module split (`:upscale-spine` + `:upscale-native`) existed only because the MTL flavor
seam forced it. With native upscaling flavor-free there is no seam to bridge, so ONE module
(`:upscale`) carries both the spine and the native backends; package boundaries keep the same
purity rules (spine packages never import `android.graphics`; bitmap work lives in
`upscale.native`).

## 3. The SPI (`upscale.core`)

```kotlin
enum class UpscalerKind { BYTE, RENDER_TILE }

data class UpscaleRequest(
    val mangaId: Long, val chapterId: Long, val pageIndex: Int,
    val sourceBytes: ByteArray,
    val reason: Reason,                 // DISPLAY or PREFETCH
)

interface UpscalerBackend {
    val id: String                       // "ncnn", "ort", "simple", "artcnn-tile"
    val kind: UpscalerKind
    val displayName: String
    val capabilities: UpscalerCapabilities   // scale range, viewerScope?, costTier…
    val readiness: StateFlow<Readiness>      // Ready / Unavailable(reason)
    suspend fun process(request: UpscaleRequest): UpscaleOutcome   // BYTE backends
}
```

`RENDER_TILE` backends do not implement `process`. Requests stay `ByteArray`-shaped: the spine
never touches `android.graphics.Bitmap`.

**ArtCNN stays a global display decision.** It is a per-frame GPU tile *sampler*, not a byte
producer; per-page sampler swaps drop GPU state (the viewer code warns about exactly this), and
a byte-upscaled page plus tile refinement do not compound (sampling is not accumulation). So the
policy does not give ArtCNN a per-page plan slot: `tileRefinement` (the existing
`webgpu_artcnn_upscaler` pref, surfaced in the new screen) decides the WebGPU viewer's tile
sampler while mode != SIMPLE, the backend object appears in the registry/diagnostics/status, and
the status hub records when a display went through it. This is the honest form of "one backend
concept" — one settings surface, one registry, one status story — without per-page GPU thrash.

## 4. Policy — the one decision site

`UpscalePolicy.plan(input)` → ordered BYTE plan, e.g. `[ncnn:realcugan-2x@vulkan, simple:bicubic]`.
The executor walks the plan; the first success serves the page; every demotion is recorded in the
status hub (auto-fallback with a visible reason; no silent fallbacks — the MTL rule).

- Mode: **AUTO** (new default for fresh installs; native → simple fallback), **NATIVE**
  (native only, no resample fallback), **SIMPLE** (resample only). Existing stored values keep
  their meaning; nothing is force-migrated.
- The tile layer and enablement: `enabled(manga) = globalEnabled && mode != OFF-semantics &&
  (perMangaOverride ?: categoryDefault ?: defaultForNew)` — per-manga opt-ins keep the existing
  `upscale_manga_$id` prefs. The old "translation enabled" gate is gone.
- Cache identity is spine-owned (`upscale.identity`) and **byte-compatible with the old v3
  keys** (content spread-sampling, factor, model, preset, backend:format:hashes) so the existing
  200 MB disk cache survives the cutover.

## 5. Prefetch (`upscale.sched`)

The display path executes its plan directly (unchanged call shape). The scheduler runs the same
plan for page N+1..N+depth ahead, writing **only to the disk cache**; display is cache-first, so
a prefetched page shows with a cache hit and nothing is parked in RAM. Priorities: visible page >
read-ahead (default depth 2, configurable). Gates: a memory-pressure callback (injected) and
chapter-scoped cancellation. Inference stays single-flight: one shared semaphore across the BYTE
backends (preserves the old engine + `byteTransformMutex` behavior).

## 6. Status (`upscale.status`)

`UpscaleStatusHub` keys `(mangaId, chapterId)` → per-page
`PLANNED(backend) → RUNNING(backend) → DONE(backend, ms) | FELL_BACK(from→to, reason) |
FAILED(reason) | SKIPPED(reason)`, plus aggregate per-backend served counters and an events
`Flow`. The HUD badge, the settings live panel, the stats, and the achievement observer are all
views on this one hub.

## 7. Models (`upscale.model`)

Absorbs `UpscaleModelManager` (manifest validation, size + SHA-256 verify, throttled re-verify —
all preserved). Adds: per-model readiness for the settings UI, auto-download on first native
enable, catalog expansion in `upscale_models.json` (3×/4× Real-CUGAN/ESRGAN where real artifacts
can be hashed), and user-imported ONNX — validated by probing an ORT session load under a size
cap, stored in `filesDir/upscale_models/imported/`, listed as `imported-<name>`, deletable.

## 8. Reader seam & app integration

- `ReaderUpscaler` (object, successor of `UpscaleReaderHook`): `isUpscaleActive` +
  `upscaleDisplayBytes`, delegating to the controller. Pager/webtoon holders and the WebGPU
  `UpscaleExtension` call it; `ReaderTranslation.needsOriginalBytes` consults it.
- HUD: `UpscaleStatusOverlay` beside `MtlTranslationOverlay` in `ReaderActivity` — a small chip
  showing the current page's backend/latency/fallback, with its own pref toggle.
- Manga details: `UpscaleMangaToggle` rebuilt on the controller — no MTL gate; subtitle shows the
  effective mode/backend.
- Settings: rebuilt screen with mode/backend/model/factor/prefetch/tile/cache/defaults/imports/
  diagnostics + per-model download rows + live status panel. Registered in the settings catalog.
- Achievements: `UpscaleAchievementObserver` subscribes to hub events and unlocks
  `upscale_first`, `upscale_vulkan`, `upscale_npu`, `secret_upscale_4k`, rotating tiers — the
  spine never hears about achievements.

## 9. Migration & cutover

- Prefs keys unchanged (`pref_upscale_*`, `upscale_manga_$id`); new keys added for the new
  features. The translation gate and `IS_NOMTL` forcing disappear by construction.
- Old cache and model files keep working (same paths, same key algorithm).
- Deleted at cutover: the eight `ui/reader/setting/Upscale*.kt` files, the old route body, and
  `yakuyomi-engine`'s `NativeUpscaler.kt` / `OrtUpscaleSession.kt` / `src/main/cpp/`.

## 10. Testing

- Spine pure tests (repo harness): enablement precedence, plan ordering, factor clamps, cache
  identity stability (golden vector against the old algorithm), scheduler dedup/cancel, status
  transitions.
- `scripts/kotlinc-check/check.sh` over every new/changed file; `run-harness.sh` extended.
- Device checklist (user): cold start with models present, download mid-read, airplane mode,
  OOM device, WebGPU viewer with ArtCNN on/off, `nomtl` build, all three readers.
