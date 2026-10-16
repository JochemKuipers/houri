<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

### Improve
- **Restoring a backup is faster again, on large libraries.** Two things were making a big restore
  slow. The first was one database transaction per series: the restore opened and committed a
  transaction for every single entry, each with its own hand-off to the database writer, so a
  library of tens of thousands of series spent most of its time committing rather than writing.
  Entries are now written in batches of 250 in one transaction, with a single bad entry still
  falling back to being restored on its own so one failure cannot take a batch with it. The second
  was that the whole backup was decoded into memory before anything was written; it is now read
  straight off the file a few entries at a time, which also means a large backup no longer has to
  fit in memory all at once.
- **The library count after a restore is counted in SQL** rather than by reading every series back
  out of the database just to measure the list.

### Fix
- **A thin line between pages in the long-strip WebGPU reader is fixed.** It came and went, and
  showed up most often where a page had not finished loading yet. The loading placeholder was drawn
  at a slightly different height from the space the reader had set aside for it, so it either
  painted over the row of real artwork above it or left a gap.
- **Bookmark colours now show in the reader's chapter list and in the Updates tab.** Both were
  dropping the colour on the way to the screen — the reader's list because its chapter model never
  carried the field, and the Updates list because its bookmark icon was always drawn in the theme's
  default colour. Colours set on a chapter show everywhere the bookmark does.
- **EPUB books open in the book reader.** The reader picked which viewer to use before it had loaded
  the chapter's contents, so it could never tell a text chapter from a picture one and every book
  fell through to the image viewer.
- **Coming back from a series no longer jumps to the top of the list** in Browse, and the same for
  any other list you come back to from a details screen.
