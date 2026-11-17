# viewer/webgpu/ — WebGPU reader pipeline

Debugging-oriented knowledge base. Read this before changing anything in this folder; the
state machines here are spread over nine files and the invariants live in the comments of
the ones that own them.

---

## ⚠ KEEP THIS FILE CURRENT — it is written by its readers

**Every new observation about this pipeline belongs here, in the same commit that made it.**
This file is only as good as the last person who learned something and wrote it down; the
alternative is re-deriving it from a logcat paste on a phone, which is what it exists to
prevent.

Update it whenever — not only when you fix a bug:

- You diagnose a bug, or **fail to** diagnose one and had to work it out by bisection.
- A logcat symptom turns out to mean something other than it appears to.
- You learn that a log line, field or flag does **not** work the way its name suggests.
- A fix here removes an assumption that turned out to be false.
- You add a state, a thread, a recovery path, a cache tier, or a counter.
- You burn real time on something avoidable.

Where it goes:

| Observation | Section |
|---|---|
| A log line and what it actually means | **Symptom table** |
| A rule that is not obvious and breaking it is costly | **Known landmines** |
| New state/thread/cache machinery | the section it belongs to, plus the state diagram |
| A gotcha that cost an afternoon | **Known landmines**, one bullet per incident |
| Anything that would have saved time before you found it | the table it fits in, else a new one |

Two rules that keep it useful:

- **Record what was wrong, not what you changed.** `grep` and `git log` already track the
  diff. Write the thing that is true forever ("an adapter is consumed and cannot be reused")
  rather than the thing that was true for one afternoon ("the spinner returns null now").
- **Don't delete a landmine because it is fixed.** Move it to the landmines list with the
  invariant it protects. The next person will try the same simplification.

If you cannot explain *why* something is true, write the observation anyway and mark it
`UNVERIFIED:` — a labelled unknown is worth more than a confident guess, and it tells the
next person which belief to check first.

---

## THE TWO HALVES

| Half | Where | Owns |
|---|---|---|
| App | `app/.../ui/reader/viewer/webgpu/` | page shells, residency, decode scheduling, GPU upload |
| Library | `external/webgpuviewer-houri` (`:webgpuviewer`) | device, render thread, tile grids, transitions, filters |

The app **never decodes or draws** — it hands the library an `ImagePage` per page and the
library draws it. So "the page is blank" is a question about *which* ImagePage the shell
currently holds, and "nothing renders at all" is almost always the device, not the app.

## THREADS — the mental model everything else hangs off

| Thread | Name | What runs there |
|---|---|---|
| Render | `WebGPU-Render-Thread` | `WebGpuRenderer.dispatcher`. `render()`, `renderPass`, `buildSpreadPage`, tile grids. Guarded by `WebGpuRenderer.mutex`. |
| Decode | `WebGpuViewer-Decode-N` | `decodeExecutor`. **A pool** (`decodeParallelism()`, 1–2 workers): each worker owns the page it popped under `lock`. All decodes/preloads/byte transforms; the native upscale stage additionally serializes on `byteTransformMutex`. |
| Main | `MainScope` | stuck sweep, progress spinner, device-loss handler, `onTrimMemory` → `shrinkCacheOnTrim`, and the **memory monitor** (the fourth coroutine, `startMemoryMonitor`). |
| Default | — | chapter preload/retry only. |

**A field read by both the render thread and main must be `@Volatile`.** `ViewerReaderPage`
gets this right for everything *except* what went wrong — see "Known landmines".

The viewer's `lock` (`Object`) covers `pageCache`, `decodeQueue`, `stuckRecords` and the
`currentPage` swap. It does **not** cover the shell fields, which is why those are volatile.
Never call `cleanup()`-bearing code while holding it — `releasePageResources` is deliberately
lock-free for exactly that reason.

**The decode pool preserves the single-flight invariant.** The one invariant the queue protects
is "a page in a state `queueForDecode` treats as in-flight has a worker actually on it": the pop
happens under `lock`, so N workers pop N *distinct* pages, and every worker exit path (including
the plain returns `decodeReaderPage` takes) resets its own page. What the pool changes is only
throughput: a slow decode no longer blocks the prioritized current page queued behind it. The
in-flight trace registry is `activeDecodes` (one entry per worker) — **not** a single slot; the
stall watchdog watches every entry. Small/TINY devices run one worker on purpose (two concurrent
decodes double the transient bitmap spike the memory budget exists to prevent).

The watchdog's cadence is two-speed (KMK): 1s while any decode is in flight, 10s when none is. A
stall cannot exist without an in-flight decode, so parking while `activeDecodes` is empty costs only
the *noticing* of a newly started decode — detection itself is from the trace's timestamps, never
from the loop's tick, so no stall is ever reported later than it would have been. Before this, the
1s tick ran for the whole session whether or not anything was decoding.

## PAGE STATE MACHINE

```
IDLE ──queueForDecode──▶ QUEUED ──worker pops──▶ DECODING ──▶ (imagePage = ImageSingle)
  ▲                        │                        │
  │                        │                        ├─ fails ─▶ ErrorPage, back to IDLE
  └────────────────────────┴────────────────────────┘
                     LOOPING (bytes absent) ──startPageLoad──▶ LOADING ──Ready──▶ QUEUED
```

**The one invariant:** a page is never in a state `queueForDecode` treats as "someone is
already on it" while nothing actually is. `ensureDecoding` is the one producer (the renderer
asked for it). `requeueStuckPage` is the *only* thing allowed to break that tie.

### `ensureDecoding` is one-shot

```kotlin
if (page.wantedByRender) return
page.wantedByRender = true
```

It queues at most once per shell. `fetchPage` runs every frame for every page in the render
window, so this is what stops a failing page becoming a frame-rate retry loop (each pass
allocating decoder and network buffers). The consequence, and it is load-bearing:

> **A shell that fails is never re-queued by the render walk.** `wantedByRender` is cleared
> only by `releasePageResources` (destroys the shell) or by `requeueStuckPage` giving up.
> An `ErrorPage` is recovered by **tap** (`WebGpuViewer.onTap`), not by re-demand.

If you are looking for "why doesn't this page retry itself", that is the answer — it is by
design, and tap is the escape hatch. `rebuildPageCache` / `resetDecodedPagesAfterDeviceLoss`
/ chapter swap clear the flag en masse.

## DECODE PRIORITY — the queue is ordered, not two-ended

The ordering *policy* is `decodePriorityFor` in `WebGpuDecodePriority.kt` — a pure function over
five facts (is-current, is-on-screen, renderer-demanded, signed distance, scroll direction), with
 no Android import, because it is asserted offline by `DecodePriorityHarness`. What stays in
 `WebGpuDecodeQueue.kt` is the two lookups that need a viewer: which page is `currentPage`, and
 `pageDistance`. That seam is deliberate: before it, the one piece of arithmetic in this folder
 whose bands are documented as load-bearing could only be exercised through a live viewer with a
 populated cache.

**The bands are unchanged, and one guard was added while extracting them:** the distance magnitude
is widened to `Long` before `minOf`, because `abs(Int.MIN_VALUE)` is negative and the subtraction
would have overflowed into a *negative* priority — a page that can never be popped. Distances come
from chapter/index arithmetic and cannot reach that in practice; `DecodePriorityHarness` pins both
the clamping and the positivity.

`decodeQueue` is a `PriorityDecodeQueue`, not an ArrayDeque. The old deque encoded priority in
its two ends (prioritized at the pop end, background at the other), which made "prioritized" a
single bit; priorities are now computed at enqueue by `decodePriorityOf`:

| Class | Priority | Who |
|---|---|---|
| current page | 1000 | `page === currentPage` |
| on screen | 900 | `imagePage.isOnScreen` |
| renderer-demanded | 800 | `wantedByRender` (set by `ensureDecoding` *before* it queues, so the class is already correct at enqueue) |
| travelling preload | 750 − distance (ahead) / 740 − distance (behind) | the side `lastScrollDirection` points at |
| idle preload | 730 − distance (ahead) / 720 − distance (behind) | the side the reader came from |
| other | 100 | no anchor / no measurable distance |

The bands interleave rather than stack: scrolling up, the nearest page above (travelling behind,
740−1) outranks the nearest below (idle ahead, 730−1) — direction genuinely flips the race —
while every preload stays under renderer demand (max 749 < 800) and above OTHER. Distances past
64 clamp, so a far preload still outranks an unrelated shell. Ahead still outranks behind at
equal distance and equal direction (the deque-era invariant). Ties resolve LIFO among
renderer-demanded pages (the page just demanded wins) and FIFO among preloads (the walk's
nearest-first resolution decides within a distance tie) — the two ends of the old deque, as
explicit classes. The queue itself is `PriorityDecodeQueue` (`PriorityDecodeQueue.kt`) —
generic on its element type precisely so `scripts/kotlinc-check/run-harness.sh` can compile and
run the real class (`DecodeQueueHarness`, 28 assertions) instead of asserting on a copy.

Everything that was true of the deque's threading stays true: every mutation happens under the
viewer's lock, `enqueue` dedups by identity first (the LOADING branch's old `contains` guard, now
unconditional), and a page popped under the lock is never handed to two workers.

## PIPELINE STAGES — the decode path, named, and on the placeholder

`WebGpuPagePipeline.kt` turns the decode path's implicit stage structure into `PageStage`
(FETCH → LOAD → READ → VALIDATE → ENHANCE → METADATA → DECODE → PREPARE → UPLOAD → ADOPT).
`enterPipelineStage` does exactly two things: enters the [DecodeTrace] stage (the trace names are
byte-identical to what the stages were when they were bare `traceStage("...")` calls — the
symptom table greps them; do not rename one without grepping this file first) and publishes the
stage label onto the shell's `ProgressPage.stageText`, which the placeholder renders under the
percentage: `43%` over `Decoding`.

The stage line is diagnostic plumbing with a reader-facing face, not a state machine: it writes
nothing the pipeline did not already know, adds no work, and dies with the placeholder it rides
on (adoption replaces the whole placeholder). FETCH/LOAD are published by `startPageLoad`
(`publishLoadStage`) — they run before any trace exists. If you add a stage to the decode path,
give it a `PageStage` so the reader can see it; if you add a *placeholder*, remember the stage
line is `ProgressPage`-only and an `ErrorPage`/`TransitionPage` correctly has none.

## STUCK-PAGE LADDER

The escalation table is `stuckLadderActionFor` / `stuckRetryDue` / `stuckRetryBudgetReset` in
`WebGpuStuckLadder.kt` (pure, asserted by `StuckLadderHarness`). The constants moved with it:
`STUCK_REDRIVE_SOFT_LIMIT`, `STUCK_REDRIVE_HARD_LIMIT`, `STUCK_REDRIVE_COOLDOWN_MS`,
`STUCK_REDRIVE_FRESH_WINDOW_MS`, `STUCK_LOAD_DEADLINE_MS`. The state machine that applies them is
still `requeueStuckPage`, below.

`startStuckPageSweep` runs off `stuckSignal` (conflated) — not a timer. The orphan condition
cannot arise on its own; it needs a queue/cache mutation, and only a few sites do one.

| Branch | Meaning |
|---|---|
| `queued-but-absent` | QUEUED but not in `decodeQueue` — nothing will ever pop it |
| `loading-done` | LOADING but the bytes already arrived |
| `idle-placeholder` | IDLE + `wantedByRender` + still a `ProgressPage` — the common one |

`requeueStuckPage` then, per page:

- `attempts <= 3` → reset to IDLE and re-queue (`REDRIVEN`)
- `attempts <= 9` → **escalate**: `tearDownStuckPage` drops the shell and its tiles entirely (`REBUILT`)
- `attempts > 9` → give up: clear `wantedByRender`, hand it back to the render walk (`GONE`)
- anything inside the 1500 ms cooldown → `DEFERRED` (re-arm, no log)

The `last=<outcome>` in the log is `decodeOutcomes[pageKey]` — *how the previous decode
ended*, not why the page is stuck. Outcomes: `no-stream`, `no-bytes`, `rejected:*`,
`abandoned`, `not-adopted`, `loading`, `decoded`, `threw`, `unfinished` — several of them are
*normal* returns, and the worker's `finally` cannot tell a return from a throw, so every early
return in `decodeReaderPage` completes its own trace first (`loading` = the page left for
`startPageLoad` because bytes were not Ready; `not-adopted` = the finished image was discarded for
a page nobody will draw; `abandoned` = viewer torn down, or the page evicted mid-decode). A return
that skips its own completion is recorded as `threw` and the ladder's log lies. **`no-stream`
means `page.stream` returned null, not that the decoder failed** — see "Symptom table".

