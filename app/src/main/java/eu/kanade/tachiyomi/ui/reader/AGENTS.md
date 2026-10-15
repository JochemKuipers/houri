# ui/reader/ — Reader

Fullscreen reading surface. Deliberately a classic `Activity`, NOT a Voyager `Screen` (fullscreen, `singleTask`, S-Pen remote actions, container transforms).

## Entry

- `ReaderActivity.kt` — `singleTask` Activity. `ReaderActivity.newIntent(context, mangaId, chapterId, page)`.
  Uses AndroidX `viewModels<ReaderViewModel> { graph.viewModelFactory }` + `graph.inject(this)` with
  `@Inject lateinit var` fields. `ReaderViewModel.kt` holds chapter/page state (not a Voyager ScreenModel).

## Key directories

| Path | Purpose |
|------|---------|
| `viewer/` | `Viewer.kt` + `ViewerConfig.kt` contracts; `pager/` (strip) and `navigation/` (webtoon/LTR/RTL/vertical) viewers; `ReaderPageImageView`, progress/transition views |
| `viewer/PageTranslator.kt`, `viewer/ReaderTranslation.kt` | MTL translation overlay hookup — engine itself lives in `yakuyomi-engine/` (see its AGENTS.md) |
| `loader/` | `PageLoaderFactory` → `PageLoader`: `HttpPageLoader`, `ArchivePageLoader`, `DirectoryPageLoader`, `DownloadPageLoader`, `EpubPageLoader` |
| `model/` | `ReaderChapter`, `ReaderPage`, `ReaderItem`, `ViewerChapters`, `ChapterTransition`, `InsertPage` |
| `setting/` | `ReaderPreferences`, `ReadingMode`, `ReaderSettingsScreenModel`, `ReaderOrientation`, `ReaderBottomButton` |
| `setting/Upscale*.kt` | On-device upscale stack (MTL-gated): `UpscaleReaderHook` → `UpscaleEngine` (+ `UpscaleBackendDetector`), `UpscaleModelManager`, `UpscaleCacheManager`, `UpscaleMangaStore`, `UpscalePreferences` |
| `chapter/`, `loader/` | `ReaderChapterItem`, chapter loading pipeline |

## WebGPU viewer (`viewer/webgpu/`)

`WebGpuViewer.kt` is wiring only — fields, pager callbacks, chapter swap, navigation. Everything with
a state machine or a retry ladder is a top-level extension beside it, one concern per file:

| File | Concern |
|------|---------|
| `WebGpuViewer.kt` | the class: fields, `fetchPage`/`onPageChange` wiring, `setChapters`, navigation, lifecycle |
| `WebGpuPageModel.kt` | `ViewerPage` hierarchy + split-aware `prev`/`next` neighbour links |
| `WebGpuPageCache.kt` | page residency, eviction policy, `fetchPage` resolution, split reconciliation (`syncPageList`) |
| `WebGpuDecodeQueue.kt` | what the worker owes a page (`queueForDecode`) + the stuck-page retry ladder |
| `WebGpuDecodePipeline.kt` | bytes → GPU image for one page |
| `WebGpuViewerWorkers.kt` | the three long-lived coroutines (decode worker, liveness sweep, progress spinner) |
| `WebGpuViewerImageState.kt` | preferences → filters, transition, zoom, HUD |
| `WebGpuSpread.kt` / `WebGpuSpreadHeightMatch.kt` | dual-page pairing / height matching |
| `WebGpuZoom.kt`, `WebGpuExif.kt` | per-page presentation, reapplied after every decode and every image swap |
| `WebGpuPlaceholderPages.kt` | `ProgressPage` / `ErrorPage` / `TransitionPage` — drawn procedurally |
| `WebGpuRestore.kt` | deferred reading-position restore, deferred until the target page has real heights |

The render engine is a git **submodule** (`external/webgpuviewer-houri`, `:webgpuviewer`); the app
never fetches images itself — it hands the library an `ImagePage` per page and the library draws it.

**Read `viewer/webgpu/AGENTS.md` before debugging anything in this folder.** It holds the thread
model, the page state machine, the stuck-page ladder, the device-recovery rule (an adapter is
consumed and cannot be reused), a symptom→cause table, and the landmines that have already cost
an incident each.

## Novel / text reader (`viewer/novel/`, KMK)

