<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New
- **Extensions can update themselves.** A new setting in *Advanced → Extensions* installs new
  versions of your extensions in the background, on a schedule you pick (every 6 hours to weekly).
  It is only available with the **Private** or **Shizuku** installer, because those are the two
  that can install without showing a system dialog — with the other two the row says so instead of
  quietly doing nothing. Off by default.
- **Both built-in libraries can now be refreshed from Browse.** Open the Local or EPUB library and
  use *Refresh library* in the overflow menu: it re-reads the folder on disk and re-syncs every
  series you have in your library, so a newly added book shows up without waiting for a library
  update run.
- **A reading mode can be set per category.** Category manager → overflow → *Reading mode* pins
  every series in that category to a viewer (webtoon for a long-strip shelf, for instance).
  Individual series keep their own mode, and a subcategory can override its parent.

### Improve
- **The EPUB library is visible without hunting for it.** Both built-in libraries bypass the
  "other" language filter in the source list, global search and the migration source picker, so
  your local library can be searched and migrated into regardless of which languages you have
  enabled.
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
- **The app no longer crashes on launch if the About screen was the last screen you were on.**
  Voyager saves the screen stack between launches, and the About screen's `readResolve` handed back
  something that is not serializable, so restoring it threw during every cold start that had it in
  its history.
- **Finishing a series is now recognised the moment you finish it,** rather than only after you
  closed and reopened the reader. The reader was judging "have I read everything" against the
  chapter list as it was when the reader *opened*, so the chapter that completed the series still
  counted as unread.
- **The "new manga" notification no longer fires once per chapter.** It appeared for every chapter
  you finished in your first sitting on a series, for the same stale-list reason as above.
- **Swiping back to the last page no longer re-fires the chapter-complete webhook or sound.** The
  completion ran every time the last page was *shown*, including the swipe back that re-showed it.
- **The spoiler warning now judges chapters by number, not by list position.** It could only ever
  fire for whoever finished chapter one, and mislabelled a legitimate read of the last chapter of a
  partially-read series.
- **Stopping a reread restores what you had actually read.** It used to restore *everything*: it
  guessed from the last-fetch timestamp, which marking a chapter read or unread does not change, so
  every chapter of the series looked older than the reread. A series with 10 of 50 chapters read
  went to 50/50 the moment you hit Stop.
- **The reread chip on a series opens the right dialog.** While a reread was running, the chip said
  "Rereading" but opened the *Start* dialog, whose confirm then did nothing.
- **Sequels & Prequels no longer shows relations from a series that does not have them.** If the
  tracker bound to a series reported no relations, the app carried on asking other trackers and the
  MangaDex fallback, and showed whatever they answered under that series — including a "Source"
  that the bound tracker does not list at all. Now the first tracker that answers is taken at its
  word, and a series with no relations shows no row.
- **Anime-Planet now saves your score.** The rating was passed to the API layer and never sent, so
  a score picked in the app never reached the remote list. Status and progress already did.
- **Comick now reads back what it stored.** Its update ignored the server's reply, so a score or
  progress the site had not accepted stayed in the app looking saved. The first write of a track —
  on binding — also left the rating out entirely.
- **Incognito reading is detected again,** so the achievement that counts incognito chapters can
  unlock. The reader resolved the flag once, before the series had loaded, and so only ever saw the
  global switch — never the per-extension one.
- **Achievement streaks count properly.** Every reading streak was compared against *today* rather
  than against the previous day, so any run of three or more days broke on the third day and
  "Three Day Run" — and everything above it — was unreachable no matter how consistent you were.
- **Achievement days follow your calendar, not Greenwich's.** Streaks, daily counters and the
  rotating achievement pool all rolled over at midnight UTC, so for most of the world they turned
  over during the evening — and the weekend window opened on Friday night.
- **The "read 5 today" and "search 5 times" achievements now need five.** Six of the rotating daily
  goals fell through to the default threshold of 1, so the first chapter of the day unlocked a tier
  described as five.
- **Rotating achievements now record anything at all.** They were only ever drawn when you opened
  the achievements screen, so until you did, every count was thrown away and none of the daily or
  weekly cards could unlock.
- **Daily achievements reset independently.** They shared one day stamp, so the first one to be
  updated each day reset only itself and the others carried yesterday's total into today.
- **The "switch reader direction 20 times" achievement now counts switches.** It counted the
  opposite — rebuilding the viewer in the *same* direction, which happens on every chapter — so
  reading a series straight through filled it and genuinely flipping the direction never did.
- **Reading time is no longer rounded up to a whole minute per chapter.** A chapter abandoned
  seconds in still counted as a minute, which reached the reading-time tiers — and the rank that
  reads the total — far faster than the time you actually spent reading.
- **The WASM engine runs the module it downloads** instead of returning the input backwards. It
  compiled the module and discarded it, so the "WASM Whisperer" achievement unlocked on any trigger
  and a caller that sent the result to a site got a value with the right shape and the wrong
  contents.
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
