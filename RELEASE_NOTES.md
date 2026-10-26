<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

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

### Improve
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
