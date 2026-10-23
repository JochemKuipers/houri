<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Subcategory types**: when creating a subcategory (Edit Categories or from library
  selection) you can label it Author, Artist, Illustrator or Designer, so two same-named
  subcategories under one parent are tellable apart; the label shows as a colored tag in
  Edit Categories and in the Set Categories dialog.

### Improve
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
