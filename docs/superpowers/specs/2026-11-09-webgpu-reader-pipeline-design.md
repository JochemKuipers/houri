# WebGPU reader pipeline refactor + bugfix pass (2026-11-09)

Scope: `app/.../ui/reader/viewer/webgpu/` + the two seams it owns in
`external/webgpuviewer-houri`. Goals: fix the two reported bugs (first-page
runaway, intermittent page-boundary line), rework the decode path into an
explicit stage pipeline with a stage listener, add smart (priority + direction)
loading, and harden reading-position restore.

## Bug 1 — "the first page runs away from the user when they scroll down"

Mechanism: a placeholder's slot height in the continuous walk is a **live
surface read**. `ProgressPage.height` answered `viewer.pager.state.height` and
`TransitionPage.height` answered `min(state.width, state.height)` on every
measurement. The walk measures slot heights fresh every frame, so every
undecoded page's slot silently re-derived from whatever the surface reported
*that frame*.

The reader's scroll bookkeeping (`scrollYInternal` consumed in slot units at
crossing time) is measured against a geometry that rewrites itself under it:

- At open the surface sizes 0 → H₀ → H₁ as layout and insets settle.
- Hiding the menu bar (fullscreen immersive) resizes the surface again — this
  happens exactly when the user starts reading, i.e. scrolling down through the
  first pages, which are exactly the pages most likely to still be placeholders
  on a slow chapter open.
- Foldables, split-screen drags and e-ink refresh-mode switches do the same
  later in the session.

Each undecoded slot stretches/shrinks with the surface while the camera keeps
its accumulated offsets: the content moves without the user scrolling — the
page "runs away". It reads as rare because it needs surface-height churn to
overlap a scroll through *undecoded* pages, which is concentrated in the first
pages of a freshly opened chapter.

Fix: **a slot height is a property of the page, not of the surface.**
`ProgressPage` and `TransitionPage` freeze their reserved height on the first
stable (non-degenerate) read and never re-derive it. Pages that decode swap to
real heights through the existing decode clamp (`clampScrollWithinPage` + the
hold-the-offset rule), which is the one height change that is *supposed* to
happen, and it is compensated. Surface churn can no longer move the document.

Consequence that makes the width-change rescale in
`ImageViewerContinuousState` self-consistent: a frozen placeholder's slot does
not scale with width, so the proportional scrollY rescale on a width change
becomes a no-op for it (oldH == newH) and only fires for decoded anchors, whose
document height genuinely scales with width.

## Bug 2 — the intermittent thin line between pages

The 2026-10 root cause (two anchors for one boundary) was fixed by chaining the
device-space clip edges through the walked band (`clipTopY`/`clipBottomY`), and
the tile path, the `Render` slot fill and the placeholder centre all consume
those chained floats. Two painters still derived their own boundary:

1. **The fade veil** (`drawFade` in `renderSnapshot`) painted
   `anchorY + scale*docTop .. + scale*pageHeight` — the old derivation. It
   paints exactly when a page fades in after decoding — the placeholder→real
   swap moment.
2. **The per-image underlay rect** painted the image's own placement extent
   (`solveImagePlacement` path) instead of the slot's chained edges for
   uncropped pages.

Both are "rare" for the same structural reason the earlier fixes were: they
only matter when the boundary row would otherwise be painted by nobody (a
mixed boundary pair, mid-decode or mid-fade). Fix: both consume the chained
`clipTopY`/`clipBottomY` — every boundary painter now hangs off the one float.

## The page pipeline

`decodeReaderPage` was a 500-line function whose stage structure existed only
as `traceStage` strings. The refactor makes the stages explicit and gives them
a user-visible face without changing what any stage does:

- `WebGpuPagePipeline.kt`: `PageStage` (FETCH, LOAD, READ, VALIDATE, ENHANCE,
  METADATA, DECODE, PREPARE, UPLOAD, ADOPT) with display labels, and
  `enterStage(page, trace, stage, detail, traceName)` which does both halves:
  the `DecodeTrace.enter` (log-side stage names stay byte-identical to today —
  the symptom table greps them) and `publishPageStage`.
- `publishPageStage` writes the stage label onto the shell's `ProgressPage`
  (`stageText`), invalidated for the next frame. The placeholder renders the
  stage under the percentage: "43%" over "Decoding · 4032x3040".
- `startPageLoad` publishes FETCH/LOAD and keeps the download percentage feed;
  the loader-side progress and the pipeline-side stage now read as one line of
  status instead of a bare spinner.

