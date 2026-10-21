<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New
- **AI Translation**: Traditional Chinese (zh-TW) is now available as a target language alongside Simplified Chinese.

- **AI Translation**: When the translation engine is installed but the optional multilingual OCR add-on (PP-OCRv5) is missing, a one-tap "Install multilingual OCR add-on" row appears in the AI models settings. It downloads only the ~16 MB add-on, which is what reads English and other Latin-script pages; Japanese and Chinese work without it.

- **Book reader**: Novels now come with a curated, bundled font set — 16 OpenType families grouped by kind (serif, sans, mono, handwriting) in a picker that previews each family in its own face, alongside the device generics and imported files, so "serif" no longer means a different typeface on every device.

- **Book reader**: Bold-text and force-dark toggles, plus hand-typed hex colours for text and background, so a book's own colours can always be made readable.

- **Local manga (EPUB)**: A book whose EPUB metadata declares a series — calibre's tags, or an EPUB 3 collection marked `series` — is titled "Series: Volume", so a library row is a volume of a series rather than an anonymous fragment.

### Improve
- **WebGPU reader**: Smoother continuous (webtoon-style) scrolling — the background layer drawn under every page used to hand the GPU a fresh buffer and binding on every frame; both are now drawn from a small reusable ring, cutting per-frame GPU allocation churn for as long as the reader is open.

- **Book reader**: Find-in-book searches no longer re-read and re-strip the whole chapter with every keystroke — the plain text is built once per book while it is open, so typing in the search sheet stays quick on large novels. (Find-in-book is also now offered for local EPUB chapters at all — the button previously never appeared for them.)

- **Book reader**: The chosen font's data is base64-encoded once per family instead of on every page render and every typography adjustment, removing the hitch a font change used to cause mid-book.

- **Local manga (EPUB)**: EPUB-as-comic chapters now collect plates declared as SVG `<image>` elements (by `xlink:href`), not only `<img src>` ones.

- **Large libraries**: Global-update's metadata refresh and the category screen no longer load the entire library with per-entry chapter counts just to read titles and count category membership — that full-library pass was the heaviest step of both flows and is now gone.

- **Large libraries**: Routines that only ever needed one field now ask the database for exactly that field — the manga screen's sequel/prequel badge lookup and the "is this source entry already in my library" cross-check run lightweight queries instead of a full-library snapshot, and library cover pruning matches covers without loading your whole collection first.

- **Large libraries**: The database engine now keeps its scratch space and page cache in RAM and memory-maps the file for reads, so the temporary sorts that library-wide queries perform on big collections no longer spill to disk and every read gets a larger cache behind it.

### Fix
- **Large libraries**: On very large libraries, the out-of-memory guard around loading the Library could leave you staring at an empty screen; it now falls back to showing your entries without their unread counts rather than an invisible library.
- **Library**: Using "Search library" on a manga no longer breaks the back stack — Back from the filtered Library now lands you on the manga you came from, first closing the search filter as usual, instead of dropping the manga screen entirely.

- **Browse**: Leaving a source's browse screen for a manga (and coming back from a chapter) keeps the list/grid scroll position, instead of returning to the top of the page.

- **Manga Translator**: Image-translation failures now say what actually went wrong — the service found no readable text on the page, the page exceeds its 8 MB / 10000 px limits, or composing the translated page failed — instead of a bare "MangaTranslator returned empty result".

- **Local manga (EPUB)**: A chapter no longer gets stuck blank when the book references an image that is not actually in the archive — the loader skips (and logs) those references instead of creating a page the reader can never open.

- **Book reader**: Producer-noise hardening for EPUB books — a stray byte-order mark at the start of a text chapter is stripped instead of rendered, and a case variation of the internal `book.internal` URL no longer silently drops a book's images and stylesheets.

- **Book reader**: Links *inside* a book now land on their destination — a footnote link to `chapter.xhtml#note-7` scrolls to that note instead of opening the chapter at the top.

- **Local manga (EPUB)**: Resources whose file names contain a `+` (some producers' `cover+v2.jpg`) no longer 404 when the book tries to show them.

- **WebGPU reader**: Fixed a thin dark line that could appear at page boundaries in continuous (webtoon-style) reading while a neighbouring page was still decoding.

- **WebGPU reader**: Reopening a chapter after the app was killed now resumes on the page you were actually on — when a tall page had been split into segments mid-reading, the reader used to land several pages past your position.

- **WebGPU reader**: With double-tap zoom disabled, a tap on a paged page now registers immediately instead of the reader waiting to see whether a second tap arrives, matching the continuous reader.

- **WebGPU reader**: Zooming out in continuous (webtoon-style) reading no longer silently marks ahead-of-read pages as read — the saved reading position only advances with actual scrolling now, so reopening a chapter lands where you left off even after deep pinch zooms.

- **WebGPU reader**: A page in continuous reading that finished decoding while the reader sat still no longer stays blank until the next touch — the reader now notices pages joining the visible strip as soon as they become part of it.
