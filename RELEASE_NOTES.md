<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New
- **EPUB books can now be read as books.** A new built-in **EPUB library** source appears in Browse.
  Drop a folder of `.epub` files under your local source directory (in a `novels` folder) and each
  folder becomes a series, with one chapter per book inside it. Previously an EPUB could only be added
  as a manga, where it was read as a pile of illustrations and the text was never shown.
- **Reading appearance settings for books**: font, font size, font scale, text colour, background
  colour, smooth text, justified alignment, hyphenation, line spacing, paragraph spacing, character
  spacing, and left/right/top/bottom margins. Alignment, hyphenation and vertical writing each have
  a **"Follow book"** option, so a book that already looks right keeps its own styling and you only
  change what you want changed.
- **Vertical writing (縦書き)** is supported, which Japanese and Chinese novels usually expect.
- **Your place in a book is remembered by where you got to, not by page number.** Text reflows
  differently on every screen size and at every font size, so remembering "page 4" dropped you
  somewhere different each time. Progress now follows the actual paragraph, and the percentage shown
  reflects how much of the book is really left rather than how many files it has.
- **Books with a table of contents can be navigated by it**, and text can be searched within the
  current book.
- **A switch for the cover loading spinner** on a series' details page, in Settings > Appearance >
  Manga Info. On by default, as before; turn it off to get Mihon's plain skeleton placeholder
  instead of the spinner while the cover loads.

### Improve
- **EPUB files are parsed properly now.** Titles, authors, publishers and descriptions appear instead
  of coming out blank for books that used their own namespace prefixes; the contents page is read from
  both modern and older EPUB formats; embedded images, stylesheets and fonts load; and a DRM-locked
  book now says so plainly rather than opening to a run of blank pages. Font-obfuscated books - the
  common "protected" kind - read normally, as they always should.
- **The rotating daily and weekly challenges now actually rotate.** Only the three and four
  achievements currently on show made progress - everything else in the rotating pool was still
  being counted in the background and quietly unlocking itself the first time you happened to do
  the thing, so the pool drained instead of turning over. The library's Achievements tab now puts
  the active ones first, marks the rest as "not in rotation", and shows a real progress bar
  ("3/5") instead of a bare number. The rotation also rolls over when you open the app rather than
  only when you happen to visit the tab.
- Rotating progress is no longer lost when an unlock is refused, and two achievements marking at
  once no longer overwrite each other's count.

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
- **Finishing a series counted again every time you reopened it.** Re-reading the last page of a
  series you had already finished counted another completion, so "Complete 5 manga" could be earned
  from a single series opened five times. Starting a real reread of a series releases the claim, so
  finishing it again counts as the new work it is.
- **The three "complete a manga in <mode>" achievements unlocked from a single chapter read.**
  Left to Right, Right to Left and Vertical Scroller all say "complete a manga", and unlocking any
  of them needed only the first chapter read in that mode. They now unlock when a series is actually
  finished, and the LTR weekly tier counts completions rather than chapters. "Versatile Reader"
  (finish the same series both as a pager and as webtoon) was unreachable and now works.
- **Incognito reads never counted.** The reader is the one achievement source that fires in
  incognito, but its hook sat inside the block that deliberately skips incognito - so Shadow
  Reader and the incognito daily challenge could not be earned at all. Library counts (organic
  chapters, streaks, binge) are still left alone, as intended.
- **Panorama Lover could not be earned.** The double-page hook existed but nothing ever called it.
  It now fires from the one place both the pager and WebGPU viewers report a two-page spread.
- **Reread Nostalgia, Eternal Return and Living Library were unreachable.** They are lifetime tiers
  ("reread 5 / 20 / 100 manga") but were checked against the size of a single rereading action,
  which is always one. They now read a lifetime counter.
- **"Perfect Week" rewarded ordinary reading.** Its requirement was seven days on which you read
  *anything*, ever, rather than seven series finished in seven days. It now measures the latter, and
  "Binge Guilt" (a 100-chapter day followed by three days of nothing) works too.
- **Speed Reader could not be earned.** Nothing ever started the clock it measures. Finishing a
  20+ chapter series inside an hour now works, timed from your first chapter read of that series.
- **A day with a skip still earned "Perfect Day".** That challenge reads "10 chapters without
  skipping" and nothing recorded the skipping half of it.
- Two challenges matched the wrong hours: Morning Pages fired before 6 AM rather than before 8, and
  Midnight Pages fired in the small hours rather than after 11 PM. "Streak Keeper" also unlocked on
  the first day read rather than the second.
- Eight more achievements had no way to be earned and now do: Cutout Connoisseur, AST Searcher,
  Offline Polyglot, Tag Explorer, Translation Sprint, Perfect Week Challenges, Double Mango and
  Pineapple King.
- A wipe now also clears the new completion bookkeeping, so re-earning after a wipe behaves like a
  fresh install instead of being silently skipped.
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
- **Back on a screen opened from an extension dropping you into the Library.** Open a source from
  Browse and press back and you were sent to the Library tab, with the source screen still sitting
  behind it - so the next back press walked you back into the source you had just left. The
  "back goes to the Library tab" shortcut was competing with the back stack pop and could win the
  press; it now takes it only when there is nothing left to pop.
- **Migrating a single entry was a dead end that called the series "not found".** With "Use smart
  search" off - the default - migrating one entry opened the migration list, which immediately
  opened a search screen of its own on top of it. The list underneath was already showing "No match
  found" for the entry, because the smart search had been skipped on purpose, so it claimed nothing
  matched on any of your sources however many you had configured. Getting out took backing out of
  the search and then answering an exit dialog, and it could simply drop you back into the search.
  A lone entry with smart search off now opens the search screen itself, which searches every
  source you selected for migration, and one back press leaves it.