Interceptor seam: `WebGpuExtension` is unchanged (upscale = byte transform,
translation = post-decode) and now sits inside named pipeline stages rather
than being called ad hoc from the middle of the function. New on-the-fly
modifiers implement `WebGpuExtension.transformBytes` (bytes) or the
post-decode compartment and are automatically ordered, isolated, and traced.

## Smart loading

- `PriorityDecodeQueue` replaces the raw `ArrayDeque`: entries carry a priority
  computed at enqueue (current page > on-screen demand > renderer-demanded >
  travelling preload by distance > idle preload by distance > other), ties
  broken LIFO within a class (the old prioritized behaviour) and FIFO among
  equals (the old background behaviour). The pop/contains/remove/size contract
  of the old deque is preserved so the queue's invariants (pop under lock, one
  worker per page) are untouched. The direction bands interleave
  (travelling-behind 740−d outranks idle-ahead 730−d' when d < 10+d'), which a
  first-cut +30 boost over a 100-point class gap could never do — caught by the
  standalone ordering harness before it shipped.
- Direction-aware prefetch: the viewer tracks `lastScrollDirection` from page
  crossings; `decodePriorityOf` boosts the side the reader is moving toward, so
  a downward read fills downward first without losing the symmetric window.

## Position tracking

- `restoreGeneration` bumps on every `setChaptersInternal`; both deferred
  restores capture it and abort when it moved (previously they relied only on
  the documentY-delta heuristic and the anchor identity). Chapter swaps and
  explicit seeks can no longer race a restore into applying a stale position.

## Predictive geometry (implemented the same day)

- `TallPageSplitter.split`'s sink now receives each band's pixel dimensions
  alongside its bytes - every band of a strip is fully determined by the strip's
  own height, so handing them out costs nothing.
- `ReaderPage.predictedImageSize` carries those dimensions on the page model:
  a segment is born with its exact band size, and the pipeline's validate stage
  writes what an ordinary page's header declares the moment its bytes have been
  read - before the decode has run.
- `ProgressPage` resolves its slot height in three steps: predicted aspect
  (`viewportWidth * h / w` - the exact expression the walk computes for the
  decoded page) first, the frozen viewport read second, the live read last.
  With prediction the placeholder-to-image swap changes no geometry at all;
  long strips no longer reflow as their segments finish loading one by one,
  which used to be the largest remaining source of mid-chapter shift.
- The aspect path is width-proportional on purpose: it rides the library's
  width rescale exactly as a decoded page does, while staying immune to the
  surface-HEIGHT churn the freeze exists for.

## Deliberately unchanged

- The stuck-page ladder, eviction policy, memory budget and device recovery:
  re-verified against this design; no change.
- `clampToDocumentEndLocked`'s `notifyPageChange(-1)`: load-bearing (the app
  callback is what moves the window anchor for the re-measure); its one-page
  backward report at a short document end is documented in the folder's
  AGENTS.md instead of being "fixed".
- TallPageSplitter: adaptive cutting + bounded recuts already landed; the split
  side of the predictive idea (naming split candidates before the loader cuts)
  is subsumed by the predictive-geometry work above - the loader still decides
  when to cut, but every reader-side consequence of a split is now known as
  soon as the split exists.
- Smart tiling is library-side (`TileRenderer`: on-screen-first centre-out
  generation, byte-budgeted atlas, capped texture dims, memory-pressure
  scaling) and already covers what the objective asks; no app-side change.

## Verification performed offline

- `scripts/kotlinc-check/check.sh` over every touched file: error profile
  identical to the baseline batch (same unresolved-classpath cascades, no new
  families) — cross-checked against a stashed baseline run of the same file
  set.
- The `PriorityDecodeQueue` ordering algorithm is verified permanently:
  `scripts/kotlinc-check/run-harness.sh DecodeQueue` compiles and runs the REAL
  generic queue (`PriorityDecodeQueue.kt` — extracted and genericized for
  exactly this) through 28 assertions covering class order, LIFO/FIFO ties,
  direction-band interleave, dedup, identity `contains`/`any`, remove-keeps-order
  and `describe`. The scratch harness that first ran this caught the first-cut
  boost being dead weight (+30 over a 100-point class gap could never flip a
  race); the bands were restructured to interleave before anything shipped.
- `release_notes.py --check` passes (New 18 / Improve 12 / Fix 3).
