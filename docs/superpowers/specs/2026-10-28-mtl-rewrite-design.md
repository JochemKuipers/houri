# MTL Ground-Up Rewrite — Design

**Date:** 2026-10-28
**Status:** Approved (user picked big-bang delivery)
**Delivery:** Big-bang — the whole new stack is built, then switched over in one cutover. Old code stays in the tree until cutover so behavior can be diffed and the tree stays green throughout (new code is additive until the final deletion).

## 1. Goals

Replace the entire MTL stack — orchestration, provider wiring, and the native vision pipeline — because the current code is unreliable, slow, hard to configure, and hard to change:

- **Reliability**: no stuck pages, no hangs, classified failures with explicit retry semantics.
- **Speed**: visible page first, per-provider concurrency, per-stage timeouts instead of per-page ones, prewarm.
- **Provider clarity**: one selection decision, a user-visible fallback chain, no silent fallbacks, cache identity computed by the spine (never re-derived).
- **Structure**: the framework *enforces* ownership boundaries in types; adding a provider is one class + one registration.

## 2. Core principle

**The local pipeline is itself a provider.** The spine knows `MtlProvider`s with capabilities; one built-in provider *is* the local composite (vision stages + a text model). Nothing in the spine special-cases "local vs cloud".

## 3. Architecture

```
┌─ app/ (wiring only) ─────────────────────────────────────┐
│  MtlController (facade)   reader seam   settings screens  │
└──────────────┬───────────────────────────────────────────┘
               │
┌─ mtl.* spine ─────────────────────────────────────────────┐
│  ProviderRegistry ── ProviderSelector (one decision site) │
│  ChapterQueue (priority, per-provider concurrency)        │
│  PageJob state machine (per-stage timeouts, cancellation) │
│  PageStore (content-hash + saved-page, spine-keyed)       │
│  StatusHub (Flows)   ContextStore (breadcrumbs, grounding) │
└──────┬─────────────────────────┬──────────────────────────┘
       │ MtlProvider SPI         │ VisionStageProvider SPI
┌─ built-in providers ────────┐ ┌─ native engine (submodule) ┐
│ local-gguf · cloud-llm      │ │ stage interfaces:           │
│ gemini-nano · ichigo-image  │ │ detect / ocr / inpaint /    │
│ + local composite provider  │ │ group / render, new models, │
│   (composes vision stages + │ │ native tiling, versioned    │
│    a text provider)         │ │ JNI boundary                │
└─────────────────────────────┘ └─────────────────────────────┘
```

### Module/package plan

| Module | Fate |
|---|---|
| New code | Package root `mtl.*`, living in the existing `yakuyomi-engine/` Gradle module during development |
| `exh.yakuyomi.*` (old, ~57 files) | Deleted wholesale at cutover |
| `yakuyomi-engine` module | Renamed `mtl-core` at cutover |
| `yakuyomi-stub` | Rewritten to mirror only the new app-facing facade; renamed `mtl-stub` at cutover |
| `external/yakuyomi-engine` | C++ rewrite (new architecture + new models); same submodule pointer mechanism |
| `llamatik-native` | Unchanged — consumed by the `local-gguf` provider |

**Build order** (= review sequence): spine + fake-provider tests → providers v1 → native engine v2 → local composite → wiring/cutover/delete.

## 4. Provider SPI (`mtl.core`)

Pure Kotlin — no Android imports. The spine's decision logic (selection, queue, state machine, cache identity, status) must stay unit-testable on the JVM.

```kotlin
@JvmInline value class ProviderId(val value: String)

enum class ProviderKind {
    TEXT_LINES,      // translates OCR'd lines (text + context in, lines out)
    VISION_PAGE,     // sees the page raster + lines (vision-capable LLM)
    FULL_PAGE_IMAGE, // image in → image out; bypasses the vision pipeline
}

data class ProviderCapabilities(
    val kind: ProviderKind,
    val sourceLangs: Set<String>,   // empty = any
    val targetLangs: Set<String>,   // empty = any
    val acceptsContext: Boolean,    // breadcrumb / glossary / manga grounding
    val maxConcurrency: Int,
    val costTier: Int = 2,          // 0 free-local … 3 premium
    val latencyTier: Int = 2,
)

sealed interface Readiness {
    data object Ready : Readiness
    data class Unavailable(val reason: String, val userMessage: String) : Readiness
}

interface MtlProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: ProviderCapabilities
    val readiness: StateFlow<Readiness>
    suspend fun open(): ProviderSession          // throws MtlException on failure
}

interface ProviderSession : AutoCloseable {
    suspend fun translate(request: TranslationRequest): TranslationResult
}
```

Requests are capability-shaped (`TextLinesRequest`, `VisionPageRequest`, `FullPageRequest`); results are `Lines`, `Regions`, `Page` (encoded webp), or `NothingToTranslate`. `TranslationContext` carries glossary, breadcrumbs, manga grounding; `PromptPolicy` carries prompt fingerprint + knobs.

Vision stages are providers too:

```kotlin
enum class VisionStage { DETECT, OCR, INPAINT }
interface VisionStageProvider {
    val stage: VisionStage
    val id: ProviderId
    val models: List<ModelRequirement>
    val readiness: StateFlow<Readiness>
    fun open(models: ModelFiles): StageHandle
}
```

The **local composite provider** binds one stage per `VisionStage` + one text provider into a `FULL_PAGE_IMAGE`-capable provider. Swapping in a new OCR model later (e.g. manga-ocr-class) is a new `VisionStageProvider`, not a spine change.

