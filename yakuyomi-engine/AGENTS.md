# yakuyomi-engine/ Module

On-device + cloud manga translation ("AI Translation" / MTL). Package root: `mtl.*` — the
spine lives in the **`:mtl-spine` module** (`mtl-spine/`), shared verbatim by both MTL flavors
with no source filters; this module keeps only `exh.yakuyomi.*` (native engine integration,
Machina service, MLKit/llama.cpp providers in `flavor/`); the native vision pipeline (`li.joye.yakuyomi.engine.*`:
Detector/Ocr/Inpainter/Grouping/Renderer/Pipeline) lives in `external/yakuyomi-engine`,
a git **submodule** (PineappleTwilight/houri-engine, branch `main`) — commit the submodule
pointer BEFORE the main repo. ABIs: arm64 + armeabi-v7a (x86 degrades to "not ready").

## Pipeline (per page)

detect (DBNet NCNN 1024) → OCR (ONNX 48px CTC) → group (union-find + MST) →
translate (LLM) → inpaint (AOT-GAN NCNN, overlaps translate) → typeset (Canvas).
Removal covers **all** detected regions; translation only the OCR-readable ones.
Never overwrite a good translation with a worse one (§11 rule).

## Architecture (the `mtl.*` spine)

All translation orchestration lives in `mtl.*` — a ground-up rewrite of the old
`exh.yakuyomi.TranslationManager` stack. The old stack is gone; do not resurrect it or fork it.

Three decisions are made exactly once. Do not re-derive them at a call site.

