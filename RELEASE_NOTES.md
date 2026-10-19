<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Book reader**: *Import books* in **Settings → Book reading** copies one or more EPUBs off your device into a new folder in your local source, under a name you choose. Several files at once become one series. Files that are not EPUBs are skipped and reported, and an existing folder of the same name is never overwritten.

- **Books (EPUB)**: Added a **Book reading** category to Settings, so book typography — font, size, colours, spacing, margins, hyphenation, vertical writing — can be changed without opening a book first. It is the same set of settings as the *Reading appearance* tab inside the reader, and the two stay in step.

### Improve

- **Reader**: The chapter list you open from the reader now has a draggable scrollbar on the right, like the library and every other long list. Previously, getting from chapter 1 to chapter 400 meant flicking.

### Fix

- **Library**: A chapter name from a book now shows on two lines instead of one, so a long title like *Harry Potter and the Prisoner of Azkaban* is recognisable instead of a fragment.
- **Book reader**: The vertical chapter-progress bar counts up from the top again. It was mirroring with the comic reading direction, which put 100% at the top — right for a right-to-left manga, wrong for a document, where progress always runs from the first line down.

- **Tracking**: Keeping up with a MangaBaka series that is still releasing or on hiatus no longer marks it as Completed. Reaching the latest chapter of an ongoing series used to flip your status to Completed every time the chapter count grew, and the entry could never go back to Reading afterwards.
- **Tracking**: Logging out of a tracker now also clears the scores, statuses and progress it had saved locally for your library. Signing out only dropped the credentials, so the next login brought the whole tracked list straight back.
- **Smart categories**: Subcategories bound to a tracker status now only move a manga that is already somewhere under their parent. A series filed under Shounen no longer gets dropped into `Josei → Reading` as well just because both subcategories share a status; being in both branches still updates both.
- **Browse**: Scrolling down a source's results and coming back from an entry or the reader no longer snaps the list to the very top. The list's scroll position was tied to whichever layout was on screen, so it was lost whenever the screen briefly showed its loading state or changed display mode.
- **Book reader**: Tapping a picture inside a book zooms it, centred on the point you tapped. Tap it again to put it back. The page keeps scrolling and text stays selectable, and if anything goes wrong the tap falls through to scrolling exactly as before.
- **Book reader**: Scrolling a book no longer jumps around. Sections were being prepended above you at a height measured before their illustrations had loaded, so the page shifted under you as pictures appeared; sections that carry their own stylesheet also lost it, and were laid out in the previous section's type.
- **Book reader**: A new *Paragraph indent* setting, so first-line indents can be turned off or left to the book. The custom font and background colours you asked for alongside it were already there.
- **Migration**: The *Select sources* list no longer offers every E-Hentai language at once. Each language is its own live source, so entries filed under a language you had switched off still put a row there; only the languages you have enabled are listed now.
- **Book reader**: The reading-appearance settings now show the values you have actually saved. They opened on the defaults and only corrected themselves once you touched something, which made saved settings look like they had not stuck.
- **Book reader**: Picking a font you imported no longer prints your own CSS as a paragraph of text at the top of the page. The `@font-face` rule was being placed loose in the document's `<head>`, which the renderer moved into the page as visible text — so the font never applied either.
- **Library**: Covers in JPEG XL no longer slow the library down. Cover colour sampling asked the system decoder for an image it cannot read, and retried on every scroll because the colour it wanted never arrived. Libraries with `.jxl` covers should now open noticeably faster.
- **Local source**: E-books and single-file comics dropped straight into the local source folder now show up in *Browse*. Only folders were listed, so a `.epub` with no folder around it was invisible.
- **Library**: *Find duplicates* no longer lists the same pair of entries twice. The duplicate check matches in both directions, so every pair was shown once from each side; three mutually-duplicated entries produced three rows for a single thing to review.
- **Books (EPUB)**: Books now show their real title, author, publisher and language instead of their file name. Almost every EPUB was affected: the metadata parser read one field and then swallowed the rest of the book's metadata block, so a title, author and date declared in the file were silently discarded and the filename stood in for all of them.
- **Books (EPUB)**: Fixed a table of contents that listed only every other chapter. Some books showed a handful of entries out of hundreds; others showed them all, which is why it looked like a problem with particular files rather than with the reader. Partly-nested contents (a part heading with chapters under it) lost the nested chapters entirely.
- **Reader**: Long-pressing inside a book (EPUB/novel) no longer opens an image toolbar — Set as cover, Copy, Share, Save — on top of the text-selection toolbar. Long press in a book now only selects text, the way it does in any other reader.
- **Reader**: Fixed a crash when tapping any page action (Set as cover / Copy / Share / Save) while reading a book. A book's pages are text and have no image to act on, so every option in that toolbar threw a `NullPointerException` and closed the app.
