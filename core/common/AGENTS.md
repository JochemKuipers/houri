# core/common/ Module

Foundation module providing networking, security, storage, and shared utilities. No Android framework dependencies except `Context`.

## Network layer

**`NetworkHelper`** – OkHttp client factory with:
- Interceptor chain: `UncaughtExceptionInterceptor` → `UserAgentInterceptor` → optional logging → DoH resolver
- `CloudflareInterceptor`: WebView-based challenge solving (30s timeout)
- KMK: MangaDex cover interceptor, download-with-resume (exponential backoff)
- 12 DNS-over-HTTPS providers in `DohProviders.kt`

**Key files:**
- `Requests.kt` – GET/POST/PUT/PATCH/DELETE builders
- `OkHttpExtensions.kt` – `Call.await()`, `Response.parseAs<T>()`, progress tracking
- `NetworkPreferences.kt` – DoH provider, user agent config

## Security

**`SecurityPreferences`** – Biometric lock, secure screen, DB/CBZ encryption (SY)

**`CbzCrypto`** (in core:archive) – AES-256/128/ZipCrypto via Android KeyStore

## Preferences framework

**`tachiyomi.core.common.preference`** — 100% SharedPreferences, no Jetpack DataStore:

| Type | Role |
|---|---|
| `PreferenceStore` | Factory interface (`getBoolean/getInt/getString/…`, `getEnum`/`getLongArray` extensions) |
| `Preference<T>` | Reactive value (`get/set/changes()/stateIn()`); self-heals on type mismatch |
| `AndroidPreferenceStore` | Production impl (`@SingleIn @Inject`, default SharedPreferences + keyFlow) |
| `InMemoryPreferenceStore` | Tests/previews |
| `SettingKey<T>` | Single source of truth: exact storage key + default (new code, replaces inline literals) |
| `SettingHost` / `SettingsRegistry` | Feature key contributions; fail-fast duplicate detection; `isBackedUp()` mirrors backup filtering |

**Preference key conventions:**
- `__PRIVATE_` prefix: Excluded from backups
- `__APP_STATE_` prefix: Internal app state, not user-facing

Feature classes (`*Preferences`, one `fun foo() = store.getX(key, default)` per setting) and UI
hosts both reference the same `*SettingKeys` consts — keys must never drift. UI wiring:
`app/.../presentation/more/settings/framework/AGENTS.md`. Never rename a key without a
migration in `mihon/core/migration/migrations/`.

## Storage

**`FolderProvider`** interface + `AndroidStorageFolderProvider` (`/storage/emulated/0/<app_name>`)

**`DiskUtil`** – Disk space, filenames, `.nomedia`, media scan, hash

**`UniFileExtensions`** – `.extension`, `.nameWithoutExtension`, `.displayablePath`

## Shared utilities

| Utility | Purpose |
|---------|---------|
| `CoroutinesExtensions.kt` | `launchUI`, `launchIO`, `withIOContext` |
| `RxCoroutineBridge.kt` | RxJava 1 ↔ coroutines bridge |
| `ImageUtil.kt` | Image detection, splitting, rotation, background color |
| `Hash.kt` | MD5, SHA-256 |
| `StringExtensions.kt` | `chop()`, `truncateCenter()`, natural sort |
| `QuerySanitizer.kt` | Search query normalization |
| `UrlUtils.kt` | URL classification (online/local/embedded) |
| `LogcatExtensions.kt` | `logcat {}` wrapper |
| `Localize.kt` | Moko Resources string resolution |

## Device memory (`util/system/DeviceMemory*.kt` + `AppMemoryMonitor.kt`, KMK)

The one place that answers "what is this device, and how much room is left right now". Three files,
split so the policy can be asserted offline:

| File | Owns | Testable offline |
|---|---|---|
| `DeviceMemoryClass.kt` | `DeviceTier` + the fold (`deviceTierFor`: RAM, vendor low-RAM mark, 32-bit ABI cap) | yes — `DeviceMemoryHarness` |
| `DeviceMemoryMath.kt` | `MemoryPressure`, headroom, tier ceilings, `shouldDowngradeToRgb565`, `jxlMaxDimension` | yes — same harness |
| `AppMemoryMonitor.kt` | `ActivityManager` reads, the published `Snapshot`, the RAM-gate bypass | no (Android) |

**Who samples, and when.** `AppMemoryMonitor.sample(context)` is the only method that touches the
system (a binder call). The image-loader factory — `App.newImageLoader`, invoked by Coil when the
first image is requested — calls it once to *prime* the snapshot. `sampleIfStale(context)` is the
per-image entry point — Coil's decoders and `ImageUtil.lowRamJxlMaxDimension` — and answers from the
published reading when it is younger than one second, re-reading only when it is not. That bounds the
live answer to one system read a second across all decodes and zero when the app is idle.

**There is no read-without-sampling accessor, on purpose.** A draft had `snapshot(context)` that never
sampled; the decoders' pressure checks then read a startup reading for the whole session — dead code
wearing a live API's clothes. If you find yourself wanting a free read on a hot path, the question to
ask is what decision the read is for: if the answer is "per image", use `sampleIfStale`; if it is "once
at startup", call `sample` there and keep the result.

**The static half is resolved once.** `totalMem`, the vendor low-RAM mark and the ABI do not change
during a process, so `static(context)` caches them behind a double-checked lock. Only the pressure
is re-read.

**Consumers, so a change here knows its blast radius:**

| Consumer | What it reads |
|---|---|
| WebGPU reader (`viewer/webgpu/`) | tier + pressure for its own decoded-page/tile ceilings |
| Coil (`App.newImageLoader`) | `imageCacheBytes`, `fetchParallelism` (the startup prime) |
| Coil decoders (JXL/AVIF/HEIF) | `sampleIfStale` → `shouldDowngradeToRgb565` |
| `ImageUtil.lowRamJxlMaxDimension` | `sampleIfStale` → `jxlMaxDimension` |
| MTL / WebGPU RAM gates | `isMtlSupported` / `isWebGpuSupported` (tier ≥ MEDIUM) |
| `DeviceUtil.isLowRamDevice` | the tier, for legacy callers |

**Do not reintroduce a device-size boolean.** `totalMem < 3GB` was the old answer everywhere, and it
was wrong in both directions: a 4GB phone the OEM marked low-RAM looked healthy, and a 12GB phone
that had gone TIGHT looked fine. Everything asks the tier or the live pressure instead.

**`is64Bit` and the 32-bit cap.** A 32-bit process shares ~3GB of address space across the Java heap,
the native decode buffers and the GPU's allocations, so `deviceTierFor` caps it at SMALL. That also
pins decode parallelism to one worker, which is the real reason the cap exists.

## Logging

- **KMK code:** `xLogE()` / `xLog()` from `exh.log` (XLog with safe pre-init fallback)
- **Mihon code:** `logcat {}` from `tachiyomi.core.common.util.system`
- **NEVER** use raw `android.util.Log`

## Conventions

- No Android framework dependencies except `Context`
- `InMemoryPreferenceStore` for tests/previews
- Fork markers: `// KMK -->` for Komikku additions