## DEVICE LIFECYCLE — read this before touching recovery

`WebGpuRenderer` is a companion-object singleton: `instance` → `adapter` → `device`, plus a
single render `dispatcher` and `mutex`.

### NEVER reuse an adapter to rebuild a device

A `GPUAdapter` is **consumed** by the first `requestDevice` that succeeds on it. Dawn rejects
a second one outright:

```
adapter is "consumed": it has already been used to create a device
```

So after a device loss you must `instance.requestAdapter(...)` **again** and only then call
`requestDevice`. `recreateDeviceLocked` did reuse the old adapter; nothing else re-requested
one, so `initError` latched and `isAvailable` stayed false **for the life of the process**.
Symptom: one page decodes, the device is lost, and from then on every single decode throws
`IllegalStateException: WebGPU not available` forever.

`deviceGeneration` is bumped on every successful recreation. Anything caching device-bound
objects (filters, mipmaps, the shared pineapple texture) keys on it or on `device` identity.

### What is alive after a loss

`recoverFromDeviceLoss()` → device rebuilt → filters cleaned → swapchain rebuilt from the
retained platform surface. The app's `deviceLostListener` then calls
`resetDecodedPagesAfterDeviceLoss()`, which destroys **every** decoded page, clears the tile
grids, and re-anchors on the current page. Everything device-bound is gone by design; nothing
cached survives.

**Recovery retries, bounded.** `recoverDeviceWithRetries` makes 3 attempts with linear backoff
(250/500/750 ms) before giving up. A loss on a phone is usually backgrounding, and the first
`requestAdapter` frequently fails because the adapter is still being reclaimed — single-shot
recovery meant one unlucky attempt latched `initError` for the life of the process. Bounded, not
indefinite: an unrecoverable device must stop trying rather than spin for the session.

**The library ladders recoveries too (`RECOVERY_GIVE_UP_ATTEMPTS`).** A failed recovery loses
the device it just built, whose own loss callback starts the next chain — so the app's 3-attempt
bound never bounded anything across chains. `WebGpuRenderer.recoverFromDeviceLoss` now counts
consecutive failures itself: early failures run inside the app chain's own pacing (300 ms
steps), deeper failures buy longer cooldowns (`recovery deferred …` in the log), and 12
consecutive failures stop the self-retries until a fresh loss event. A recovery resets the
dial only after its device has stayed alive 15 s (`RECOVERY_STABLE_MS`) — a device that dies
again immediately belongs to the same pressure era. A failed recovery also self-retries once
per cooldown (`scheduleRecoveryRetry`), because the loss callback fires once and the reader
used to stay dead until recreated even after the pressure passed. The app's
`recoveryChainRunning` guard keeps parallel chains from stacking on top of a running one: a new
loss while recovery is in flight is absorbed by the running chain's retries.

**A recovery that lost its device at birth now reports failure.** After `createSurface()` the
recovery re-reads `deviceLost`: a surface build that fails (`VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`)
loses the fresh device synchronously on the loss-callback executor, and recovery used to return
`true` for exactly that case — hiding the loop from every caller.

**`DeviceRecoveredListener` for the silent successes.** The loss callback fires once, on the
loss itself; the library's self-healing retries succeed later with no callback. The app
registers `WebGpuRenderer.addDeviceRecoveredListener` and re-runs
`resetDecodedPagesAfterDeviceLoss` there — a page that failed to decode *while the device was
dead* carries an `ErrorPage` and a latched `wantedByRender` that only a fresh reset clears.

**A requery must evict the segment key, not just its parent.** A split segment's slice is cached
under its own derived key; evicting only the parent re-downloads the parent and then
`ensureSegment` finds the stale slice still cached and hands the same bad bytes back.

### The device upgrades itself to core, and dead devices fall back across sessions

Two hardening layers added from the 2026-11 field reports (Adreno 6xx and Mali bifrost
webgpureport exports; a PowerVR/nomtl emulator logcat with
`vkQueueSubmit failed with VK_ERROR_OUT_OF_DEVICE_MEMORY` two seconds into the first decode):

