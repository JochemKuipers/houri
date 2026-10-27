<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Book reader: paged and two-page modes**: books (EPUB) can now be read like a real paper book —
  open the reader settings while a book is open and pick **Paged** (one page per screen, turned
  with a tap, the volume keys or a flick of the slider) or **Two pages** (the classic open spread,
  two columns with a proper gutter) beside the original continuous scroll. Page turns respect the
  book's own writing direction, so a Japanese vertical-writing novel turns the way a Japanese book
  does, and changing the mode keeps your place in the book.
- **Book reader: cover as first page**: most EPUBs keep the cover in their metadata rather than in
  the book's spine, so it was never shown once you started reading. The reader now opens on the
  book's declared cover when there is one (on by default, with a setting to turn it off), and never
  shows it twice for books that already carry a cover page. Applies to the next chapter you open.
- **Navigation 3**: the app's entire UI now runs on Jetpack Navigation 3 (androidx.navigation3),
  replacing the retired Voyager navigator — every screen is a serializable route on one back
  stack, tabs keep their own per-tab stacks on tablets and phones alike, and the two-pane
  settings layout is now a real Navigation 3 scene (list on the left, category on the right on
  tablets). This matches upstream Mihon's migration and should improve predictive-back
  animations and state restoration (upstream mihonapp/mihon#3984). All screens now use AndroidX
  ViewModels with Metro DI instead of Voyager ScreenModels, and the Llamatik demo app submodule
  also no longer depends on Voyager.
- **Subcategory types**: when creating a subcategory (Edit Categories or from library
  selection) you can label it Author, Artist, Illustrator or Designer, so two same-named
  subcategories under one parent are tellable apart; the label shows as a colored tag in
  Edit Categories and in the Set Categories dialog.
- **Book reader: custom CSS & JavaScript injection**: the reader's appearance tab has a new
  **Custom CSS & JS** section — write your own stylesheet or JavaScript and the reader injects it
  into every book. Custom CSS is on by default (it starts empty and can only restyle) and your
  rules are applied after the reader's own, so they win ties; edits apply to the open book the
  moment you confirm them. Custom JavaScript is **off by default** and its toggle carries the
  warning: injected scripts run inside every book you open, so only enable it for code you trust.
  Scripts run in each document right after the reader's own bridge, so a change takes effect from
  the next page you turn.

### Improve
- **WebGPU reader: the between-pages black line, fixed at the root**: the thin dark line that
  could appear between pages in long-strip mode was two layout paths disagreeing by up to half a
  pixel about where one page ends and the next begins — the one that drew sharp tiles rounded the
  boundary to a whole pixel, and everything else (backgrounds, freshly-decoded pages, loading
  placeholders) did not, so the boundary row could be claimed by neither and showed the background
  instead. Every page's boundary is now derived from the same whole-pixel position, so the line
  has nothing left to feed on. It previously appeared rarely, mostly while a neighbouring page was
  still loading — which is exactly when the two disagreeing paths met.
- **WebGPU reader: pages decode in parallel**: the reader now decodes two pages at once instead of
  one, so the page you are looking at no longer queues behind whatever slow page the reader was
  already deep into (a long strip, a large JXL, a slow network read). Small devices stay
  single-threaded on purpose, where two parallel decodes would cost the memory the reader is
  budgeting to protect.
- **Book reader: smoother page turns**: building a book page's markup (which inlines its
  illustrations) now happens off the main thread, so turning the page in an illustrated book no
  longer hitches, and the reader warms the next document's text ahead of you — both in paged mode
  and while scrolling continuously.
- **Book reader: vertical-writing books scroll correctly**: a Japanese or Chinese novel set in
  vertical writing flows sideways, and the reader's page-step used to scroll it down — never
  moving, so every tap skipped a whole section. The step now scrolls the axis the book actually
  uses, and the tap zones follow the writing direction.