## 5. Selection policy (the one decision site)

```kotlin
data class SelectionPolicy(
    val mode: Mode,                    // AUTO or MANUAL(id)
    val fallbacks: List<ProviderId>,   // ordered, user-visible
)
```

- `ProviderSelector.chain(requirement)` returns the ordered providers able to serve a call: capability match (kind, langs, context) ∧ readiness `Ready`, manual pick first, then fallbacks, then (auto mode) ranked by `(costTier, latencyTier)`.
- A request walks the chain until one accepts. **No silent fallback**: the effective chain is logged and the status reports which provider served a page.
- Cache identity = `provider.id + model + langs + promptFingerprint`, computed once by the spine (`PageStore.keyFor`). The old bug class — two call sites deriving different identities for the same configuration — is designed out by making the identity a spine-owned value.

## 6. Page lifecycle (`mtl.sched`)

```
QUEUED → PREPARING (decode, validate, tile) → TRANSLATING (stage progress events)
       → RENDERING → STORING → DONE | SKIPPED(reason) | FAILED(classified, retryable?)
```

- Per-stage timeouts (prepare 10 s; provider call per provider; render 10 s; store 5 s) — replaces the current 90 s/600 s per-page bludgeon.
- Explicit cancellation checks at every boundary; chapter cancel is immediate.
- `ChapterQueue`: per-chapter page order, priorities (visible page > read-ahead), pending cap, pause/resume/cancel, and **per-provider concurrency** (`capabilities.maxConcurrency`): local = 1, cloud = N.
- Retryable classification drives the retry UI: `Timeout`/`RateLimited`/`ProviderUnavailable` retryable; `AuthRequired`/`ModelMissing` not.

## 7. Native engine contract (`external/yakuyomi-engine` v2)

- C++ pipeline with stage interfaces; the Kotlin boundary is one **versioned C API** over opaque handles (`mtl_engine_*`), wrapped by a thin `NativeEngine` Kotlin class.
- Stage results are per-region: box, mask, text, confidence.
- **Tiling of tall pages moves native-side** (engine owns slicing + stitching); Kotlin loses `LongPageStitcher` and the scattered `shouldSlice` checks.
- Pipeline-internal worker threads; detect/inpaint overlap OCR/translate via bounded queues.
- New models (modern detector, manga-ocr-class OCR, LaMa-class inpainting) land as new `VisionStageProvider`s — an app-side provider addition, not a spine change. Until the C++ v2 lands, the local composite adapts the *existing* `li.joye.yakuyomi.engine.*` sessions behind `NativeEngine`, so the cutover is not blocked on the C++ rewrite.

## 8. Providers v1 (parity list)

| Provider | Kind | Ported from |
|---|---|---|
| `local-gguf` | TEXT_LINES (+ vision when mmproj present) | `LocalLlm*`, `LlamaCppLlmBackend` |
| `cloud-llm` | TEXT_LINES (+ vision when image ≤ limit) | `YakuyomiTranslator` |
| `gemini-nano` | VISION_PAGE | `GeminiNanoTranslator` |
| `ichigo-image` | FULL_PAGE_IMAGE | `MangaTranslatorService` (+Auth/Fingerprint internals preserved) |
| `local-composite` | FULL_PAGE_IMAGE | new: vision stages + text provider |

Each provider contributes its own settings block (settings-framework keys co-located with the provider). Core prefs: enabled, target language, selection mode + manual pick + fallback chain, per-manga toggles, privacy gates (incognito/censor stay app-level). One-time migration maps old keys; old page caches are dropped (approved).

## 9. Errors

```kotlin
sealed class MtlException : Exception {
    ProviderUnavailable(id, reason)  // retryable
    AuthRequired(id)                 // not retryable
    RateLimited(id, retryAfter?)     // retryable
    StageTimeout(stage)              // retryable
    ModelMissing(models)             // not retryable
    QualityRejected(reason)          // retryable (e.g. empty/garbled output)
    Cancelled
}
```

`StatusHub` maps these to user strings (successor of `TranslationErrorMapper`). Offline fallback becomes an explicit policy (the chain), not a hidden flag.

## 10. Wiring & reader seam

- `MtlController` — the app-facing facade: gates, per-manga toggles, `translatePage` (Flow-based, cancellation-aware), `translateMangaInfo`, status observation, retry/cancel. Thin by construction: it delegates everything.
- Reader seam keeps the small `PageTranslator` interface; the new `MtlPageTranslator` implementation is registered from DI.
- `mtl-stub` mirrors only `MtlController`'s surface for the `nomtl` flavor.
- Settings screens are rebuilt from provider contributions; registered in `SettingsCatalog` + `SettingsMainScreen`.

## 11. Testing

- Spine pure tests (JUnit Jupiter + Kotest + MockK, `@Execution(CONCURRENT)`, backtick names): selector, queue ordering/concurrency/cancellation, state machine transitions + timeouts, cache identity stability.
- Provider tests: fake providers for the spine; MockWebServer for `cloud-llm`.
- C++ engine: its own harness + a golden-page region test (spec detail in the submodule).
- Device checklist before cutover: cold start, tall page, airplane mode mid-chapter, provider switch mid-chapter, model download mid-read, cancel mid-page, OOM-low-RAM device.

## 12. Non-goals

- Extension-APK providers (rejected — in-app plugin interface chosen).
- Keeping old public API or caches (free re-cut approved).
- Interactive region editing UI.