Renders text chapters (EPUB, web novel) in a WebView rather than in a native text widget. Chosen
because an EPUB *is* an XHTML document with the publisher's own stylesheet — drop caps, ruby,
columns, vertical writing, tables. Re-implementing that in Compose means re-implementing a browser.
The reader's preferences are layered on as an injected stylesheet; the document keeps its own.

| File | Concern |
|------|---------|
| `NovelWebViewer.kt` | The WebView, resource interception, and the `ViewerProvider` that selects it |
| `NovelTypography.kt` | The settings value. No Android imports, so it is unit-testable |
| `NovelTypographyCss.kt` | Turns that value into a stylesheet; `applyTo` is idempotent |
| `NovelProgress.kt` | Reading position and percentage progress |

**Tri-state preferences.** `justified`, `hyphenation` and `verticalWriting` are `Boolean?` where
`null` means *follow the publisher*, stored as the string `"follow"`. They are nullable on purpose: a
default of `false` would mean "emit `text-align: left !important`", which silently restyles a
Japanese light novel out of vertical writing and cannot be turned off again.

**The stylesheet is never empty.** Even for default typography, because that is the only way a user
can turn *off* a publisher's own justification. Properties that default to "follow the publisher" are
simply omitted when null.

**`!important` everywhere on purpose.** An EPUB stylesheet sets `font-size` on `body` or a class; on
specificity grounds those win over a host rule and the user's font size silently does nothing. This is
the single most common complaint about EPUB readers.

**Progress is a character offset, not a page index.** A page index in a reflowable document means
something different on every device and at every font size; `NovelProgress.Position`
(`documentIndex:characterOffset:characterLength`) is stable across all of them. Chapters are weighted
by character length so a book with 200 one-paragraph interstitial files does not read as 2% done.

**Viewer selection is by content, not by source id.** `NovelViewerProvider` picks itself when the
loaded chapter's pages carry `textPage`. An extension therefore gets this reader the moment it
implements `TextSource`, with no change here.

Resources (images, CSS, fonts) are streamed by the `TextPageLoader`'s `resourceSource` over the
reserved `epub-archive://book.internal/` scheme — see `source-epub/AGENTS.md`.

## Page splitting

A page too tall for the decoder is cut into segments by `HttpPageLoader` and **replaces** its parent
in the chapter's list, mid-session. `ReaderChapter.pageListVersion` is how the viewers learn; the
mechanics are in `model/SplitPageIndex.kt` (`internal`), `ReaderChapter.replacePages` (identity
indexes) and `WebGpuPages`→`WebGpuPageModel.linkedNeighbour`. Anything that walks the page chain
must handle a page that is no longer in the list.

## Pager / webtoon split

Strip and continuous reading use separate image processors (split 2026-08-31):
`PagerPageImageProcessor.kt`, `PagerJoinedItemsBuilder.kt`, `WebtoonPageImageProcessor.kt`.
`WebtoonBaseHolder.viewer` is public for cross-holder access. Keep the two paths separate.

## Komikku additions (KMK)

`ChapterCompleteSoundPlayer.kt` + `ChapterCompleteSoundPackManager.kt` (chapter-finish audio),
`WebhookReadingTimeTracker.kt` (reading-time webhook events). New KMK strings → `KMR` + `i18n-kmk/`.

## Conventions

- Reader stays an `Activity` — do not migrate it into Voyager.
- State in `ReaderViewModel` (AndroidX); inner screens use Voyager ScreenModels, not the reverse.
- Upscale changes stay behind the MTL gate; per-series toggles via `UpscaleMangaStore`.
- Logging: `xLogE()` / `xLog()` from `exh.log` for KMK code; `logcat {}` for Mihon code.

## Anti-patterns

- Do not launch the reader through a Voyager `Screen` push — use `ReaderActivity.newIntent(...)`.
- Do not merge the pager and webtoon image processors back into one path.
- Do not grow `WebGpuViewer.kt` past wiring — a new state machine belongs in its own file as a
  `WebGpuViewer` extension, so the class stays readable as a whole.
- Do not build an `ImagePage` and drop the reference without `cleanup()` — it owns uploaded GPU
  textures.
- Do not give a text preference a non-null `false` default. `null` = follow the publisher; `false` =
  force it, and it cannot then be unforced.
- Do not re-run `loadDataWithBaseURL` to apply a typography change — it drops the scroll position on
  every slider drag. Swap the stylesheet in the live DOM (`liveTypographyJs`).
