<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **The WebGPU reader now sizes its memory to your device and to what is actually free.** Previously
  the reader only knew one thing about your hardware — whether it had less than 3 GB of RAM — and
  made every caching decision from that single yes/no. It now measures your phone's real size class
  and keeps an eye on how much memory is genuinely available, adjusting how many pages stay decoded
  and how much tile cache the renderer keeps. A phone with 12 GB that is currently memory-starved
  behaves like the small phone it is right now, and recovers when memory frees up. If you have been
  manually shrinking caches or avoiding long strips on a low-memory device, this should need less
  fiddling.

### Improve

- **Memory pressure now recovers instead of leaving the reader permanently hobbled.** A single
  system memory warning used to permanently reduce the renderer's internal tile cache for the rest
  of the session, with nothing able to bring it back — so one unlucky warning while using another
  app cost you smoother zoom and scrolling until you closed the reader. The reader now restores the
  full cache once memory returns, after a short settle period so it cannot flicker between states.
- **The reader now reacts to memory pressure between system warnings.** Android only sends its
  memory warnings when it is already deciding which app to reclaim, which is usually after the
  reader has already absorbed the spike. A separate background check now watches for pressure
  directly, which matters most for very tall webtoon strips — each one costs roughly 47 MB of
  graphics memory.

### Fix

- **A harmless HDR warning was logged as an error on every screen change.** On displays that do not
  support HDR brightness tracking at all, the renderer logged an exception with a full stack trace
  each time a page was opened. The display was answering correctly; the renderer was asking about a
  feature that device does not have.
- **Some large pages failed to open, showing only a black screen with
  `resize: dst too large`.** The WebGPU renderer capped the first mip-map step at a much smaller
  size than the rest of the pipeline accepts, so any page between roughly 16 and 64 megapixels —
  a large scan or double-page spread — passed every size check and then failed outright while
  building its zoom levels. The limit now matches the rest of the pipeline, so those pages open.
- **Long webtoon strips could get stuck on a loading placeholder and never recover.** After
  scrolling far into a strip, one page would spin forever and the reader would keep retrying it
  in the background. The retry was silently dropped by the page loader, so the page's cached
  image was never re-cut, and the internal retry counter reset itself each time so recovery
  never gave up. Affected pages now reload, and a page that genuinely cannot load stops retrying
  instead of spinning for the rest of the session.
- **Long webtoon strips no longer re-download themselves over and over.** A tall strip needs
  roughly twice its own size in image cache — its original plus the pieces it is cut into — so on
  the default cache size it could re-fetch and re-cut the whole strip every time you scrolled,
  leaving a page that never settled. Segments are now restored one at a time when the cache is too
  small to hold them all, and giving up is bounded and reported rather than looping.
  **Tip:** raising *More → Reader → Image cache size* above the default (250 MB and up) is still
  the best fix for very tall strips.