- **`requestDeviceBestEffort` requests `core-features-and-limits` whenever the adapter lists
  it** (`requiredFeaturesFor` in the submodule's `WebGpuRenderer`). Both field adapters list
  it, and so does essentially everything else in the field — which means Compatibility mode
  was supplying only restrictions: 128-invocation compute workgroups, 16384-byte uniform
  buffers, 15 inter-stage variables, storage buffers barred from the vertex stage, 16
  texture+sampler combinations per stage. A pipeline that exceeds any of those fails
  validation with **no exception on the draw path — a frame that draws nothing, i.e. the
  black screen**. The upgrade is in the library, not the app, and the init/recreate log lines
  now carry `core=true/false`; compare that flag across field reports before theorising about
  a limit. A driver that lists the feature but rejects the request retries compat-only
  instead of latching `initError`.
- **`WebGpuHealth` (app) turns repeat dead sessions into the legacy viewer.** One strike per
  reader session that closes with the renderer still dead (`destroy()` re-reads `isAvailable`
  — gated on `activity.isFinishing`, because a phone drops the GPU device on backgrounding
  as a matter of course and an OS background-kill is not a session the user gave up on), or
  that lost the device `SESSION_LOSS_STRIKE_THRESHOLD` (3) times even with recoveries in
  between; `DISABLE_STRIKES` (2) strikes and `WebGpuProvider` declines, so the reader opens
  on the pager instead of the same dead device every launch. The About screen easter egg
  (the RAM-gate one) resets the strikes. A strike is *not* recorded for a session that lost
  the device once and recovered — backgrounding does that on healthy phones. Every strike is
  also written to the **in-app log file** (`xLogE` from `recordStrike`), because a phone-only
  user never sees logcat and "the reader stopped opening on WebGPU" with no record of why
  reads as a mystery regression.

## RESOURCE OWNERSHIP

`releasePageResources(page)` is the single owner and the only place that releases a
`ViewerReaderPage`. It frees: spread image, height-match retry job, comparison originals,
retained source bytes, rescale-in-flight flag, `wantedByRender`, and the image page itself.

If you add a field holding a GPU resource or a coroutine **to `ViewerReaderPage`**, add it
here too. Six hand-copied subsets used to exist and had already drifted — that drift is how
`wantedByRender` ended up cleared on the eviction path but not the device-loss path.

Cleanup order matters: the spread and comparison pages are released *before* the image page,
because their cleanup compares their contents against the current one.

The three "throw the working set away" paths (`rebuildPageCache`, `destroy`,
`resetDecodedPagesAfterDeviceLoss`) go through `releaseAllShellsAndClearResidency` in
`WebGpuPageCache.kt` — see its landmine before adding any clearing of your own.

## SPLIT RECONCILIATION

A page too tall for the decoder is replaced by segments mid-session, **after** the viewer
built its page graph.

- `ReaderChapter.pageListVersion` is the change signal; `PageListWatch` holds all three
  chapter slots (prev/current/next), not just the current one.
- `syncPageList` is called from `preloadPages` **and** from `continuousPage` — scrolling
  inside a page crosses no chapter, so the preload path never runs, and without the
  `continuousPage` call the strip draws a stale shell under the page being read.
- `supersededBySplit` marks the dead parent. `evictReplacedPages` drops it — nothing else
  will, it is neither idle nor farthest and its key still looks valid.
- `linkedNeighbour` delegates a removed page's neighbour to its replacement, because
  `positionOf` returns -1 and every edge test reads that as a chapter boundary.

## CACHE & EVICTION

`cacheSize = 1 + preloadAhead + preloadBehind + (dualPage ? 3 : 1)`.

`evictFarthestPage` is distance-based (chapter/index math, never a cache chain walk, so gaps
from earlier evictions cannot mismeasure it). Two things override distance:

1. **Drawn-page immunity** — `imagePage.isOnScreen` from the last frame is ground truth.
   The continuous viewer tracks `currentPage` through the library's relative ±1 deltas, so a
   lockstep disagreement mis-centres the window and distance alone destroys visible pages.
2. **Preload-window membership** — pages inside the window survive while anything outside exists.

Ties prefer the cheaper page (`evictionCost`: undecoded < queued/in-flight < decoded), then
oldest insertion order. A page a split just superseded measures `distance == null` and sorts
farthest, which is the safe answer — it is exactly what the replacement is about to replace.

## PAGE LOADING — the half that has no timeout

`startPageLoad` owns bytes: it flips the shell to `LOADING`, launches `loader.loadPage` on
`Dispatchers.IO` **only if the status is still `Queue`**, and collects `statusFlow` until the
page leaves `Queue`/`LoadPage`/`DownloadImage`. Its `finally` resets `LOADING → IDLE` and then
dispatches on the final status (Ready → re-queue, Error → ErrorPage, anything else →
`askLoaderToResolvePage`).

### LOADING has a deadline now (`STUCK_LOAD_DEADLINE_MS`)

The sweep's LOADING branch is now three-way:

| Outcome | Meaning |
|---|---|
| `loading-done` | LOADING but already `Ready` — `queueForDecode` promotes it |
| `loading-stalled` | LOADING past the deadline without reaching `Ready` |
| (null) | genuinely fetching — leave it alone |

`loading-stalled` is the shape that used to be invisible: a page whose load coroutine ended with
the status stranded at `Queue`/`LoadPage`/`DownloadImage` was not queued-but-absent, not
idle-placeholder, and `queueForDecode` treats LOADING as in-flight — so it stayed LOADING for
the rest of the session with no log line at all. **Silence was the only symptom.**

The deadline lives in `WebGpuViewer.loadingSince`, stamped by `startPageLoad`, dropped by the
loader coroutine's `finally`, by `requeueStuckPage`, by `evictFarthestPage`, and wholesale
wherever `stuckRecords.clear()` runs. **UNVERIFIED:** whether `Page.State` can actually strand
at `LoadPage` — a cancelled load coroutine is the candidate, but nothing in this folder cancels
one. The deadline is worth keeping regardless: it turns a silent permanent wedge into a bounded
retry whatever the trigger turns out to be.

### `retryPage` is not eviction — a repeat `no-stream` escalates

`askLoaderToResolvePage` is the first `no-stream` recovery and it calls `loader.retryPage`.
Per `WebGpuPageRefetch.kt`'s header: `HttpPageLoader.internalLoadPage` **skips the download
when `chapterCache.isImageInCache` is true**. Correct for a network failure, useless when the
cached bytes are themselves the defect.

So the second `no-stream` for a page now escalates to `requerySuspectPage`, which evicts the
cache entry *before* re-queuing — the only thing that produces fresh bytes. The bound is
`MAX_SOURCE_REQUERIES = 1`, so a genuinely broken source is asked once and then left to fail
visibly rather than amplified.

Note the ordering: `lastDecodeOutcome` read inside the `no-stream` branch is the **previous**
attempt, because this attempt's outcome is recorded by the worker after `decodeReaderPage`
returns.

### `retryPage` was a no-op for exactly the pages that needed it

The recovery above is built on `loader.retryPage`, and for a **split segment** that call did
nothing at all — which is why a long strip could log `Re-driving ... last=no-stream` on the
same segment every 1.5 s for the rest of the session.

Three facts compose into the trap:

1. A segment is constructed with `status = Page.State.Ready` (`splitOversizedPage`), and its
   `stream` is a lambda reading a **cache file** the LRU evicts independently of the page
   list. So an evicted segment is *Ready but unreadable*.
2. `HttpPageLoader.retryPage` only reset `Error → Queue`.
3. The loader's collector filters on it: `.filter { it.status == Page.State.Queue }`, and
   `boostPage` checks the same thing.

So the retry offered a `Ready` page to the queue, the collector discarded it, `loadSegment`
never ran, and the segment's file was never re-cut. Every retry changed nothing while
reporting itself as an attempt. `retryPage` now resets anything not already `Queue`.

This is the same shape as the `retryPage`-is-not-eviction landmine above, one level up: the
call did not even *reach* the code that would have been a no-op.

### A re-query used to erase its own escalation ladder

`requerySuspectPage` cleared `stuckRecords.remove(pageKey(page))` to escape the ladder's
cooldown. Deleting the entry also reset `attempts` to 0, so a page whose re-query did not fix
it went back to attempt 1 every time and could never reach the terminal `GONE` state — an
unbounded spin, not a bounded retry, and no "Giving up on" line to show for it. It now clears
`lastAttemptAt` (the cooldown) and leaves `attempts` alone.

> **Rule:** cooldown and escalation are separate dials. Resetting a page for genuinely new
> bytes is a *fresh attempt*, never a *forgotten history*.

### A segment's slice identity must not live only in its URL suffix

`loadSegment` read which slice to re-cut from the `#segment-N` suffix on `imageUrl`, defaulting
to **0** when the suffix was absent. But `retryPage` clears `imageUrl` outright on E-H based
sources so the source re-resolves it — and a re-resolved URL has no suffix. Every segment of a
strip then resolved to slice 0, so a long strip drew as N copies of its own top segment.

`segmentNumberOfIndex` recovers the number from the page's synthetic index instead, and
`loadSegment` uses the index as the fallback. The index is `BASE + source * STRIDE + number`, so
the remainder *is* the number — which is also why the stride has to exceed the segment count.

> **Rule:** anything that can re-resolve or rewrite a page's `imageUrl` must first check whether
> the `#segment-N` suffix is load-bearing. `internalLoadPage` already guards this once
> (`if (!page.splitSegment) page.imageUrl = imageUrl`); a caller that nulls the URL upstream
> bypasses that guard entirely.

### Low memory reaches the tile cache, and comes back

`onTrimMemory` sheds page shells at `>= UI_HIDDEN`, and **separately** calls
`ImageViewerState.onLowMemory()` at `RUNNING_LOW`, `RUNNING_CRITICAL`, `MODERATE` and `COMPLETE`
only.

The split is deliberate and load-bearing: letting it ride the screen-off signal would mean a
full tile rebuild on every lock/unlock, which is why `UI_HIDDEN` stays out of the device-cache
branch. Tiles refill lazily, so shedding them costs a rebuild, not correctness.

> **The `cacheScreens = 0.8` ratchet used to be permanent, and that was the bug.**
> `TileRenderer.onLowMemory` assigned `0.8` outright, so one transient trim signal cost the
> rest of the session a permanently smaller tile budget with nothing to bring it back. It now
> *records* the pre-shrink value and `restoreMemoryBudget` puts it back. It also scales an
> explicit `cacheByteBudget` when one is set, because `budgetTiles` ignores `cacheScreens`
> entirely once the host supplies bytes — shrinking screens there did nothing at all.

> **Order is load-bearing in the trim callback:** `applyMemoryBudget` first, then
> `shedDeviceCaches`. The resample republishes the tile budget from current pressure, and
> `onLowMemory` scales whatever it finds *downward* — so the reverse order has the resample
> undo the shed on every callback, which is precisely the ratchet above.

## READING-POSITION RESTORE — three coordinate systems, one landmine

A restore lands through `setChaptersInternal` (position store → `requestedPage`), and the three
sources of that page index are written in *different list coordinates*:

| Source | Coordinates |
|---|---|
| `WebGpuReadingPositionStore` | the full strip the walk renders, camera-page relative (`pageIndexHint = 0`) |
| DB `last_page_read` | the **next-open** (persisted, un-split) list |
| SavedStateHandle `page_index` | the **live** list as it stood at write time — `updateChapterProgress` writes `positionOf(page)`, which already includes every split segment inserted before the read point |

**A process-death restore used the live coordinate to index the fresh list.** After
SavedStateHandle restore, the fresh process rebuilds the *un-split* list the loader persists, but
`chapterPageIndex` names a position in the list as it stood then — so past a tall page's split,
the chapter opened several pages forward. It is now clamped to `chapter.last_page_read` — the same
page in next-open coordinates — when that value sits inside `1 until chapterPageIndex`: an equal
value is the same page, and a smaller one means the process died before the progress write landed.
Incognito writes nothing to the DB (`last_page_read == 0`), so `chapterPageIndex` stays the only
record there and is used as-is.

**Mechanisms eliminated while chasing the same report** — do not re-thrash these without new
evidence:

- `WebGpuRestore`'s math is camera-page-relative and lands inside the target page regardless of
  neighbouring placeholder heights; `getCurrentPageIndexLocked` never returns null.
- The position store's data is written in saved/unsplit coordinates, matching a fresh list.
- `PageAnchor.MAX_PAGE_INDEX = 9999` is generous.
- Restores running from the render thread are bounded: `onPageScrolledThrough`/
  `emitReaderState` callbacks hop off via `viewModelScope.launchNonCancellable`/`launchIO`,
  `StateFlow.update` is thread-safe, `currentAnchor` is `@Volatile`, and the `loadNewChapter`
  branch is unreachable from the scrolled-through path (its
  `chapter === viewerChapters?.currChapter` guard skips exactly when the callback belongs to the
  transition being crossed).
- `awaitRestoreTarget` polls on `RESTORE_POLL_LIMIT` — 100 polls of 100 ms. Its comment had drifted
  away from that arithmetic and was restored; when touching the poll loop, keep comment and
  arithmetic in agreement.
- A deferred restore is generation-scoped (2026-11-09): `setChaptersInternal` bumps
  `restoreGeneration` on every chapter set, and both `restoreContinuousAfterDecode` and
  `restorePagedZoomAfterDecode` capture it when armed and abort when it has moved. Before that,
  a chapter swap or explicit seek landing while a restore waited for its page could still apply
  the old position — the documentY-delta heuristic only catches the user scrolling, not the
  document being rebuilt under them.

> **Rule: a saved index is only valid in the list it was written against.** Every restore site —
> SavedStateHandle, DB, position store — says which list its coordinate belongs to; `last_page_read`
> is the one written in the coordinates a fresh list actually rebuilds.

**Progress writes do not depend on the page's load state.** `ReaderViewModel.updateChapterProgress`
used to skip its entire `last_page_read` write while `page.status is Page.State.Error`. Continuous
mode scrolls *through* such pages — the stuck-page ladder leaves `GONE` shells as `ErrorPage`s on
long strips (evicted segment cache, network hiccup), and nothing stops the camera at them — so every
crossing over a failed page silently dropped its write. A chapter crossed past its last page never
turned `read`, the manga details screen resumed a chapter (or several) behind, and its chapter list
stayed stale until some later successful write happened to land — "spotty", because it only showed on
the crossings unlucky enough to straddle failed pages. The write is now unconditional (incognito
still writes nothing); only the completion side-effects (`updateChapterProgressOnComplete`, the read
sync trigger) stay gated on the page having rendered.

**In continuous mode, `onPageChange` resolves what the strip actually showed** (`continuousPage(delta)`,
the same window/chain walk the render used). It previously re-walked the chapter chain independently,
which can disagree with the strip at a chapter boundary: the strip serves a window-cached slot, while
a fresh link over an un-loaded next chapter returns null and the walk aborts — the strip keeps
moving, and every further crossing is reported from a page the reader is no longer on, compounding
the lag. The walk is kept in paged mode (deltas > 1 there; continuous only ever fires ±1).

**Read-through only advances while real scrolling happens** (KMK island in submodule
`ImageViewerContinuousState.kt`; port of upstream `92be54d`). `onPageScrolledThrough` guards the
`last_page_read`/position-store writes on this path, and its ratchet computed from walk geometry —
which a pinch zoom-out or a placeholder slot *shrinking* would move no less than a scroll would,
marking pages read that were never scrolled past. The ratchet now fires only while
`isFlinging || isPanning`: `animateScroll` (tap navigation) sets `isFlinging`, the single-finger
drag sets `isPanning`, and a second pointer joining the drag clears it — so zoom-driven geometry
changes cannot write progress anymore, and a restore measured against the steady baseline stays
steady. When chasing a "opens on the wrong page" report where `last_page_read` looks *too large*,
read this gate as eliminated along with the clamp: it was the second write-path corruption vector
in the same family.

## MEMORY BUDGET — the reader sizes itself to the device

`WebGpuMemoryBudget.kt` answers "how much may this reader hold", and `WebGpuMemoryMonitor.kt`
keeps the answer current. This replaced `isLowRamDevice`, which was `totalMem < 3GB`.

**The arithmetic is `WebGpuMemoryMath.kt`** — no Android import, asserted by
`MemoryBudgetHarness`: `deviceTierFor` (RAM + vendor low-RAM mark + ABI), `memoryPressureOf`
(system verdict, then Java heap against the runtime ceiling, then headroom), `budgetScaleFor` /
`pixelScaleFor`, the scaled ceilings with their floors, `resolvePixelCeiling`, `uploadedImageBytes`.
`DeviceTier` and `MemoryPressure` are top-level there and no longer nested in
`WebGpuMemoryBudget` — the class keeps only the two things Android owns: reading `ActivityManager`
and publishing the snapshot. Declaration order is load-bearing (`deviceTierFor` and the call sites
compare with `<=`), and `MemoryPressure`'s ordinal order is the recovery test.

**This arithmetic is now app-wide, and it has a second implementation site.** The tier vocabulary
and the fold live in `core:common` as `DeviceMemoryClass.kt`, with the ceilings that govern Coil's
image cache, the JXL dimension cap, the RGB_565 decision and the fetch pool in `DeviceMemoryMath.kt`,
and the `ActivityManager` read plus the published snapshot in `AppMemoryMonitor.kt` — asserted by
`DeviceMemoryHarness`. This file keeps `WebGpuMemoryMath.kt` for the reader's *own* per-tier ceilings
and pixel/measurement helpers, and its `DeviceTier` / `MemoryPressure` are **typealiases** of the
shared ones, so there is one vocabulary and one comparison. If you change a threshold here, check
whether it belongs in the shared pair instead: a device that is "low memory" to Coil and "fine" to
the reader is the bug this split exists to prevent.

> **The two `DeviceTier` enums were a real hazard, not a style question.** The reader's enum carries
> its per-tier ceilings as constructor data, so it could not simply be deleted in favour of a bare
> shared one. Moving it to `core:common` *with* its data, and leaving a typealias behind, is what
> keeps `tier.maxDecodedPagesBytes` reading the same while making the tier comparable app-wide. Two
> enums of the same name cannot be compared at all, and a policy function in the reader's package
> binds to whichever is in scope.

**The action policy is `WebGpuResourcePolicy.kt`** — no Android import, asserted by
`MemoryBudgetHarness`. It answers the questions that turn a *reading* into something a user sees:
`isMeaningfulPressureChange` (hysteresis), `sampleIntervalMs` / `criticalBackoffMs` (cadence),
`shouldRequestMaxRefreshRate` (does the panel's top rate earn its battery right now),
`shouldDowngradeToRgb565`, `jxlMaxDimension`, `coilMemoryCachePercent`, `coilFetchParallelism`,
`decodeParallelismFor`. Every one of those is a decision a user can see — a cache that rebuilt on a
passing spike is a visible flash, a display pinned at 120 Hz is an hour of battery, and RGB_565 on
a page that did not need it is banding on a gradient.

### The monitor backs off when nobody is looking

Three cadences, all from `sampleIntervalMs` so this loop and the app-wide sampler cannot drift:

| Situation | Interval |
|---|---|
| Idle — `isReaderIdle()` (activity stopped, or the pager detached from its window) | 10 s |
| ROOMY / TIGHT | 4 s / 2 s |
| CRITICAL, sustained | backs off from 1 s toward 16 s (`criticalBackoffMs`) |

The idle case is the battery fix: a reader left open overnight used to wake four times a second to
re-read a value that had not moved. `onTrimMemory` and `applyMemoryBudget` still run immediately at
any moment — the idle cadence only governs *polling*.

### Recovery is gated three ways, not two

`RECOVER_COOLDOWN_MS` (12 s) **and** `RECOVER_PRESSURE` (ROOMY) **and** a stability gate: the
improving reading must have held for `RECOVERY_STABLE_MS` (8 s). The cooldown is about how long ago
the last shrink was; the stability gate is about whether *this* reading has settled. They are
separate dials because a device hovering on the TIGHT boundary crosses it for a single sample and
falls back — and rebuilding the whole page cache on that is a visible flash for nothing. Same
principle as the stuck-page ladder: **cooldown and escalation are independent.**

### The audience hooks (`Viewer.kt`)

The activity is the only thing that knows it stopped, so `Viewer` gained `onActivityStop()` /
`onActivityStart()` (default no-op; WebGPU clears/sets `isReaderActive` and re-samples on resume)
and `onMemoryPressure()`, which the activity's `readerTrimCallbacks` fires for every viewer on the
severe trim levels. Before this, **only the WebGPU viewer had any trim path at all** — pager, strip
and novel held their whole decoded working set through a background or a spike, and the system's
only remaining option was to kill the process.

`ReaderActivity` deliberately excludes `TRIM_MEMORY_UI_HIDDEN` (screen off) from that callback:
a shed on every lock/unlock costs a visible re-decode, and the WebGPU viewer already handles
screen-off through its own `trimCallbacks`. The rule is the one in "Low memory reaches the tile
cache" below — screen-off is not memory pressure.

**A failed `getMemoryInfo` used to demote the device to TINY for the session.** The fallback
constant read `4L * 1024L * 1024L` — four *mebibytes*, not a device that exists — so every read
that threw or returned nothing fell through `deviceTierFor` to the smallest tier: fewer resident
pages, a smaller pixel ceiling, one decode worker. It is 4GB now, and the harness pins it. The
general rule: a fallback that is not a value the real world can produce is not a fallback, it is a
silent downgrade.

| Question | Old answer | New answer |
|---|---|---|
| Is this device small? | one boolean | `DeviceTier` — TINY…XLARGE, plus the vendor low-RAM mark |
| Is memory short *right now*? | never asked | `Pressure` — ROOMY / TIGHT / CRITICAL |
| How big is the page cache? | a page *count* | a byte budget, plus the count |
| How big is the tile cache? | fixed 16–64 MB | `cacheByteBudget`, pushed every tick |
| Does it recover? | never | after a 12 s cooldown at ROOMY, **and** an 8 s stable reading |
| How often is the system re-read? | every 4 s, forever | 4 s / 2 s / 10 s idle / backing off at CRITICAL |
| Does the panel stay at max Hz? | yes, always | only while something is moving |

### Headroom, not `availMem`

`availMem` counts swap, so a device that has been swapping reports plenty free and then gets
killed. The budget uses **`availMem - threshold`**, because `threshold` is where the system starts
reclaiming. That single change is what makes this react to a memory *spike* instead of to a
device's spec sheet.

### The sample/read split is not optional

`cacheSize` is read by the render walk and `maxPageBytes` by the decode thread;
`getMemoryInfo` is a binder call. So `Budget` is an immutable precomputed snapshot published by
`sample()` on the monitor's timer, and the getters are volatile reads. **Never call `sample()`
from a getter or from the render thread.**

### A page count is not a memory measurement

Eight shells of an ordinary chapter is ~20MB. Eight shells of a split webtoon strip is ~380MB.
`ViewerReaderPage.decodedBytes` is therefore a **measurement**, set at adoption in
`WebGpuDecodePipeline` and zeroed in `releasePageResources`, and `evictDownToBudget` satisfies
**both** constraints — the count (the user's preload preference) and the bytes (the device).
Count only would be an estimate; bytes only would throw away a preference the user set.

The measurement carries the full mip chain (`4/3` of the base area). Undercounting by that third
is what lets a cache "fit" and then not.

### The byte budget cannot evict a page that is on screen

`evictFarthestPage` declines to take the drawn page or the anchor, and the loop breaks when it
makes no progress. That is deliberate: under a tight budget the cache stops at "the visible window
plus whatever fits" rather than destroying what is being read. **Do not add a hard byte cutoff
that bypasses the evictor** — that is a segfault class of bug (see the spread/`cleanup()`
landmines), not a memory one.

### A page above the pixel ceiling is downsampled, not uploaded whole

`Budget.maxDecodedPixels` caps the decoded area uploaded at full resolution. Two inputs set
it: the device tier (TINY 6MP … XLARGE 32MP) and — tighter wherever the display is smaller —
**the panel itself**: `displayPixels * 4` (2x linear zoom headroom on each axis), floored at
2MP. A ~1MP e-ink panel therefore caps at ~4MP, a 1440p phone at ~18MP: pixels beyond that are
unviewable data, and loading them is what turns a high-resolution comic into
`VK_ERROR_OUT_OF_DEVICE_MEMORY`. Pressure scales the result (1.0 / 0.75 / 0.5).

`decodeReaderPage` resizes anything bigger *before* `Image(...)` through the library's native
`ImageUtil.resize` (F16 variant for HDR pixels), logging `Downsampled ch=... i=... to WxH
(budget N px)` and a `downsample` trace stage. Animated frames share the first frame's target
so the stack stays one size. The decoder has no shrink-on-load, so the pass is CPU-side on the
decode worker; the decoder's own buffer is freed with the decoder, so the cost is one transient
destination, not a second resident copy. Consequence worth knowing: **page quality varies
across the session exactly when memory does** — the same trade the byte ceilings already make.
If the resize native is unavailable, the original pixels continue and the old failure modes
(`Image area too large`, device loss) apply.

### 32-bit userspace is capped at SMALL, on purpose

`deviceTierFor` folds three device truths into the tier: total RAM, the vendor low-RAM mark,
and **the ABI**. A 32-bit process shares ~3GB of address space across the Java heap, the
native decode buffers and the GPU's own allocations (unified memory) — the budgets a 64-bit
MEDIUM/LARGE device comfortably holds are the spike that loses the device there (field report:
armeabi-v7a losing its Vulkan device two pages into a comic). The tier cap also pins decode
parallelism to one worker (`decodeParallelism` keys on the tier), halving the transient decode
spike. 32-bit keeps the WebGPU viewer — that is a deliberate decision, not an oversight.

### Recovery is gated three ways

`RECOVER_COOLDOWN_MS` (12 s) **and** `RECOVER_PRESSURE` (ROOMY) **and** a stability gate
(`RECOVERY_STABLE_MS`, 8 s, via `isMeaningfulPressureChange`). The cooldown alone still oscillates on
a device hovering at the TIGHT boundary, because every transition is a visible tile rebuild — but
the cooldown measures how long ago the last shed was, and says nothing about whether *this* reading
has settled. Both are needed. Same principle as the stuck-page ladder: **cooldown and escalation are
separate dials.**

The escalation direction is deliberately ungated. Any move to worse pressure acts immediately: a
device about to lose its GPU device gets no benefit from waiting a sample to be sure.

### `isLowRamDevice` is gone from the reader — do not bring it back

Every gate that used to ask "is this device low RAM?" now asks a question about the *budget
instead*, because the device-size answer was available before the page existed and the real
constraint is not:

| Was | Is now |
|---|---|
| `maxPageBytes = 40MB/80MB` on `isLowRamDevice` | `memoryBudget.maxPageBytes()` |
| JXL refused above a flat 16MB, but only on low-RAM devices | `memoryBudget.jxlPageBytes()`, applied on **every** device |
| spread height-match skipped on low-RAM devices | `memoryBudget.canAffordWorkingSet(bytes)` — asked per page |
| continuous slack `if (isLowRamDevice) 3 else 7` | `memoryBudget.deviceTier <= SMALL` |

The JXL gate is the clearest example of what was wrong: it refused a 20MB JXL **only** on a
small device, so a 1GB phone refused a page that a 12GB phone then OOMed on. The ceiling is now a
property of the page and the device together.

`ActivityManager.isLowRamDevice` still exists *inside* `WebGpuMemoryBudget`, where it is a
legitimate input — it folds a large-but-low-RAM device down to `MEDIUM` and raises a low headroom
threshold. `DeviceUtil.isLowRamDevice` is still used by Coil, `App` and `ImageUtil`, none of
which is the reader. Neither is the gate this section is about.

### The loader queue admits every page twice — watch for it

`HttpPageLoader` is upstream of this folder but it is where pages actually load, and its queue has
a property worth knowing before you reason about network behaviour:

- The queue is `java.util.concurrent.PriorityBlockingQueue` — **no dedup, ever**.
- `readerThreads()` defaults to **2**, so `internalLoadPage` runs concurrently.
- `preloadNextPages` is called from **inside every `loadPage`**. Page N offers N+1..N+k and page
  N+1 offers N+2..N+1+k, so **every page from the third onward is queued at least twice**.
  `aggressivePageLoading` offers all of them again, and `retryPage` offers unconditionally.

Two workers could both read `!isImageInCache` and both fetch. `internalLoadPage` now serialises
the download on `downloadLocks`, keyed on the **bare source URL** so a split parent and its
segments share one fetch, and re-checks the cache inside the lock.

**Anything else added to that queue must assume duplicates.** The runaway segment duplication
fixed in `splitOversizedPage` was a symptom of exactly this.

### A strip that outgrows the image cache churns instead of failing

The reader image cache defaults to **75 MB** (`eh_cache_size`, "Image cache size"). A long strip's
working set is its **parent image plus every segment cut from it** — roughly twice the strip. An
800 × 61 000 px strip is ~49 Mpx, so parent + 8 segments is ~90–230 MB depending on JPEG quality:
**over the default at every plausible quality**, and a strip big enough to need a 1 GB cache is not
unusual in webtoon.

The failure is not "out of space". `ensureSegment` re-cut the whole image and wrote **every**
segment, the LRU evicted the parent as it went, and the next request found the parent gone —
so `internalLoadPage` **re-downloaded the entire strip**. That is the churn: a page that never
settles, re-cut and re-fetched for as long as the reader sits on it, and it looks exactly like
defect 1's wedge from the outside.

`ensureSegment` is now adaptive:

- First cut writes every segment — strictly better when the strip fits, and how it always worked.
- If the segment asked for is **not** in the cache afterwards, the strip has proven it is larger
  than the cache. That image is remembered, and later cuts restore **only the band asked for**
  (`TallPageSplitter.split(only = …)`, which decodes one band instead of a dozen).
- `MAX_SEGMENT_RECUTS` bounds it, so a hopeless strip fails visibly (falling back to the whole
  image → decoder refuses >16384 px → ErrorPage) instead of re-downloading forever.

> **The real fix is still the cache size.** Adaptive cutting stops the loop; it does not make a
> 61 000 px strip fit in 75 MB. **More → Reader → Image cache size** is the lever — 250 MB for a
> 61 k strip, 500 MB+ past 100 k.

> **A segment's `Ready` status is not a promise the cache keeps.** That mismatch is what made
> defect 1 fatal; see the ladder section above.

### `Ready` does not mean readable

`internalLoadPage` ends with `page.stream = { cache.getImageFile(key).inputStream() }` and
`page.status = Ready` **without ever reading it**. The stream is a lambda evaluated later, and
the chapter cache is an LRU that evicts independently of the page list — so a page can go Ready,
sit through an eviction of its own bytes, and then throw `FileNotFoundException` from `stream`.

That surfaces as `no-stream` on the decode side. It is recovered from twice now: once via
`retryPage` (correct while the cache is intact) and once, on a repeat, via the cache-evicting
requery above.

### A split is all-or-nothing

`TallPageSplitter.split` is what turns a long webtoon strip into pages at all: the decoder
refuses >16384px and the renderer refuses to resize above 8192px, so a tall strip *must* be cut.

It used to skip a segment that failed to decode (`?: continue`) and still return `true`. That
is silent corruption, because the caller then builds the chapter's page list from whatever
arrived and labels each segment by **list position**, while the segment's real number is baked
into its cache key and recovered from it by `loadSegment`. One missing chunk and those two
identities diverge — a strip rendered with a hole in it, which no viewer can detect.

Both halves are now guarded: the splitter returns `false` on the first segment it cannot decode
(and recycles the bitmap in a `finally`), and `splitOversizedPage` orders by the index in the key
and requires a gapless `0..k-1` run before accepting the split. Either failure falls back to the
whole image, which is slow but correct.

OOM is the realistic cause of a failed segment — which is precisely the condition a long strip is
already under, so this path matters most exactly when memory is worst.

### A segment's height is fully determined — use that to identify one

`TallPageSplitter.segmentBounds` bisects, so **every band of a given strip has a height computable
from the strip height alone**, and the set is tiny: at 800px wide, H=61000 yields eight bands of
7625 and nothing else. Sweeping H from 8193 to 400000, **no strip produces a band of 1391** — the
bisect remainder structure makes it unreachable.

So a `Create tile 0 0 800 1391` in a log full of 7623/8142 segments is **not a segment**. It is a
page of ordinary webtoon proportion that happened to be mid-churn, and a useful tell is that it
got no `Create mipmap` line while the surrounding tiles did: the tile was allocated and discarded
before its mip chain was built.

Reach for this before theorising about the splitter when a band height looks wrong — the
arithmetic settles it in one pass, and a "weird" height is evidence *against* a split, not for a
bug in one.

### `segmentNumberOf` returns -1, not 0

A key that names no segment must fail a contiguity check rather than silently colliding with
segment 0. Do not "simplify" it back to `?: 0`.

### The contiguity check compares against a List, not `indices`

`keys.map { segmentNumberOf(it) } != keys.indices` **compiles, logs every split as
non-contiguous, and rejects all of them.** `IntRange` is an `IntProgression`, not a
`kotlin.collections.List`, so `List.equals(IntRange)` is always `false` — the guard fires
unconditionally and no tall page is ever split. The fallback hides it: the page still renders
from the whole image, so it looks like a slow reader rather than a dead code path. Compare
`keys.indices.toList()` instead. This is the same shape as the `combine`/`associateTo` traps
above — a type mismatch that a compiler will not catch.

## SYMPTOM TABLE

| Symptom in logcat | Where to look |
|---|---|
| `No surface texture: Error` repeating from the first frame, screen black, no crash | The swapchain is wedged (PowerVR field signature). The renderer now escalates: `swapchain error streak ... recreating the surface` after 30 failed frames, `parking until the surface changes` after 120. If you see endless spam with no recreate line, the escalation was reverted — check `surfaceErrorStreak` in `WebGpuRenderer.render`. |
| `Decode skipped ... state=DECODING` storms next to an `Evicted`/`Queued` loop | A page evicted between its queue insert and the worker pop kept DECODING — the one state `queueForDecode` refuses to re-queue. Both worker exit paths now reset DECODING unconditionally (the page is out of the worker's hands either way). If it returns, check the skip path's reset in `startDecodeWorker`. |
| `adapter is "consumed"` | Device is permanently dead. Nothing above it recovers. Check `recreateDeviceLocked` re-requests an adapter. |
| `WebGPU not available: ...` from `decodeReaderPage` | `requireAvailable()` failed inside an upload. Same root cause; it is a *symptom*, not the bug. |
| `Re-driving ... last=no-stream` | `page.stream` returned null — the source/loader has no bytes, not a decode failure. Follow `askLoaderToResolvePage`. **If it repeats, the page is probably wedged in LOADING** — see *Page loading*. |
| `Re-driving ... last=no-stream` forever, same page, **no "Giving up on" ever** | Both halves of the `no-stream` ladder were inert: the retry was filtered out by the loader's `status == Queue` guard, and the re-query reset `attempts` so escalation never terminated. Fixed in both — see *Page loading*. Before that fix, this was the signature of a long-strip segment whose cache file the LRU had evicted. |
| A long strip renders with a chunk missing, or scrambled, but no error anywhere | `TallPageSplitter.split` reported success with fewer segments than bounds — now impossible; if you see it, check `segmentNumberOf` and the contiguity guard. |
| Scrolling down skips several pages at once, or `getPage(0)` names a page the reader is no longer on | A placeholder resolved *smaller* than its slot. `clampScrollWithinPage` in `captureRenderState` is the only thing that keeps them on the page. |
| Content below the reading point shifts as pages decode (continuous mode) | Not a transform bug — the continuous walk never reads `page.scale/x/y`. The only thing that changes when a page resolves is its **slot height**: a placeholder reserves one viewport height, the real page re-measures it (growth keeps the reading point inside the slot, shrink overshoot is clamped). If it is *not* the placeholder swap (the content jumps with no decode nearby), look at the library's walk — not at `applyZoomPolicy`, which short-circuits for continuous; see the zoom-stack landmine. **Since the 2026-11-09 freeze, a placeholder slot can no longer track the surface** (that was the "first page runs away while scrolling" bug — see the landmine): a jump with no decode nearby now means a width change (the library's proportional rescale) or a walk defect, never surface-height churn. |
| Page index and the index in the segment cache key disagree | They are separate identifiers; `splitOversizedPage` now orders by the key and refuses a non-contiguous run. |
| Page spins on the placeholder forever, no log line at all | Should now log `Re-driving ... loading-stalled`. If it still does not, the page is not LOADING — check `wantedByRender` and whether `imagePage` is really a `ProgressPage`. |
| `Re-driving ... last=threw` | The decode threw; read the exception above it in the log for the real reason. |
| `Re-driving ... last=unfinished` | The worker left the page mid-flight without completing a trace. Usually cancellation. |
| `DecodeTrace STALL` | The watchdog saw a page past its stage deadline. The worker cannot report its own stall. |
| `Device not available` / blank pages after backgrounding | Device loss; check the recovery path above, not the page. |
| `F/libc Fatal signal 11 (SIGSEGV), ... fault addr 0x0 ... (WebGPU-Render-T)` with `Java_androidx_webgpu_GPUQueue_submit` above `submitAndRelease` in the tombstone | Device loss landed **mid-frame**: `render()` passed its entry `isAvailable` gate, then the draw suspended (tile generation), and the loss invalidated the native queue before `submitAndRelease`. Submitting on the dead handle is a null-deref inside `libwebgpu_c_bundled` that kills the process — no Kotlin exception can catch it. `render()` re-checks `isAvailable` immediately before the submit and drops the frame; the residual recheck→JNI window cannot be closed from Kotlin (UNVERIFIED how wide). Same rule as `cleanup()`: once the device is lost, nothing drives its objects. |
| `F/libc Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) ... (WebGPU-Render-T)` at `Java_androidx_webgpu_GPUQueue_submit`, preceded by a barrage of `GraphicBufferAllocator: Failed to allocate (4 x 4) layerCount 1 format 59 usage b00: 7` / `AHardwareBuffer ... failed` | **A frame landed while the window was being torn down.** `ReaderActivity` used to declare no `configChanges`, so rotating recreated the activity; it now does, so rotation is a surface resize through `WebGpuRenderer.resize()` (no teardown, no re-init — this in-flight wedge was the reason). Recreation still happens for other causes (e.g. locale change) — there, Compose's `dispatchSurfaceDestroyed` cancels the render job but does not wait for it, and the framework dismantles the window — its BufferQueue and every gralloc buffer behind the swapchain images (the format-59 BLOB failures) — as soon as the surfaceDestroyed callbacks return. A cancellation only lands at a suspension point, so a frame already past its last one (the fast tile path suspends only while generating) ran its whole `render` tail — submit and present — into that teardown, and the bundled Dawn aborted inside the submit. Fixed library-side: `ViewerSurface` registers `Surface.onDestroyed` (which runs on main *before* Compose cancels, while the framework is still holding the teardown off) and `WebGpuRenderer.detachSurface` sets a `surfaceDetached` gate plus drains the in-flight frame on the render mutex before returning. If it ever reappears, check that the destroy callback registration survived and that `render()` still gates on `surfaceDetached` at entry and at the submit. |
| `device lost reason=1: CreateAndroidSurfaceKHR failed with VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`, preceded by `BufferQueueProducer ... connect: already connected` and `vulkan: native_window_api_connect() failed` | A **second** swapchain was created on a window the old one still owned. Dawn can defer `vkDestroySurfaceKHR` past `close()`, so recovery must drop the old `GPUSurface` *and* release the retained `ANativeWindow` (`NativeWindow.release`) **before** it builds the replacement — see `recoverFromDeviceLoss`. The `SurfaceTexture-0-…` name means the TextureView path (API < 34), where the window is a `SurfaceTexture`'s BufferQueue. **If it still appears on a current build, the surface-teardown pump failed — see the landmine below.** |
| `recovery failed (consecutive: N, next attempt in Xms)` | A recovery produced no usable device; the library ladder is escalating. Consecutive counts reset only after a device survives 15 s. Each failed recovery used to also leak a whole Dawn device (no finalizer on the wrapper) — retired devices are destroyed one recovery cycle later now (`destroyRetired`, with `destroy()` alone; `close()` on a retired device corrupts a still-pending loss record), so a persistently climbing `consecutive:` with native memory growth is the trace to send. |
| `recovery deferred Xs (consecutive failures: N)` | A recovery attempt arrived inside the ladder's cooldown and was refused without touching the GPU. Normal while the ladder is escalating; a fresh loss event or a stable device resets it. |
| `recovery gave up after N consecutive failures` | The ladder stopped self-retrying. The reader stays on a dead device until a *new* device-loss event arrives (which restarts the ladder) or the viewer is recreated. Everything app-side keeps working; only WebGPU draws are parked. Since the strike system: a session that ends in this state records one `WebGpuHealth` strike, and two struck sessions make the next session open on the legacy viewer instead. |
| `parking until the surface changes` (see the swapchain row above) and the session then closes without ever recovering | The PowerVR black-screen shape: the device is *alive* (`isAvailable` stays true the whole time), so the dead-device strike never fired and the session used to close looking healthy. The renderer now counts parks (`surfaceParkCount`, instance-scoped on `WebGpuRenderer` — read it through `pager.state.renderer`, not the companion), and a session that ends having parked records one `WebGpuHealth` strike too, so persistent parkers fall back to the legacy viewer on later launches. |
| A field report says the reader "logs nothing" — and the user enabled **verbose logging** (Settings → Advanced) before capturing | Verbose logging wires the **OkHttp logging interceptor only** — it captures HTTP traffic and has nothing to say about a decode/render/GPU failure. The reader's own diagnostics live in logcat (`WebGpuRenderer`/`TileRenderer`/`DecodeTrace` greps, below), and the app-side degraded-session path is mirrored into the in-app file log by `WebGpuHealth.recordStrike` (xLog) since 2026-11. Do not send a user down the verbose-logging path for a render defect. |
| A field report from a **preview/beta build** shows no `RPCExternalAsset`/`DiscordRPCService` lines at all | The Discord RPC port logs through **Timber**, which was planted only in debug builds — every RPC failure (including the external-asset HTTP errors behind "presence has no images") was a silent no-op outside debug. `Timber.plant` is unconditional in `App.onCreate` now. Any future Anikku-style port should assume the same: library code that logs through a facade must have that facade planted in every build type. |
| `Evicted ch=X/i=N` → `Queued ch=X/i=N` → `Decode skipped ... cached=false` repeating every ~15 ms for the *same* page (the 2026-11-04 field log shows hundreds of cycles) | The evict↔recreate livelock: the shell was the evictor's cheapest victim *and* the render walk kept re-demanding it, so insert evicted it, the walk rebuilt it next frame, and the decode queue gained a dead entry per frame while every pop skipped it — the page never decoded and the cache never settled. Fixed twice over: `evictFarthestPage` no longer ranks wanted-undecoded shells as victims, and `queueForDecode` drops a shell that is no longer `pageInCache`. If it returns, check both halves. |
| `Downsampled ch=X/i=N to WxH (budget N px)` + a `downsample` trace stage | Normal, not a defect: the page exceeded `Budget.maxDecodedPixels` (display-derived, tier-capped, pressure-scaled) and was resized before upload instead of eating ~600MB of GPU textures. Pages of one chapter may differ in resolution across the session as pressure moves the ceiling — that is the budget's byte-scale philosophy applied to pixels. If the same page repeatedly downsampled *and* the reader still loses the device, compare the `px<=` figure in `MemBudget` against the page's actual area. |
| A single page draws at a few dozen pixels after surface churn (e-ink refresh-mode switches, sleep/resume, or a fast back-then-forward on a small cache) | A **degenerate-scale latch**: the surface reported 0x0 in a teardown/relayout window and one of the scale paths latched the 0.01 sentinel. Three latches existed; all closed — `resize`/`init` refuse to store sub-8px dimensions (library `WebGpuRenderer`), the page's parent-setter home init defers instead of latching (library `ImagePage`, re-applied by `attach`), and the app's `applyDoubleTapZoomPolicy` no longer reads `homeScale` from a parentless page. If a page still lands tiny, check whether `page.scale` itself holds ~0.01 and which of the three wrote it. |
| `Transition ensureTextures clamped WxH -> W'xH'` | The device's `maxTextureDimension2D` is below 8192 (4096 on PowerVR-class Compatibility-mode adapters) and the transition cache was sized for a larger surface — now clamped so the turn renders. Before the clamp the symptom was `ensureTextures create failed` **every page turn, for the whole session**. |
| `FilterChain acquire clamped WxH -> W'xH'` | The same device cap reached through the filter pool. Before the clamp, `beginFrame`'s catch silently disabled every filter for the rest of the session. |
| Blank/garbage page in dual-page mode only | Check `spreadPage` lifecycle — see landmines. |
| Pages decode then vanish when scrolling back | Eviction. Check `isOnScreen` immunity and `pageDistance` anchoring. |
| Saved position / read flags lag behind actual reading — by pages, or whole chapters; the Manga Details screen updates only after leaving and re-entering it | The reader's per-crossing progress write in `ReaderViewModel.updateChapterProgress` used to be gated on `page.status !is Page.State.Error`, and continuous mode scrolls straight through pages the stuck-page ladder has given up on (`GONE` → `ErrorPage`): every crossing over such a page dropped its write, and a chapter crossed past its last page never turned `read`. The write no longer depends on the page's load state — only the completion side-effects do. If writes look correct but still lag, check the continuous `onPageChange` walk resolving what the strip actually showed (see *Reading-position restore*). |
| Same page stuck forever, no ErrorPage | `wantedByRender` is still set and `imagePage` is still a `ProgressPage` — the sweep only sees that one shape. |

## LOG TAGS / GREPS

Library logs under `WebGpuRenderer` and `TileRenderer`. App logs go through `logcat {}`, so
the tag is the **coroutine or thread** name (`StandaloneCoroutine`, `VRI[Reader...y]`, …) —
filter on the message text, not the tag.

```bash
adb logcat | grep -E "WebGpuRenderer|TileRenderer"
adb logcat | grep -E "Re-driving|DecodeTrace STALL|Giving up on"
adb logcat | grep -E "WebGPU not available|adapter is"
```

**Before trusting a log to locate a bug, check the build against the submodule.** Symptoms
observed on an older APK can come from renderer code that no longer exists — a `grid LRU`
line, for instance, does not appear anywhere in the current tree.

## KNOWN LANDMINES (each one was a real bug)

- **Never pin the display's top refresh mode for a static page.** `RequestMaxRefreshRate` set
  `preferredDisplayModeId`, which is what *stops* an LTPO panel dropping to 60 Hz — and it used to run
  once, unconditionally, when the viewer composed. A reader spends most of its session looking at a
  page that is not moving, so the panel's most expensive state was held for pixels that never changed.
  It is now `RequestMaxRefreshRateFor(motion)`, and the motion signal is the viewer's own state:
  `isFlinging || isPanning || isScaleAnimating` in continuous, `animationJob?.isActive == true` in
  paged. Releasing means assigning `0` to `preferredDisplayModeId` ("let the system choose"), never a
  lower mode id — the latter would lock a 60 Hz panel at 60 Hz, which is the opposite of the intent.
  **Rule: a display-mode preference is a request that must be withdrawn, not a capability to claim.**

- **A trim callback is not a screen-off callback.** `TRIM_MEMORY_UI_HIDDEN` fires whenever the reader
  is backgrounded, including a plain screen lock. The WebGPU viewer deliberately keeps it out of its
  device-cache branch, because `TileRenderer.onLowMemory` pins `cacheScreens` to 0.8 and the resulting
  rebuild would run on every unlock. `ReaderActivity.readerTrimCallbacks` follows the same rule: it
  forwards only the *severe* levels (`RUNNING_LOW`/`RUNNING_CRITICAL`/`MODERATE`/`COMPLETE` and above)
  to `Viewer.onMemoryPressure()`, and leaves `UI_HIDDEN` to the viewer's own lifecycle hooks.
  **Rule: screen-off is an audience event (`onActivityStop`), not a memory event.**

- **A tier enum that carries its own ceilings cannot be shared.** The reader's `DeviceTier` held the
  per-tier byte budgets as constructor data. When Coil, the JXL cap and the RAM gates needed the same
  tier, the honest options were to move the enum *with* its data into `core:common` and leave a
  typealias behind (what happened), or to keep two enums of the same name — which cannot be compared,
  and silently binds a policy function in the reader's package to whichever is in scope. If a new
  consumer needs a tier property, it goes on the shared enum; an extension property would work for
  Kotlin callers but is module-`internal` and so invisible to the offline harness that asserts these
  numbers.
  **Rule: one vocabulary, one comparison. A second `DeviceTier` is a silent disagreement.**

- **Never let a 0x0 surface window write a scale.** The 2026-11 field report (e-ink reader:
  view a page, swipe back, swipe forward — the page now draws at ~50px) was a degenerate-scale
  latch, not a zoom bug: e-ink readers tear the surface down on refresh-mode switches, the
  surface reports 0x0 in that window, and `homeScale`'s guard for an unsized parent answers
  **0.01**. Three paths latched it: the library's `parent` setter initializing a pristine page
  to home while the renderer dims were 0; the renderer storing 0x0 from `resize`/`init`
  (making every later computation degenerate for the whole teardown); and the app's
  `applyDoubleTapZoomPolicy` reading `page.homeScale` at adoption, when the page's parent is
  still null. All three now refuse the degenerate value — dimensions below
  `MIN_SURFACE_DIMENSION` are not stored, the home init defers until real dimensions arrive
  (re-applied in `attach`, because a second attach is otherwise a no-op), and maxScale is not
  latched from a parentless page. The rule behind all three: **a sentinel fallback (0.01) may
  be returned, never stored.** The same shape bit once before (2fbe2de5b, sleep/resume) and
  was fixed for one door only — the renderer dims — which is why it survived.
- **Never enqueue a shell that is no longer `pageInCache`, and never rank a wanted-undecoded
  shell as an eviction victim — together they manufacture a frame-rate churn loop.** The
  2026-11-04 field log: one page (the tail of the previous chapter, farthest from the anchor,
  never decoded) was evicted at insert, rebuilt by the render walk next frame, evicted again,
  and gained a `Decode skipped ... cached=false` queue entry per frame for the whole session —
  every pop skipping, the page never decoding, the GC churning ("Skipped 33 frames"). Two
  rules came out of it: `queueForDecode` drops a page that is not the cache's copy (decoding
  it would upload textures nobody will draw), and `evictFarthestPage` treats
  `wantedByRender && !isDecoded` exactly like drawn pages — excluded from the ranked and edge
  passes, still reachable through the absolute fallbacks. Evicting an undecoded shell frees
  no bytes anyway, so the immunity costs the byte budget nothing.
- **The position store outranks `last_page_read`, so it has to be gated on it.**
  `WebGpuReadingPositionStore` keeps `pageIndex`/`documentY`/`zoom`/`offsetX` per chapter in the
  `webgpu_positions` prefs, because no DB column can express the last three, and
  `setChaptersInternal` reads it *before* `requestedPage`. That precedence is what survives a
  process kill — and what made a position reset from outside the reader invisible: marking a
  chapter unread zeroes `last_page_read` (`SetReadStatus` owns that, and has since upstream
  #7475) while the anchor stayed behind, so the chapter reopened at the page it had been stopped
  on. Only this viewer did it — pager, webtoon and novel restore from `requestedPage` alone,
  which *is* `last_page_read`. The store is now consulted only when `last_page_read > 0`.
  **Rule: a position reset from outside the reader has to overrule the store, and `last_page_read`
  is the only signal that can do it.**
- **`retryPage` is not eviction.** If the cache entry is the defect, `no-stream` → `retryPage`
  is a no-op by design and leaves the page wedged in LOADING with no timeout (see *Page
  loading*). `requerySuspectPage` is the one that evicts first.
- **The surface teardown in `recoverFromDeviceLoss` must precede the device rebuild.** The
  natural order — recreate the device, *then* drop the stale swapchain — builds the replacement
  on a window the old swapchain still owns, and Dawn defers `vkDestroySurfaceKHR` past
  `close()`, so it fails with `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` and loses the device it was
  trying to recover. Closing the `GPUSurface` is not enough; the retained `ANativeWindow` has to
  go too. **Releasing the references is still not enough:** the deferred destroy only runs when
  the instance's event loop is pumped, so both recovery and `cleanup()` now pump
  `instance.processEvents()` a few times after closing (`pumpSurfaceTeardown`) before anything
  may build a surface on that window again. Without the pump the failed build loses the *fresh*
  device at birth, its loss callback starts the next recovery, and the two other landmines in
  this file compound it into the unbounded recreate loop the field log showed (90 device
  generations, one leaked device each). Never add a `createSurface()` path that runs without a
  preceding pump.
- **Retiring a device is a two-phase free, and the second phase waits a full recovery cycle.**
  `recreateDeviceLocked` reassigns the companion's `device`/`adapter` — and the wrappers hold
  native references nothing but `close()` drops (see the submodule's `Release.kt`), so each
  recreation leaked an entire Dawn device. Recovery now parks the dead handles in
  `retiredDevice`/`retiredAdapter` and frees them at the *next* recovery's start: continuations
  that were mid-flight when the device died read `device` from the companion when they resume
  (see `onDispatcher`), so the safe free point is one full cycle later, under the render mutex.
  Freeing at reassignment time or from an arbitrary thread risks pulling the ground out from a
  resuming continuation.
  **And that free is `destroy()`, never `close()`:** the androidx.webgpu glue constructs its
  callback runnables *at dispatch time* from the global refs it holds for the wrapper —
  `DeviceLostCallbackRunnable` is built with `(callback, GPUDevice, …)` when Dawn flushes a
  pending loss record, which can be long after the device was parked (nothing drives a parked
  device, so its record rides until a later command flushes it). `close()` on a retired device
  deleted those globals out from under the pending record, and the next frame's `finish()` —
  which pumps the callback queue — aborted in `NewObjectV` with ART's
  `JNI ERROR (app bug): accessed deleted Global` (field: double device loss in one pressure
  era, Multilaser M8_4G / Android 11; tombstone: `submitAndRelease` → `finish$default` →
  `NewObjectV`). `destroy()` frees the GPU memory that fed the loss loop while the wrapper's
  global stays alive for any late dispatch, and the submodule's stale-fire guard keeps that
  dispatch from reaching the recovery ladder. `Release.kt` deliberately defines no
  `GPUDevice.destroyAndRelease()`.
- **`render()`'s `isAvailable` gate is a point-in-time check, not a lock.** The gate runs once
  at entry, then the draw suspends inside `fn` (tile generation waits on it) — a device loss
  landing in that window invalidates every device-bound handle *before* the frame's
  `queue.submitAndRelease`. The Kotlin `deviceLost` flag only flips when the loss callback runs,
  so nothing between the gate and the submit observes it, and submitting on the dead handle
  segfaults in native code with no exception path. `cleanup()` has always held the rule ("once
  the device is lost there is nothing to free"); `render()` now re-checks `isAvailable`
  immediately before the submit and drops the frame instead. **Rule: any native call after a
  suspending step inside a frame must re-assert the device is alive first — and never add one
  that assumes the entry gate still holds.** UNVERIFIED: the window between the recheck and the
  JNI call itself remains; only a native-side guard can close it.
- **The platform surface dies under a mid-flight frame, and Compose does not stop the frame
  for it.** Compose's `dispatchSurfaceDestroyed` invokes the `Surface.onDestroyed` hooks first
  and *then* cancels the render job — without waiting for it — while the framework dismantles
  the window (BufferQueue and every gralloc buffer behind the swapchain images) as soon as the
  callbacks return. Cancellation only lands at a suspension point, so a frame past its last
  one (the fast tile path suspends only while generating) submitted and presented into a
  window that was being freed underneath it: the rotation SIGABRT inside `GPUQueue_submit`,
  preceded by the format-59 BLOB gralloc failure barrage. The library now treats the destroy
  callback as the last wait before teardown: `WebGpuRenderer.detachSurface` (wired from
  `ViewerSurface`'s `Surface.onDestroyed`, registered before the host block's first
  suspension) gates new frames and drains the in-flight one on the render mutex under a
  bounded timeout before returning, and `render()` re-checks `surfaceDetached` at the submit —
  the same point-in-time rule as the device check above. **Rule: the framework only waits for
  the surfaceDestroyed callbacks to return, so anything that must not race the teardown has to
  be made true inside one.**
- **Never assume a queued page reached the loader.** `HttpPageLoader`'s collector is
  `.filter { it.status == Page.State.Queue }`, so offering a page in any other state is a
  silent no-op — no log, no work. Anything that re-offers a page must set `Queue` itself.
  This is invisible for an ordinary `Ready` page (its bytes are cached anyway) and fatal for a
  split segment, which is `Ready` *by construction* and whose file the LRU can evict.
- **Anything reached from `buildSpreadPage` runs once per rendered frame.** `fetchPage(0)`
  calls it every frame, so a counter charged at *request* time there is a frame counter, not a
  retry counter. The spread height-match budget is the one that already was.
- **`ViewerReaderPage.spreadPage` must stay `@Volatile`.** It is written by the render thread
  (`buildSpreadPage`) and cleared by `releasePageResources` from main (device-loss, stuck
  sweep, `onTrimMemory`). Unfixed, the render thread read back a spread whose textures were
  already released.
- **The spinner texture must not be handed back while the device is lost.**
  `WebGpuRenderer.device` keeps naming the dead device until recovery replaces it, so the
  `pineappleDevice === device` identity check cannot see the loss. `isAvailable` is the gate
  that can.
- **Never drop a spread or an `ImagePage` reference without `cleanup()`** — it owns uploaded
  textures and its tile grid. The library keys tile caches by page identity, so an abandoned
  spread keeps its whole grid resident and only a *destroyed* page sweeps it.
- **"Backgrounds first, then content" only holds inside `renderPass`.** The continuous draw splits
  content into two passes and only one of them obeys the ordering. `ImagePage.ImageSingle` pages go
  through `renderPass` and get the underlay-then-content treatment that fixed the seam line
  (`webgpuviewer-houri` @ `06d2b1001`). `ImagePage.Render` pages — `ProgressPage`, `TransitionPage`,
  `ErrorPage` — are drawn **after** that pass, each opening its own pass over the shared screen
  texture via `renderLoaded`. So they are the one kind of content in continuous mode that can still
  land on top of a neighbour's last row of real pixels, and they had **no underlay of their own**.

  The underlay loop now paints a Render page's background too, spanning its slot. The extent is the
  **slot** (`vp.docTop` + `vp.pageHeight`), never the page's own height.

- **A `Render` page must be drawn at its slot's scale, not at `s.scale * page.scale`.** This is what
  the seam line actually was, and it is intermittent for a structural reason: a `Render` page
  deliberately gets its own live pan/zoom (see `ImagePage.Render.drawLive`), so a pinch or fling
  running while a segment is still decoding is enough to move it off 1. `fillPage` paints
  `scale * page.height` tall while the document laid the next page out from
  `getPageHeight(page) * s.scale` - and those agree only at `page.scale == 1`.

  Off 1, taller repaints over the last row of **both** neighbours (it runs after all of them) and
  shorter leaves the difference unpainted. Either way: a thin line at every boundary the placeholder
  touches, on a long strip, only while a neighbour is decoding - which is why it reads as sporadic
  and shows between ordinary pages as readily as between segments.

  > **Rule:** in continuous mode the *slot* is the only size the document lays pages out from.
  > Anything drawn into it must be sized from the slot. Two code paths that size the same boundary
  > differently leave a fractional disagreement, and a fractional disagreement at a page boundary
  > *is* the seam.
- **The underlay must run before the mipmap-upload early return** (library
  `ImageViewerContinuousState.kt`, KMK island). Between decode (`isDecoded == true`,
  `imagePage` a fresh `ImageSingle`) and the first mip upload (`mipmaps.isEmpty()`), the
  continuous walk's image loop used to early-return *past* the underlay with content merely
  planned: the slot kept the transparent clear, bounded by two hard edges — a second seam shape,
  visible for exactly the frames the first upload takes. `renderSnapshot`'s `forEachImage` now
  paints the slot's underlay before checking what content exists. Same family as the two seam
  landmines above; the extent is still the **slot**.
- **The 2026-10 seam root cause: two anchors for one boundary.** Every earlier seam fix reduced
  the line's frequency because each addressed a *painter* for the boundary row, while the row kept
  going unpainted. The actual defect: `TileRenderer`'s continuous placement rounds the shared
  camera anchor (`snapX`/`snapY`) and hangs each grid's clip rect off it, but everything laid out
  in `renderSnapshot` — the underlay rect, `renderFast`'s image quad, a `Render` page's slot fill,
  the fade veil — hung off the **raw, unrounded** anchor. The two disagree by up to half a pixel;
  the pixel-center rasterization rule hands the boundary row to *neither* page whenever a pixel
  centre falls in the disagreement window, and the underlay shows through — whose colour is the
  reader background whenever the theme is not "automatic background", i.e. a thin black line
  between two white pages. It only fired for a **mixed** boundary pair (tiled vs. fast content
  while a neighbour decoded, or decoded vs. placeholder), which is why it read as sporadic and
  shrank with every fix that added another painter. `renderSnapshot` now rounds the anchor once,
  with the same `kotlin.math.round` over the same expression `continuousAnchor` uses, so every
  path claims bit-identical boundaries and the rasterizer's own rule gives each boundary row to
  exactly one page. **Rule: continuous content is placed from the snapped anchor, never the raw
  one — a second derivation of the same boundary is the seam, whatever paints it.**
- **The residual seam after the anchor fix: two float expressions for one boundary.** Snapping the
  anchor removed the half-pixel disagreement, but each page's clip edges were still that page's
  own derivation (`snapY + centerYOffset + halfB` for the upper page vs the next page's
  `centerYOffset - halfT`): the same boundary, computed twice, agreeing only to a few ULPs — and a
  boundary value that lands within that window of a pixel centre again leaves the row to neither
  page. The fix carries the rule to its end: `captureRenderState` **chains** the device-space
  edges through the walked band (each page's top edge is the previous page's bottom edge plus the
  device-space gap; the two walks share one base, the same snapped-anchor expression over
  `height`), and the tile path takes the chained pair via `TileRenderer.ClipEdges` (uncropped
  pages only — a crop's content edge is mid-slot and shares no boundary), as do the `Render` slot
  fill and the placeholder's own placement centre. **Rule: adjacent pages' boundary values must
  be *assigned* from one another, never computed twice from neighbours' inputs — the only value
  that cannot disagree is the one both sides share.**
- **The seam's last painters, chained too (2026-11-09): the fade veil and the per-image
  underlay.** After the chained clip edges landed, two boundary painters still derived their own
  row: `drawFade` painted `docTop .. docTop + pageHeight` (the pre-chain derivation — and it
  paints exactly when a page fades in after its decode, the placeholder→real swap, i.e. a mixed
  boundary pair), and the per-image underlay rect painted the image's own placement extent for
  uncropped pages instead of the slot. Both now consume `clipTopY`/`clipBottomY` (the underlay
  keeps the image-derived extent for a *cropped* page, whose content edge is mid-slot and shares
  no boundary). **Rule: any new painter of a page boundary consumes the chained edges — a fourth
  derivation reintroduces the line, and it will read as "sporadic" again.**
- **A placeholder's slot height is a property of the page, not of the surface (2026-11-09).**
  `ProgressPage.height` answered `pager.state.height` and `TransitionPage`'s answered
  `min(width, height)` live, so every undecoded slot re-derived from the surface every frame —
  and the surface height churns at open (0 → initial → insets-settled), when the menu bar hides
  (fullscreen immersive), on foldable posture changes and on e-ink refresh switches. The walk
  consumes scroll in slot units at crossing time, so the churn moved the content without anything
  scrolling: the first pages of a chapter "ran away" while the user scrolled through them, which
  is why it clustered at chapter opens and read as rare. Both placeholders now freeze on the
  first stable read (`reservedHeight`/`reservedSide`) and never re-derive. **Rule: never return a
  surface measurement from a placeholder's geometry — the decode swap to real heights is the one
  height change that is supposed to happen, and it is the one that is compensated
  (`clampScrollWithinPage`).** The width stays live on purpose: width changes are the library's
  proportional rescale, and a width-invariant placeholder slot makes that rescale a no-op for it
  (only decoded anchors rescale, whose document height genuinely scales with width).
- **Predicted aspect outranks the freeze, and the freeze is only the fallback (2026-11-10).**
  `ProgressPage` resolves its height in three steps: predicted aspect
  (`viewportWidth * h / w` — the exact expression the walk computes for the decoded
  `ImageSingle`), then the frozen viewport read, then the live read. Two producers feed the
  prediction: a split segment is born with its exact band size (`TallPageSplitter.split`'s sink
  now carries the band dimensions, and `splitOversizedPage` writes them into
  `ReaderPage.predictedImageSize`), and every ordinary page learns its dimensions when the
  pipeline's VALIDATE stage reads the image header — one stage before the decode. With a
  prediction the decode swap changes no geometry at all and the clamp has nothing to hold; the
  freeze exists only for pages whose bytes have not arrived yet. The prediction rides the
  current viewport WIDTH (width-proportional, like a decoded page) and stays immune to the
  surface-HEIGHT churn above. If a placeholder still reflows at its decode swap, check whether
  the prediction actually reached it: a segment's `predictedImageSize` is set at split time, an
  ordinary page's at VALIDATE — a page that misses both falls back to the freeze and owes its
  one reflow to the clamp, exactly like before the prediction existed.
- **Double-tap opt-out is one flag with a tied shadow** (port of upstream `34fb12b` in the
  library's `ImageViewerState.kt` KMK island). The app sets `doubleTapZoomEnabled`
  (`WebGpuViewer`/`WebGpuViewerContinuous`, from `WebGpuConfig.resolveDoubleTapZoom()`), and the
  same setter keeps `doubleTapGesturesEnabled` equal to it — so with the pref off, a paged tap
  fires on release (`waitForDown` is gated) and the continuous early-return key checks both flags.
  Setting `doubleTapGesturesEnabled` directly defeats the tie; always go through
  `doubleTapZoomEnabled` and let the setter do the work.
- **Never reuse a GPU adapter** (above).
- **Never call `WebGpuMemoryBudget.sample()` from a getter.** `cacheSize` is read by the render
  walk; `sample()` is a binder call. The snapshot exists so hot paths only ever read a volatile
  field.
- **The byte budget cannot evict a drawn page.** `evictDownToBudget` asks the ordinary evictor
  and stops when it stops making progress. Adding a cutoff that bypasses `evictFarthestPage`
  destroys pages that are on screen — and, via the spread/`cleanup()` rules above, textures
  something else still holds.
- **`decodedBytes` is set inside the same lock that installs `imagePage`,** and zeroed only in
  `releasePageResources`. A reader that sees the new image and the old zero lets the budget admit
  one more oversized page than it can hold.
- **`registerHdrSdrRatioChangedListener` throws unless `isHdrSdrRatioAvailable`.** Gating
  registration on a non-null display alone logs `IllegalStateException: HDR/SDR ratio changed not
  available` with a full stack trace on every surface creation of an SDR-only device.
- **Do not merge `PagerPageImageProcessor` and `WebtoonPageImageProcessor` back together.**
- **Per-frame GPU allocation churn is a library question, not an app one — and its ring now has a
  frame contract.** For a long stretch the continuous underlay built a fresh `GPUBuffer` + bind
  group per background rect per frame; the render engine now serves them from a count reset each
  encoded frame (`RenderPage.drawMaskedRect` + `RenderPage.beginFrame()`, called from
  `WebGpuRenderer.render`). When a report looks like native memory growing only while the reader
  is open, look at the library's per-call GPU object creation first — `Draw.rect`/`Draw.circle`
  still allocate per call on purpose (their per-frame count is unbounded; transitions and
  placeholder pages use them, and those paths are transient). The submodule-side rules live in
  `external/webgpuviewer-houri/library/src/main/java/ca/mpreg/webgpuviewer/renderer/AGENTS.md`.
- **The zoom/fit stack is paged-mode work; continuous mode must not run it.** `applyZoomPolicy`
  short-circuits on `isContinuous` (WebGpuZoom.kt), and before that guard every decode paid the
  wide-zoom/fit-mode policies' `pager.state` reads, a dozen transform writes and up to four
  bounds walks on the worker's single thread — for properties the continuous reader never reads
  back. That is the load-bearing fact, verified in the library: the walk sizes every slot from
  the page's own dimensions (`ImageViewerContinuousState.getPageHeight` reads only
  `page.width`/`height` and its crop) and places content with its own math, so
  `page.scale`/`x`/`y` are inert there; and `ImageViewerState.getPage` → `attach()` supplies
  `parent`/`scope`/`onInvalidate` for every page it hands out, so `isOnScreen` and the
  drawn-page eviction immunity work without the app touching them either. **Rule: when
  continuous-mode content appears misplaced, look at slot heights (the placeholder/real swap
  family), never at page transforms — there is nothing there to fix, and applying a transform
  there is a decode-thread cost with no reader.**
- **One header parse per page.** `PageBytesValidator.inspect` is the only signature/dimension
  reader in the decode path. The JXL oversize gate keys on its verdict
  (`verdict.format == ImageFormat.JXL`), not on a second `ImageUtil.findImageType` pass over the
  same bytes, and it runs *before* the upscale hook, so a page refused for its JXL size never
  pays native inference first. Wave ordering note: a `JXL too large ...` throw now surfaces
  between the `validate` and `upscale-check` stages — the stage any trace last names for it is
  `validate`.
- **The working set has one release body, and `cleanup()` still waits for the lock to end.**
  `rebuildPageCache`, `destroy` and `resetDecodedPagesAfterDeviceLoss` share
  `releaseAllShellsAndClearResidency` (WebGpuPageCache.kt): the snapshot-and-release walk plus
  the map clears they used to hand-copy. A new piece of bookkeeping that names shells has to be
  cleared *there* — that is the drift this closes (`requeryRecords` and the stuck-sweep re-arm
  stay at the device-loss site on purpose; the other two do not want them). For the same
  lock-free reason as [releasePageResources], the adoption swap in `decodeReaderPage` releases
  the replaced page *after* its `synchronized` block, not inside it — folding it back in is the
  frame-jank shape this was made lock-free to avoid.
- **`ViewerPage.key` is computed once, at shell construction.** `ViewerTransitionPage` and
  `ViewerReaderPage` build their `PageKey` in their own constructors and `pageKey(page)` just
  reads `page.key` — sound while `Page.index` stays `val`, `ReaderChapter` stays a data class
  whose identity is its `chapter` value, and renumbering happens by building fresh shells (as
  split reconciliation does). If `ReaderChapter` grows a mutable field the key reads, or pages
  are renumbered in place against existing shells, every memoized key lies — and eviction, the
  stuck ladder, decode traces and `PageListWatch` all key on it.
- **`WebGpuExtensions.active()` is a cached snapshot — state changes must clear `activeCache`.**
  The double-checked `@Volatile` snapshot replaced a per-decode-call walk that re-counted the
  installed extensions every time; `install`, `uninstall` and `setEnabled` are the three
  invalidators. A new site that flips an extension's enabled state (a migration reinstall, a
  settings import) must invalidate too, or the reader acts on a stale set for the life of the
  process.
- **Do not grow `WebGpuViewer.kt` past wiring.** A new state machine belongs in its own file
  as a `WebGpuViewer` extension.
- **The adapter's `maxTextureDimension2D` can sit below every app-side 8192 constant — tiled
  upload is the reason tall pages still work.** Compatibility-mode PowerVR adapters report
  4096, while app constants (`SPREAD_MAX_DIM`, `UpscaleScalingStrategy.MAX_DIM`,
  `HttpPageLoader.maxSegmentHeight`, the decoder's ~16384) read as if 8192+ textures existed.
  They are safe only because every page upload flows through the tiled Mipmap path, whose tile
  size follows `WebGpuRenderer.maxTextureDimension2D` (re-queried from whichever adapter is
  live, including after device recovery). The library clamps everything it sizes off a surface
  or page dimension the same way — surface (`surfaceDimensionCap`), transition caches, filter
  pool, tile atlas, font atlas — and anything new on either side that sizes a texture must do
  too; anything that would *upload* a texture larger than the limit without going through
  tiling is the bug class to check. Full rule + degradation semantics:
  `external/webgpuviewer-houri/library/src/main/java/ca/mpreg/webgpuviewer/renderer/AGENTS.md`.

## OFFLINE TESTS — the policy this folder can prove without a device

`scripts/kotlinc-check/run-harness.sh` compiles these files on a plain JVM and drives them. Each
one is a file with **no Android import**, which is why the policy was extracted into files of its
own rather than stubbed: a stub would be a *copy*, and asserting a copy proves nothing about the
thing that runs.

| Harness | Asserts |
|---|---|
| `DecodePriorityHarness` | `decodePriorityFor` + the real `PriorityDecodeQueue`: class ordering, distance clamping, the direction flip (travelling-behind beats idle-ahead only within the boost window), `Int.MIN_VALUE` staying positive |
| `StuckLadderHarness` | the escalation table, the cooldown, the fresh-window boundary, that a full ladder terminates and reaches GIVE_UP exactly once |
| `SpreadMathHarness` | the height-match plan (never shrinks the taller side, clamps to the upload ceiling), degenerate dimensions as noops, the rescale gate |
| `MemoryBudgetHarness` | the tier fold (RAM, low-RAM mark, 32-bit cap), pressure thresholds and their ordering, every floor, the JXL-below-page invariant at every tier and pressure, the 4/3 mip measurement — **plus** the shared action policy: hysteresis, cadence, refresh-rate, RGB_565 and JXL-dimension rules |
| `DeviceMemoryHarness` | the app-wide half: the same fold and ceilings as seen from `core:common`, the 48MB RGB_565 threshold, the JXL dimension cap's monotonicity, and that the fallback is a real 4GB device rather than a silent downgrade |
| `ImageValidationHarness` | every signature `PageBytesValidator` accepts (JPEG/PNG/GIF/BMP/WebP/JXL/AVIF/HEIF/TIFF/ICO), dimensions where the header is cheap, the refusals it exists for (an HTML body served as a page), and that pathological headers terminate |
| `ExifRotationHarness` | all eight orientations: dimensions, hand-computed corner pixels, and the composition identities (5∘5, 7∘7, 6∘8, 6∘6 = half turn) |
| `ChapterPreloadGuardHarness` | one winner per key, release and re-begin, stale requeue, blank/oversized keys, the key-set cap |
| `ColumnWidthHarness` | the continuous reader's column width: percent-per-side padding, monotonicity, the 20% floor, degenerate screens |
| `DiagnosticsHarness` | the session report: a clean session is not notable, every anomaly is, the bounded rings stay bounded, and `render()` never throws |

Two of these found real defects on the day they were written, both of the "silent, looks like
something else" kind:

- **A little-endian TIFF was never recognised.** `sniff` read the byte-order magic with `beU16`
  for both `"II"` and `"MM"`, so the `II` half compared `0x2A00` against 42 and failed. Every
  little-endian TIFF — most of them — was refused as `not an image` before the decoder saw it,
  which presents as an unsupported-format error on a format the decoder handles.
- **The memory fallback demoted the device** (see the memory-budget section).

Run them with `scripts/kotlinc-check/run-harness.sh` (all) or
`scripts/kotlinc-check/run-harness.sh MemoryBudget` (one, matched by substring).

## SETTINGS — which ones the WebGPU reader honours, and why one cannot be

The reader has its own `WebGpuConfig`, so a preference in the legacy viewers' groups reaches it
only if this folder reads it. An audit of every `readerPreferences.*()` call in `ViewerConfig` /
`PagerConfig` / `WebtoonConfig` against the whole `webgpu/` package is what produced this table;
re-run it before assuming a row is still true.

| Setting | Honoured here by |
|---|---|
| Viewer navigation (tap zones) | `WebGpuConfig.applyNavigationProfile()` — **resolved by reading mode**: the continuous modes read `navigationModeWebtoon` / `webtoonNavInverted`, the paged ones `navigationModePager` / `pagerNavInverted`. It used to read the Pager pair for every mode, so the Webtoon group's switch did nothing while a strip was open. |
| Invert taps (`smallerTapZone`) | `ViewerNavigation` itself — shared, automatic |
| Crop borders | `WebGpuConfig.imageCropBorders` — **resolved by reading mode**: Pager group for L2R/R2L/vertical, `cropBordersWebtoon` for WEBTOON, `cropBordersContinuousVertical` for CONTINUOUS_VERTICAL. The last two were previously unread, so the Webtoon group's switch was inert. |
| Webtoon side padding | `WebGpuColumnWidth.resolveColumnWidth` + the pager's layout width inside `WebGpuViewerContinuous.columnFrame` — see below |
| Double-tap / pinch zoom, zoom-out, transitions, cutout, page offset, gap, dual-page pairing | `WebGpuConfig`, as documented in the sections above |

**Side padding is a column width, not a margin on each page.** The library sizes every page to the
surface it renders into and has no per-page width, so "narrower" has exactly one expression from this
side: give the pager a smaller layout width inside a full-screen `FrameLayout` and centre it. The
frame is always in the hierarchy (padding 0 means full width), so turning the setting up is a layout
change rather than a re-parenting. Two consequences worth knowing:

- **Taps in the margin do nothing.** The margins belong to the frame, not to a page. At the setting's
  maximum (25% per side) that is a deliberate column, the same shape as the legacy viewer's
  page-frame margin.
- The width comes from the **frame**, not `displayMetrics`, so a split-screen or free-form window
  pads the window it actually has. The frame is `MATCH_PARENT`, so its width does not depend on the
  child's and re-reading it cannot oscillate.

**`webtoon_scale_type` (and its `longStripGapSmartScale` gate) is inert here, and cannot be faked.**
It is defined against a *paged* viewer: the legacy listener scales the recycler so a page's rendered
width becomes `pageHeight * ratio`, which needs the page's own height — a per-page value. The WebGPU
reader has no paged webtoon mode (both WEBTOON and CONTINUOUS_VERTICAL are continuous there) and its
library scales every page to one surface width, so a fixed column could only approximate it by
narrowing *everything*, including the tall strips the setting exists to leave alone. If it is ever
implemented, it belongs in the library as a per-page width, not here as a column.

**Two more legacy groups are deliberately not reproduced:** `centerMarginType` (the pager's SSIV
display margin, which has no counterpart in this reader's fit policy) and the webtoon dual-page
toggles (`dualPageSplitWebtoon` and friends — continuous mode has no spreads at all).

## DIAGNOSTICS — the session record a phone-only user can send

`WebGpuDiagnostics` aggregates a bounded set of counters as the session runs and renders them as a
few lines: decodes by outcome with total/max duration, downsamples, decode stalls, evictions plus a
worst-churn count, device losses, recoveries, swapchain parks, stuck-ladder rungs, and each distinct
memory budget. Nothing here can alter the reader — the same contract `DecodeTrace` has.

It is the answer to the gap `WebGpuHealth.recordStrike` found the hard way: every diagnostic in
this folder went to logcat, and the person who sees the bug is holding a phone with no logcat. So:

- a **notable** session (a loss, a failed recovery, a park, a downsample, a decode stall, eviction
  churn, or a `GIVE_UP`) writes itself to the in-app log when the reader closes, via `xLogE`;
- a **struck** session carries the rendered block under the strike line, because a strike says
  *that* a session went wrong and the block says what it did;
- **Settings → Advanced → Dump crash logs** includes it, so the dump a user already knows how to
  produce answers a reader question too.

A clean session writes nothing to the in-app log: the log is read by someone chasing a problem.

## WHERE THINGS ARE

| Concern | File |
|---|---|
| the class: fields, wiring, navigation, lifecycle | `WebGpuViewer.kt` |
| page shells, spread slot, neighbour links | `WebGpuPageModel.kt` |
| residency, eviction, split reconciliation, render walk | `WebGpuPageCache.kt` |
| what the worker owes a page + the stuck ladder | `WebGpuDecodeQueue.kt` |
| the ordered queue itself (generic, harness-verified) | `PriorityDecodeQueue.kt` |
| decode priority policy (pure, harness-asserted) | `WebGpuDecodePriority.kt` |
| stuck-ladder escalation table (pure, harness-asserted) | `WebGpuStuckLadder.kt` |
| device tier + pressure + ceilings (pure, harness-asserted) | `WebGpuMemoryMath.kt` |
| hysteresis, cadence, refresh-rate + parallelism policy (pure) | `WebGpuResourcePolicy.kt` |
| app-wide tier vocabulary + fold (pure, harness-asserted) | `core/common/.../util/system/DeviceMemoryClass.kt` |
| app-wide ceilings + JXL/RGB565 policy (pure, harness-asserted) | `core/common/.../util/system/DeviceMemoryMath.kt` |
| the `ActivityManager` read + published snapshot | `core/common/.../util/system/AppMemoryMonitor.kt` |
| spread geometry (pure, harness-asserted) | `WebGpuSpreadMath.kt` |
| continuous column width (pure, harness-asserted) | `WebGpuColumnWidth.kt` |
| session diagnostics (bounded counters → in-app log) | `WebGpuDiagnostics.kt` |
| the stage pipeline behind a decode + the placeholder's status line | `WebGpuPagePipeline.kt` |
| bytes → GPU image for one page | `WebGpuDecodePipeline.kt` |
| the long-lived coroutines (decode worker pool, liveness sweep, progress spinner) + stall watchdog | `WebGpuViewerWorkers.kt` |
| progress / error / transition placeholders | `WebGpuPlaceholderPages.kt` |
| spread pairing + height-match maths | `WebGpuSpread.kt`, `WebGpuSpreadHeightMatch.kt` |
| preference → filter/transition wiring | `WebGpuViewerImageState.kt`, `WebGpuConfig.kt` |
| device profile + live byte budget | `WebGpuMemoryBudget.kt` |
| the memory watcher loop + trim wiring | `WebGpuMemoryMonitor.kt` |
| render engine | submodule `external/webgpuviewer-houri` |