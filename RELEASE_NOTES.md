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
- **Reworked the translation pipeline from the ground up** — the on-device translation engine was rebuilt for faster startup, more reliable translations when providers change or go offline, and a smarter cache that reuses translated pages until the source or provider settings change.
- **Title and description translations no longer show stale results** — cached translations are now validated against the manga's current metadata and your provider settings, so edited entries re-translate instead of showing outdated text.
- **Clearer provider status in translation settings** — the Gemini Nano device status row now reflects the actual on-device availability reported by the engine.
- **Legacy translation caches are cleared on upgrade** — one-time cleanup of the old saved-page store; translations of pages will re-run on demand.

- WebGPU devices now run in full core mode wherever the driver supports it (`core-features-and-limits`), instead of staying in Compatibility mode with its lower limits — removes a class of silently-broken pipelines that could draw nothing (black screen) on capable devices.
- Reader: extremely high-resolution comic pages (250MB-comic territory) no longer crash the WebGPU reader out of memory. Pages are now clamped to what your screen can actually show (with zoom headroom) instead of uploading every pixel of a huge scan to the GPU — so the reader survives instead of dying with a lost GPU device mid-chapter, and on small or e-ink panels it uses a fraction of the memory it did. On a struggling device page detail eases off and recovers with available memory rather than the reader failing; 32-bit devices get a deliberately smaller memory budget across the reader.
- Reader: a WebGPU page could render at a tiny fraction of its size (a few dozen pixels) after the surface was rebuilt underneath it — seen on e-ink readers around refresh-mode switches and on sleep/resume: view a page, swipe away and back, and it came back postage-stamp. The degenerate scale is no longer latched; the page returns at its proper size.
- Reader: WebGPU sessions whose GPU device keeps dying without recovering now fall back to the standard reader on the next launches, instead of opening the same black screen every time — the easter egg on the About screen's version entry resets that fallback after a driver update fixes the device.

### Fix
