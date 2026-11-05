<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Built-in Komga source**: browse, search and read straight from a Komga server without installing an extension. EPUB books open in the book reader (downloaded to the app cache once, then read offline), comics read as image pages, and reading progress syncs through the existing Komga tracker. Configure the server address (and optional API key) in the source's settings.
- **Page-turn animations for books** (paged reading): the old page now slides off the edge you are heading for, dissolves, or swings over on its spine — with an adjustable speed. Off by default; pick one in Reading appearance → Page turn.
- **Footnote popups in books**: tapping a note reference now shows the note beside the text — a small window wearing the book's own typography, images included — instead of turning to the notes document. Notes that live in another file are read out of the book on the spot; anything that cannot be shown falls back to the ordinary link behaviour. Toggleable in Reading appearance.
- **Auto-scroll for books** (continuous reading): the page scrolls itself at a pace you set — dp per second, from a slow drift to a brisk walk — pausing the moment you touch the screen, and running off the end of a chapter exactly as a finger drag would. In paged mode the same toggle becomes Moon+'s timed page-turn.
- **Word spacing and text-outline** settings for books (Reading appearance), alongside letter spacing and italic — Moon+ Reader's typography set, engine-native.
- **Status line for books**: a quiet line at the top or bottom of the page showing the clock, your progress through the chapter, the chapter's name and the battery level, each toggleable, with its own size and colour. Off by default.
- **Webnovel plain-text support**: sources that serve chapters as plain text now render as real paragraphs — line breaks become paragraph breaks, and three cleanup passes (intelligent paragraphs, blank-line removal, space trimming — Moon+ Reader's suite) strip the artefacts of machine-assembled text. Each pass is toggleable in Reading appearance → Webnovels.
- **Highlights and selection actions in books**: select any text to get four pen colours, a copy action and a look-up (the system web search, or ColorDict/GoldenDict when installed). Highlights persist per chapter, survive font changes and reflow, and re-mark themselves on every page. Tap inside a highlight to remove it. Toggleable in Reading appearance.
- **Read-aloud for books**: the window's text is spoken by the system text-to-speech engine, in order, with an adjustable rate — and it chains across the book, turning the page itself when a document is spoken out (works in continuous and paged modes). Reading appearance → Read-aloud.
- **Ignore publisher styles** for books (Reading appearance): drops the book's own stylesheets and keeps the reader's — the escape hatch for books that hardcode pixel font sizes, page-width boxes or paper-tuned letter-spacing. Your typography settings win from the first page, and flipping it back restores the book's styling instantly.

### Improve

- Update the default user agent string to a current Chrome version so sites and their bot protection no longer treat app requests as an outdated browser (only affects installs that never customized the agent; "Reset user agent" in advanced settings picks it up)
- Details: chapter filters, sorting, display mode and download actions now apply immediately after you visit the scanlator priority screen again — no need to leave and re-open the entry first
- About: tapping the version text copies it to the clipboard, like Mihon and other forks
- Migration: with smart search off, the manual search results now show each candidate's latest chapter number as it is fetched, matching the migration list (new "Fetch chapters in manual search" option in migration options, on by default)
- Reader: a wedged WebGPU swapchain (PowerVR: `getCurrentTexture` returning `Error` on every frame, black screen with no crash) now escalates — the renderer tears the surface down and rebuilds it after ~0.5s of consecutive failures, and parks with a single reported outage instead of retrying forever. Also stops the decode-queue churn the retry storm fed.

### Fix

- **WebGPU reader:** fixed reading position and read-state lagging behind actual progress — sometimes by whole chapters. Progress is now saved even when a page's load has failed, and the WebGPU reader reports the exact page its scroll actually reached, so reopening a manga resumes where you left off and the Manga Details screen reflects it immediately.
- Fix the loading circle shown while the migration list searches a source sitting off-center in the row; it is now aligned with the cover slot where the result will appear
- Reader: rotating the screen no longer kicks you out of a WebGPU reader chapter; the reader now resizes in place instead of recreating the activity
- Reader (longstrip/continuous): reaching the end of the last chapter now pops the menu like the other viewers do
- MangaBaka: entries sitting on the current MangaBaka chapter total are no longer auto-marked Completed while the series is still releasing; only finished series complete on catching up
- Tablet UI: the debug info screen no longer shows a back button in two-pane settings (tapping it dropped out to the phone layout)

- Fix a crash in the scanlator priority reorder screen when dragging a row while the automatic-priority note is shown (`IndexOutOfBoundsException`), and items no longer move from the wrong position in that state

- Reader: the rare thin line between pages in the continuous/webtoon WebGPU viewer. Adjacent pages' tile clip edges were two float derivations of the same boundary and could disagree at a pixel centre, leaving a boundary row to neither page (the background showed through). Both sides now clamp at one shared value, so every row belongs to exactly one page.
- Reader: a page evicted mid-decode could stay latched as decoding, needing the stuck-page sweep to recover it; the worker now always clears its own latch.
