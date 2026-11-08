# App structural rewrite — plan

**Date:** 2026-11-05 · **Branch:** `app-efficiency-rewrite` · **Worktree:** `app-efficiency-rewrite`

## Goal

Every area of the `app` module (plus app-side wiring in other modules where mangled) becomes:
structured (clear layering), delegated (no god-objects — controllers/collaborators own concerns),
encapsulated (state and config not reachable from everywhere), and dynamically configured
(named, typed config instead of scattered magic constants).

Behavior-preserving. No user-visible change except where a refactor incidentally fixes a bug
(those go to RELEASE_NOTES; pure internal refactors do not).

## Out of scope (frozen)

- **Upscaler** — worktree `focal-pangolin` (`feat/upscale-pipeline-rewrite`) owns it. Frozen:
  `ui/reader/setting/Upscale*.kt`, upscale call sites inside `webgpu/`/`PagerPageHolder`/
  `WebtoonPageHolder`/`ReaderTranslation`/`MangaScreenConstants`, `SettingsUpscalerRoute.kt`,
  upscale sections of `ReaderPreferences.kt`/`SettingsReaderRoute.kt`/`SettingsCatalog.kt`,
  `upscale/` module + its `settings.gradle.kts` line.
- **Submodules** (`external/*`) unless wiring they missed.
- **Extension API** (`source-api` ABI, extension sources) — upstream standard.
- Legacy Injekt *registration* survives per root AGENTS.md (bridge keeps); do not extend Injekt.

## The pattern being imposed (established in P1, then reused everywhere)

**Controller delegation.** A god ViewModel/Activity/composable keeps: state holders + a thin
public intent surface. Each concern moves to a collaborator class co-located in the same
package (`LibraryFilterController`, …) that receives its dependencies via constructor and
exposes a narrow API. Precedent in-repo: `MangaInfoTranslationController` (MTL rewrite).
Rules:
- Controllers never hold Android `Context`; pass what they need (prefs, repos, scopes).
- ViewModel keeps `StateFlow` composition + public intents that delegate 1:1.
- Compose screens take state + lambdas, never touch the VM's internals.
- Magic constants (durations, thresholds, sizes, keys-strings) move to a co-located
  `*Config`/companion constants object with a doc comment.

## Phases (ordered to keep upscale collisions at zero until the end)

- [x] P0 — this plan.
- [ ] P1 — Library: `LibraryViewModel` (2433) split; `LibraryTab` (582); `presentation/library`.
- [ ] P2 — Manga details: `MangaViewModel` (2317), `MangaScreen.kt` (2092+1115 ui), `MangaInfoHeader`
      (1171), `TrackInfoDialog` (1568), `EditMangaDialog` (640), scanlator screen.
- [ ] P3 — Settings screens → modular framework (`SettingKeys`+`SettingsHost`+`GenericSettingsScreen`):
      `SettingsEhRoute` (1258), `SettingsYakuyomiRoute` (1242), `SettingsAdvancedRoute` (1082),
      `SettingsDataRoute` (1071); `SettingsReaderRoute` only non-upscale sections.
- [ ] P4 — Browse & lists: `BrowseSourceViewModel` (831), `ExtensionsScreen` (720), Updates/History/
      Downloads VMs+screens, `CategoryDialogs` (903), `TrackInfoDialogHome` (818).
- [ ] P5 — Infrastructure: `LibraryUpdateJob` (990), `Downloader` (852), `DownloadCache` (626),
      `NotificationReceiver` (682), `DiscordRPCService` (653), backup restorers, sync.
- [ ] P6 — exh: `EHentai.kt` (1660), favorites, metadata UI, recs UI, smart search.
- [ ] P7 — Reader (non-upscale): `ReaderActivity` (2130), `ReaderViewModel` (2244),
      `NovelWebViewer` (2605), `NovelTypographySettingsPage` (1375), `NovelTypographyPreferences`,
      pager/webtoon viewers (non-upscale parts). WebGpu* only where upscale-untangled.
- [ ] P8 — Shell & DI: `MainActivity` (977), `App.kt` (643), `AppGraph` wiring hygiene,
      Injekt keeps audit.
- [ ] P9 — Final sweep: domain interactors dedup, `util/` consolidation, remaining >500-line files,
      AGENTS.md updates for every changed area, kotlinc harness green.

## Verification per phase

- `scripts/kotlinc-check/check.sh <touched files>` after each file restructure (only allowed
  build-adjacent command; expect only known unresolved-receiver cascades for Compose-heavy files).
- User runs `spotlessApply` → `spotlessCheck` → `assembleDebug` per phase.
- Each phase ends with: summary of what moved where, AGENTS.md touch-ups, note update.

## File map (filled per phase)

See ctx_note "App structural rewrite — area tracker" for live status.
