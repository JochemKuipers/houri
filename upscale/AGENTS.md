# upscale/ Module (`:upscale`)

The standalone upscaling pipeline — the MTL-rewrite pattern applied to upscaling. **Not
flavor-gated**: `nomtl` builds get native inference too, because nothing here touches the
translation engine. One module, two halves: a pure-Kotlin spine (`upscale.*`) and the native
backends it drives.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/kotlin/upscale/core/` | SPI surface: `UpscalerBackend`, `UpscaleRequest`, `UpscaleOutcome`, `Readiness`, `ReaderKind`, enums (`UpscaleMode`, `BackendPref`, `ModelFamily`, `UpscalePreset`, `SimpleAlgo`) |
| `src/main/kotlin/upscale/policy/` | `UpscalePolicy.plan(config, PolicyInput)` — THE single decision site: gates, scale clamping, backend ordering, fallback composition. `UpscaleConfig` is the immutable pref snapshot |
| `src/main/kotlin/upscale/identity/` | `UpscaleIdentity` — cache keys. **Byte-compatible with the old `UpscaleCacheManager.cacheKeyDetailed` v3 scheme on purpose**; changing it orphans the whole cache (bump `KEY_VERSION` only with a migration plan) |
| `src/main/kotlin/upscale/status/` | `UpscaleStatusHub` — per-chapter page state machine (`PLANNED→RUNNING→DONE/FELL_BACK/FAILED/SKIPPED`), totals, `Event` flow (controller emits `Served`/`Failed`, not the mark* methods) |
| `src/main/kotlin/upscale/model/` | `UpscaleModelManager` — manifest (`app/src/main/assets/upscale_models.json`), verified downloads, ORT-probe-validated ONNX imports |
| `src/main/kotlin/upscale/native/` | `NcnnUpscalerBackend` (JNI), `OrtUpscalerBackend` (ONNX Runtime NNAPI/CPU), `SimpleUpscalerBackend` (resample), `DeviceProbe`, session base class |
| `src/main/cpp/` | `upscale_jni.cpp` — moved verbatim from yakuyomi-engine, symbols renamed `Java_upscale_native_NcnnUpscaler_*`. NCNN prebuilts still come from the external/yakuyomi-engine submodule |
| `app/.../upscale/app/` | App adapter: `UpscalePreferences`/`UpscaleMangaStore` (pref keys byte-identical to the old stack), `PrefsUpscaleConfigSource`, `UpscaleBootstrap` (assembly), `UpscaleBindings` (Metro container), `UpscaleReaderBridge` (reader seam), `UpscaleAchievementObserver`, `UpscaleGpuBridge` (ArtCNN registration) |

## Rules

- **The spine never reads a pref store or an Android class.** `UpscaleConfig` + resolver
  lambdas in, plan out; that is what makes the policy assertable offline
  (`scripts/kotlinc-check/run-harness.sh UpscaleSpineHarness`, 46 assertions).
- **Plans are chains, not choices.** `plan()` returns every step that MAY serve the page
  (e.g. VULKAN→NPU→CPU→Simple); the executor records demotions and falls through. Never
  collapse to one step at plan time — availability is per-session and racy.
- **`RENDER_TILE` backends are display quality, not page pipelines.** The WebGPU ArtCNN
  sampler registers via `UpscaleGpuBridge` (settings/status see it) but byte plans never
  include it; `UpscaleController.useGpuTileSampler()` is the only consultation point.
- **MTL is decoupled by design.** `UpscalePreferences` has no `TranslationPreferences`
  dependency and no MTL gate; the per-manga toggle on MangaScreen shows whenever the global
  switch is on. Do not reintroduce the gate.
- **Enablement ladder order:** global switch → per-manga store → category overrides (any
  opt-in wins, else explicit opt-out) → default-for-new-entries. Lived in the spine so it is
  one implementation.
- **Cache identity:** `nativeKey` includes factor + modelId + preset + backend + format +
  artifact hashes; `simpleKey` includes algo + mode. Model/backend changes self-invalidate.
- JNI work: check `upscale/src/main/cpp/CMakeLists.txt` — the NCNN archives resolve from
  `external/yakuyomi-engine/engine/src/main/cpp/ncnn`; there is deliberately still only one
  NCNN checkout.
