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
- Restoring a backup is dramatically faster on large libraries. Restoring a backup with tens of
  thousands of series spent most of its time on work that had nothing to do with the data itself:
  it posted a progress notification once per entry (each one a round trip to the system UI), it
  looked up reading history two database queries per history row instead of two per series, it
  re-read the whole category list and the whole merge table for every series, it rebuilt the
  edited-titles cache once per series when a backup carries an edit for most of the library, it
  compared known URLs against a list instead of a set when ordering the restore, and it kicked off
  a full library refresh while the restore was still writing - both fighting over the same
  database. All of that is now proportional to how long the restore takes rather than to how many
  series it contains.
- Category sorting now reaches the subcategories too. Pick an order in Edit Categories and the
  Library's subcategory chips, its folder layout and the category filter list all follow it, at
  every level and without breaking the parent-child nesting.

### Fix
- **Marking a chapter unread not resetting the position in the new reader.** Read a chapter partway
  through, mark it unread, and the WebGPU reader reopened it at the page you had stopped on. The
  saved page was being reset correctly, but that reader also keeps the page, offset and zoom in
  its own store, which is how it restores your place across a process kill - and it was trusting
  that copy without checking that a position still existed. The other three readers were already
  correct.
- **Every list losing its place on the way back.** Opening a series from a browse list or a search
  and pressing back dropped the list at the very top instead of where you left it. The screen was
  coming back under a saveable-state key the app had never seen, so its scroll position - and
  anything else it remembered, such as how far a collapsed list was - started over. The fix keeps
  one slot per screen so it finds its own state again, while still giving two copies of one screen
  separate keys, so re-opening a settings screen quickly cannot crash.
- **History opening chapters the merge hides.** Continuing a series from History could hand back an
  upload the details screen does not show. With scanlator A holding chapters 1-2 and B holding
  1-70, finishing A's chapter 2 and resuming from History opened B's chapter 1 instead of chapter 3.
  History now uses the same rules as the details list, the reader and the library's continue button.
- **Library badge and History progress ignoring the smart scanlator merge switch.** Turning the
  merge off un-merges the chapter list, so every upload is a chapter again - but the badge and the
  History "read of total" kept counting one chapter per number, so two scanlators each holding
  chapters 1-10 showed 20 chapters in the list and 10 on the badge. Both follow the switch now,
  which also fixes the unfinished-manga filter in History.
- **Blacklisting one upload of a chapter a scanlator published twice.** Blacklisting a single upload
  instead of every upload of that chapter and scanlator left it in the counts: read the version you
  had blacklisted and the chapter still counted as read while its unread twin was the one on screen.
  The badge and the History progress now honour both forms of blacklist entry.