- **Database (under the hood)**: the library database now uses Mihon's reworked schema, so the
  fork stays mergeable with upstream. Updating merges duplicate entries — the same series twice
  under one source — into the copy in your library, carrying read state, history, categories and
  tracking to the survivor, and chapter listings can no longer split one chapter into two rows.
  The Updates tab and the widget's unread count also read from the partial unread index instead
  of scanning every fetched chapter, which makes them noticeably snappier on large libraries
  (upstream mihonapp/mihon#3805, mihonapp/mihon#4022).
- **Set Categories**: the dialog now has the same navigation aids as Edit Categories —
  a search field, a sort dropdown (manual / name / date-added / date-modified), and
  expand/collapse chevrons with Expand all / Collapse all, so large category trees are
  browsable without leaving the dialog.
- **Anime-Planet**: rewired against the site's current pages — refreshing now reads your status,
  chapter progress and score back from your Anime-Planet list (true two-way sync), and freshly
  bound entries pick up the manga's total chapter count.
- **WebGPU reader**: loads pages faster — each page's header is sniffed once instead of twice,
  an oversized JXL page is refused before the upscale pass runs instead of after it, and a
  decode adopting its image no longer releases the page it replaced while holding the
  bookkeeping lock (less hitching while many pages resolve).
- **WebGPU reader**: more decode-path speedups — page bytes reach the native decoder with a
  single copy (byte-array ingest) and in 64 KB stream reads instead of 8 KB, its JNI bindings
  are resolved once per process instead of per page, EXIF rotation is a straight-line pass
  instead of a per-pixel formula dispatch, and two per-page lookups (WebGPU availability, page
  identity keys) are cached.
- **Import books**: the file picker now offers only EPUBs — plus the generic
  `application/octet-stream` spelling most file managers give an `.epub`, so folders do not
  appear empty — instead of every file on the device.

### Fix
- **Crash right after onboarding**: finishing onboarding — or tapping the finish button a touch
  too eagerly, twice in the same moment — could crash the app with "NavDisplay backstack
  cannot be empty", because the second tap popped the home screen itself off the navigation
  stack. The finish action now only removes the screen it owns, and the home screen is
  re-seeded as the bottom of the stack before it is ever rendered empty, so no double-tap
  anywhere in the app can leave it rootless (the same class of fix as the back-press one
  below).
- **Legacy reader: zoom settings are honoured**: in the classic pager and long-strip readers,
  a **double tap followed by a pinch zoomed in even with pinch-to-zoom disabled** — the
  pinch was misread as a double-tap-and-drag gesture, which the setting does not govern. The
  two are now told apart for real. Also, with **double tap to zoom** turned off, dragging
  after the double tap still zoomed (the WebGPU reader already treated that setting as
  covering the whole double-tap gesture); and on animated (GIF) pages in the pager,
  **disable zoom in** and the double-tap setting previously had no effect at all. All three
  now answer the same setting the same way in both readers.
- **Crash on back at the home screen**: pressing back — or double-pressing it quickly — could
  crash the app with "NavDisplay backstack cannot be empty". Navigation 3 can deliver a back
  press whose handler is one frame out of date, and the app's own back handlers took that press
  at face value and popped the home screen itself off the stack; every remaining entry then had
  nothing to stand on. The home screen, the tabs and the tracker sheet now treat their root as
  the floor: a back press at the very bottom is left to the system instead of emptying the
  stack.
- **Crash after tapping a source badge on a manga page**: opening a source from a manga that
  had no matching source screen below it in the stack used to silently discard the home screen
  while rebuilding the stack, leaving the app rootless — the next back press then crashed it the
  same way. The home screen is now the floor for that rebuild too, so the source screen simply
  opens on top of it.
- **WebGPU reader crash while backgrounding**: losing the GPU device in the middle of drawing
  a frame — which is what backgrounding a low-memory phone does — could crash the whole app
  with a native `SIGSEGV` from the WebGPU queue: the frame had already passed its "is the
  device alive" check, and the loss landed while it was still drawing, leaving the submit to
  run on a dead handle that nothing in Kotlin could catch. The device is now re-checked right
  before the frame is submitted and the half-drawn frame is dropped, letting the normal
  device-loss recovery take over instead of taking the process down.
- **Rotation**: rotating the device (or rotating in the reader and then leaving a chapter) no
  longer drops you back on the Library tab — the tab you were on, along with the scroll
  position and other saved state of every screen, now survives the orientation change.
- **Upscaling (no-MTL build)**: the Upscaler category is back in Settings — the no-MTL build
  offers Simple upscaling (with the per-manga "Upscale Manga" toggle), but its settings category
  was accidentally hidden along with the AI-translation ones, so global upscaling could never be
  turned on.
- **On-device LLM (mtl)**: an `external/llamatik` submodule checked out past the pinned
  commit no longer breaks the `:llamatik-native` build with a bare "Cannot find source file /
  No SOURCES given to target: llama_jni" — CMake now detects the missing JNI sources and
  reports the one-command fix instead; the submodule pin itself was restored.
- **Google Drive**: a revoked/expired refresh token no longer fails every sync attempt
  forever — the app signs itself out cleanly so you can sign in again; and on devices
  without a browser, tapping Sign-in toasts a friendly error instead of crashing.
- **Anime-Planet**: rating writes now hit the site's real `rate` endpoint and use its half-star
  (0.5–5.0) scale — scores previously never persisted.
- **Anime-Planet**: starting a re-read no longer marks the entry "Won't Read" on the site.
- **EPUB books**: a book's description no longer prints raw HTML — the `<p>` tags and `&#8212;`-style
  entities that producers author into it now show as readable prose with proper paragraph breaks
  in the manga details screen.
- **Migration**: edited series details now follow the entry when it is migrated or copied to another
  source — a manually cleaned title, corrected author/artist, description, tags or status used to
  stay behind on the old entry (edits are stored against the entry's ID), so the migrated one came
  back with raw source metadata.
- **Local source**: deleting an EPUB or a local manga's downloaded chapters no longer shows a cryptic
  "Failed to determine if … is child of …" error — and it now disappears from the chapter list on
  the first delete instead of needing a second attempt. The post-delete refresh used to race the
  file deletion and open the file mid-delete.
- **Find duplicates**: the toolbar no longer overlaps the list — its rows and the "Find duplicates"
  title used to stack on top of each other in the screen's top-left corner, leaving the entries
  unreadable underneath.
- **Achievements**: unlocked cards' animated gradient now follows the card's rounded corners
  instead of painting sharp square corners that stuck out past every other card in the grid.