| Decision | Owner |
|---|---|
| Which backend translates (image service → Gemini Nano → local GGUF → cloud) | `mtl.select.ProviderSelector` (single decision site; MANUAL picks keep the user's pick) |
| Prompt / parse / align / offline-fallback protocol | `mtl.provider.Prompt.kt` — every text-shaped provider builds through `buildTranslationPrompt` |
| Which pages are translatable, and at what sample size | `mtl.provider.PageCodec`/`PagePipeline` (`PageImageValidator` still owns the limits) |
| Page lifecycle, caching and status | `mtl.sched.PagePipeline` / `mtl.store.PageStore` (disk: `DiskPageStore`) / `mtl.status.StatusHub` |

```
MtlBootstrap           assembles everything once; the app's only entry is `controller`
├── ConfigSource            (app: preferences → MtlConfig, incl. promptPolicy fingerprint)
├── PolicySource            (app: preferences → SelectionPolicy; the old "provider" pref maps
│                            to a MANUAL pick + ichigo→nano→local→cloud fallbacks)
├── PagePipeline            (mtl.sched) the per-page chain: prepare → detect → OCR → (group)
│                            → translate → inpaint → render → store; stage providers are SPI
├── MtlScheduler            (mtl.sched) chapter-ordered work queue, per-provider lanes
├── ProviderSelector        (mtl.select) registry + selection
└── StatusHub               (mtl.status) per-page progress for UI/settings
```

Flavor seam: app wiring passes `mtl.provider.MtlComponents` (providers + stage lists + resolver).
The `mtl` flavor binds `mtl.provider.RealMtlComponents` (real providers + native stages); the
no-MTL flavor (`yakuyomi-stub`) binds an empty `NoMtlComponents` — gates still run, pages fail
cleanly, never half a pipeline. Both flavors ship a `mtl.provider.MtlComponentsContainer`
`@BindingContainer`; the app graph binds exactly one.

Layout note: the spine module `:mtl-spine` (`mtl-spine/src/main/kotlin/mtl/`) is compiled into
both flavors by dependency, not by source filtering — do not move `mtl.*` files into this module.
The mtl flavor-only files that need ML Kit (`GeminiNanoProvider`), llama.cpp
(`LocalGgufProvider`), the li.joye engine (`NativeVisionStages`) or the binding container
(`RealMtlComponents`, `MtlComponentsContainer`) live in `src/main/kotlin/flavor/` with
`package mtl.provider`. `yakuyomi-stub` depends on `:mtl-spine` and supplies its own empty set.
The flavor swap point is unchanged: app → `:yakuyomi` | `:yakuyomi-stub`, each `api()` the spine.

Backends are thin providers implementing `mtl.core.MtlProvider`. Adding one means: a class in
`mtl/provider`, registration in `RealMtlComponents.providers()`, and (if it needs an
AppId-scoped display in settings) the `SETTINGS_NANO_ID`-style id probe in the settings screen.

## Extending

**A new text provider**
1. Implement `mtl.core.MtlProvider` (see `mtl/provider/LocalGgufProvider.kt` — thin wrapper over
   the runtime). Hand raw output to `mtl/provider/Prompt.kt` for parse/alignment — do **not**
   re-implement prompt building or line parsing.
2. Register it in `RealMtlComponents.providers()` in your preferred order (registration order is
   the selector's tie-break).
3. The readiness flow drives the settings screens' "Available / Not available" copy; the
   `MtlController.providerReadiness` / `refreshProviderReadiness` accessors are the settings UI's
   probe point — no flavor-specific code in app modules.
4. Cache identity comes from the pipeline (`MtlConfig.promptPolicy.fingerprint` + provider
   `modelId()`) — never re-derive it at the call site.

**A new engine tuning knob**
1. Map it in `EngineConfigFactory.defaultConfig()`.
2. Add it to `EngineConfigFactory.configChanges()` **in the same edit**, or the native session will
   not rebuild and the setting will silently do nothing until the app restarts. Render settings are
   the exception: they are read per page and need no entry.

**A new page-level rule** (limit, sample size, encoding) belongs in `PageImageValidator` or
`PageImageEncoder`, not inline in the manager.

## Facades and their collaborators

Two former god classes are now facades. **Their public API did not change** — `app/` reaches them
through `globalAppGraph`, and `yakuyomi-stub/` mirrors the same surface for the `nomtl` flavor, so
every public member stays on the original class and delegates. The collaborators are `internal` and
held `private`, so nothing outside the module can see them; a split must not widen an API.

`MangaTranslatorService` (466 lines, 10 public) — Ichigo image translation
- `MangaTranslatorFingerprint` — `clientUuid`, `fingerprint`, `buildFingerprint`,
  `deviceMemoryBucket`, `screenInfo`, `canvasHash`, `hashString`. Depends on `prefs` + `context`.
  It fabricates a **fixed** web-client profile (WebGL, connection, browser capability, touch,
  orientation, perf) so the service sees a browser. The strings are fixed on purpose — consistency
  matters more than describing the real device — so a partial edit here changes the identity the
  account was registered with.
- `MangaTranslatorAuth` — token storage, `refreshAccessToken`, `login`, `signup`, `logout`,
  `clearAuth`, `isLoggedIn`, `getCurrentUser`, and the two header builders. Takes `baseUrl` and the
  retry helper as lambdas: it posts to `baseUrl()/auth/*`, and the retry helper must reach the
  stored token, which is the whole point of retrying with a refreshed one.
- The service keeps `baseUrl` / `isPrivateHost`, the `allowedTargetLangs` / `allowedModels`
  allowlists and their sanitisers, the translation path, and `executeWithAuthRetry`.

`LocalLlmManager` (141 lines, 24 public) — on-device GGUF provider
- `ImportedGgufModels` — the custom-model directory, `importGguf`, and its file mechanics. An
  import stages into a `.tmp` and drops it on **any** failure: a partial file is invisible to
  `importedModels` (which lists only `.gguf`) yet still counts against the storage check that gates
  the next import, so a leak strands the user with no way to see or clear it.
- `LocalLlmSampling` — the per-model override map, stored as one JSON blob under a single pref.
- `LocalLlmSession` — the llama.cpp backend, its mutex, and running/loading state. Takes
  `resolveModel` as a lambda rather than resolving for itself.
- The manager keeps model *selection* (`resolveModel`, `modelById`, `isModelReady`,
  `isLocalProvider`) because it is the one decision all three read from, plus the download
  passthroughs. The public `running` / `loading` / `importing` are typed `StateFlow<Boolean>`, not the
  collaborators' own types.

`OrtUpscaleSession` (301), `ModelManager` (405) and `LocalLlmDownloadManager` (333) were checked and
are each already single-concern; do not split them for line count alone.

## Key classes

| Class | Role |
|---|---|
| `mtl.MtlController` | App-facing facade: enabled/gated/per-manga checks, provider readiness probes, manga-info translation |
| `mtl.MtlBootstrap` | Assembly: registry/selector/pipeline/scheduler/status from the app's seams |
| `mtl.sched.PagePipeline` / `MtlScheduler` | Per-page chain (prepare → detect → OCR → group → translate → inpaint → render → store) and the chapter work queue |
| `mtl.select.ProviderSelector` / `ProviderRegistry` | The one provider decision, with MANUAL-pick semantics and registration-order tie-break |
| `mtl.store.PageStore` / `DiskPageStore` | Content-addressed cache + saved-page layer (persistence policies: NEVER / WHILE_READING / AUTO) |
| `mtl.status.StatusHub` | Per-page states for UI and settings |
| `mtl.core.MtlProvider` / `ProviderSession` | The provider SPI — implement this for a new backend |
| `mtl.provider.RealMtlComponents` | Binds the real providers + native stages for the `mtl` flavor |
| `mtl.provider.{CloudLlm,GeminiNano,LocalGguf,Ichigo}Provider` | Cloud LLM, on-device ML Kit GenAI, llama.cpp GGUF, Ichigo image service |
| `PageImageValidator` | Page size/dimension limits and the decode sample size |
| `PageImageEncoder` | bitmap → JPEG for vision, WEBP for output and cache |
| `YakuyomiEngine` | Native session lifetime and dispatch; nothing else |
| `EngineConfigFactory` | prefs → config, typeface, text colour, orientation, rebuild triggers |
| `LongPageStitcher` | Tall-page slicing, stitching, stats summing, region offsetting |
| `NativeComponents` | The detector/OCR/inpainter sessions as one unit |
| `LocalLlmManager` / `LlamaCppLlmBackend` | Local GGUF via llama.cpp; per-model sampling overrides |
| `MangaTranslatorService` / `MangaTranslatorAuth` / `MangaTranslatorFingerprint` | Ichigo image translation; facade over session + device identity |
| `LocalLlmAccelerator` | Probes whether `gpuLayers` can actually offload (GPU backend built in? Vulkan compute?) |
| `LocalLlmDownloadManager` | Resumable GGUF+mmproj downloads (Range resume, throttled emits) |
| `ModelManager` | Downloads detector/OCR/inpainter models |
| `BreadcrumbNotes` | Cross-page consistency notes |
| `TranslationPreferences` | ~54 prefs (engine tuning, per-provider keys); stub duplicate in `yakuyomi-stub/` |
| `mtl/provider/Prompt.kt` / `mtl.core.PromptPolicy` | Prompt text, sanitisation, line alignment, JSON parsing, policy fingerprinting |
| `TranslationException` | Thrown by the Ichigo service for distinguishable failure classes |

## Gotchas

- ML Kit beta4 API differs from docs: `Generation.getClient()` → `GenerativeModel`;
  `checkStatus(): Int` (0–3); `download(): Flow<DownloadStatus>`; request via
  `generateContentRequest(ImagePart, TextPart) { temperature; maxOutputTokens }`.
- llama.cpp backend: `updateGenerateParams(...)` MUST be called or models output nothing;
  wrap prompts with `applyChatTemplate`.
- The llama.cpp runtime is **not** the `com.llamatik:library` AAR — it is built from the
  `external/llamatik` fork by `:llamatik-native` (see `README-local-llm.md`). The upstream AAR
  is CPU-only, so its `gpuLayers` offloads nothing.
- `external/llamatik` is a submodule with **nested** submodules: use
  `git submodule update --init --recursive external/llamatik` or CMake aborts.
- GPU offload (ggml Vulkan) is opt-in via `-Pmtl.gpuOffload=true`. It needs Vulkan-Hpp +
  SPIRV-Headers (the NDK has `glslc` but not those) and compiles against **API 29+**, because
  ggml-vulkan needs a Vulkan 1.1 core symbol the NDK's `libvulkan.so` stub only exports from
  there. Stripped cost: 6.6 MB/ABI CPU-only vs 64.2 MB/ABI with Vulkan.
- llama.cpp never fails an offload it cannot perform — it logs
  `compiled without support for GPU offload` and runs on the CPU. `LlamaCppLlmBackend` therefore
  forces `gpuLayers` to 0 unless `LocalLlmAccelerator` says offload is real.
- Renderer caps translated text at the original glyph size (`originalFontSize` = median
  line-quad thickness, measured on `TextLine.tightQuad` — the **pre-unclip** rect, since
  DBNet's 2.3x unclip inflates a 30x200 line to ~90x260); `RenderConfig.fixedTextColor` +
  `"fixed"` colorMode override it.
- Inpainting removes **glyph strokes**, not region boxes: `buildSegMask` intersects DBNet's
  stroke mask with the region boxes. Masking whole boxes hands AOT-GAN a hole several times
  the glyph area, which returns as a flat light patch over artwork.
- The **removal** set is every detected region (m-i-t parity — unreadable text is erased too,
  so no raw source script survives beside the English); the **translation** set is only regions
  with readable OCR. Removal's per-region box fallback is gated on readable text, so false
  detections are not blanketed out of the artwork.
- `colorMode="fixed"` resolves outline == fill on light backgrounds, so the renderer must skip
  the outline there — stroking black-on-black emboldens glyphs and closes their counters.
- Settings UI: `SettingsYakuyomiScreen` (+ LLM/engine advanced screens); Gemini Nano toggle
  + live status row; per-model sampling sliders hidden without a model.
- Prompt/parse helpers are shared — do not fork per-provider copies.
- Fork markers: `// KMK -->` for Komikku additions.
