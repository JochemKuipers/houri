# EPUB reader hardening, built-in fonts and reading QoL

**Date:** 2026-10-20
**Status:** Approved design
**Scope:** text reader ecosystem — fonts, typography quality-of-life, the EPUB-as-comic loader, series metadata import

---

## 1. Problem

The book reader's engine side (parsing, resources, continuous scroll) has been through several
hardening rounds, but the parts a reader *touches every day* stopped short:

- The font setting offers four CSS generic families plus whatever the reader imported by hand.
  There is no real bundled typeface anywhere, so "serif" is whatever the device WebView resolves it
  to, and the rendering differs per OEM.
- An imported font cannot be removed (files accumulate forever), the import accepts any file
  under 16 MB (a non-font imports fine and then silently does nothing), and each row shows only
  its name, never the letterforms.
- Two typography answers common readers ship are missing: a bold-text toggle and a way to read in
  the dark reader themes when the publisher's own colours fight them (`force dark`).
- `EpubPageLoader` (EPUB-as-comic) builds its streams with `reader.getInputStream(path)!!` — a book
  whose markup names an image the archive does not contain produces a stream lambda that throws.
- Numbered series imported by folder rely on the file-name heuristic only; a book that *declares*
  its series (calibre metadata, EPUB 3 collection) is not read.

## 2. Design

### 2.1 Bundled fonts

`app/src/main/assets/novel-fonts/` carries ~16 curated **OFL-licensed variable fonts**, one file
per family, plus each font's `OFL.txt` under `novel-fonts/licenses/` (the licence must travel with
the fonts).

Groups and families:

| Group | Families |
|---|---|
| Serif | Literata, Lora, Merriweather, Source Serif 4, Bitter, Vollkorn, Playfair Display |
| Sans | Inter, Source Sans 3, Open Sans, Karla, Rubik, Atkinson Hyperlegible |
| Mono | JetBrains Mono, Source Code Pro |
| Handwriting | Caveat |

- A pure `NovelBuiltinFonts` table maps display name → asset file → MIME → category. Family
  values follow the existing prefix convention: `builtin/<Family>`, stored in
  `pref_novel_font_family` beside `user/<name>`. Neither prefix collides with CSS generic
  keywords; `NovelTypographyCss.quoteFamily` already quotes both.
- `NovelFonts.load` gains a `builtin/` branch reading the asset through the same `Face` type.
  No new injection channel is invented — the `@font-face data:` URI path is the one that has never
  failed (see `ui/reader/AGENTS.md`).
- `NovelWebViewer.readerFontCache` becomes a bounded LRU (4 faces): a session of font-hopping may
  not pin every face it ever displayed.

### 2.2 Font picker overhaul

The `SelectItem` dropdown in `NovelTypographySettingsPage` becomes a dialog:

- Grouped rows — **Built-in** (per category) and **Your fonts** — each rendered in its own
  family via a cached preview `Typeface` (`createFromAsset` for built-ins, `createFromFile` for
  user files; generic families map to Compose's own families).
- User-font rows carry a delete action (`NovelFonts.delete`) behind a confirm dialog. Deleting the
  *selected* family writes the serif fallback so the reader never points at a missing font.
- `NovelFonts.import` validates the magic bytes (`ttf`/`otf`/`woff`/`woff2` — the signatures
  `EpubMimeSniff` already knows for these formats) and reports *why* it failed: not a font, too
  large, unreadable. Strings are new KMR base-locale entries only.

### 2.3 Reading QoL

Two new tri-states in `[NovelTypography]` — nullable, default "follow the publisher", same shape
as `justified`/`hyphenation` (a non-null default would force a rule onto every book):

- **Bold text** (`pref_novel_bold_text`): `font-weight: 700 !important` (or `400` for off) on the
  text-bearing element set, deliberately *excluding* `h1–h6` so a book's heading hierarchy
  survives. Variable fonts give real weights; generics get synthetic bold.
- **Force dark colours** (`pref_novel_force_dark`): when on, the sheet resolves its own dark
  palette — background `#121212` unless the appearance tab set one, text colour via the existing
  `contrastingForeground` rule — and neutralises the publisher's colours inside the flow:
  `background-color: transparent !important` on everything except `html`/`body` and
  `img/svg/video/picture/source`, with `color` re-asserted across the same element set so
  white-on-white books read in dark themes. Illustrations are *not* inverted.
- `NovelTypographyCss` also gains the unconditional `img { max-width }` rule (margins guard
  dropped), and `EpubReader.readText` strips a leading UTF-8 BOM.
- `EpubArchiveUrls.archivePathOf` normalises the scheme case (`substringAfter("://")`) so a
  case-variant scheme cannot 404 a resource.

### 2.4 EPUB-as-comic hardening

`EpubPageLoader.getPages`:

- Resolves each candidate image path against `reader.contains()` first and skips unresolvable ones
  (logged by name), so a book with broken `<img>` hrefs builds a page list of real pages only.
- Page indexes stay dense after skips.
- The stream lambda throws a named `IllegalStateException` instead of an NPE if the path stops
  resolving between list-building and render.

### 2.5 Series metadata import

- `EpubOpfParser` collects, in precedence order: `<meta name="calibre:series">` +
  `calibre:series_index`, then EPUB 3 `<meta property="belongs-to-collection">` refined by
  `collection-type: series`. Values land in `EpubMetadata.series` / `seriesIndex` (index parsed
  leniently, null on failure).
- `EpubReaderExtensions.fillMetadata`: a declared series prefixes the chapter name as
  `"<series>: <book title>"` when the title does not already start with the series name. The
  file-name heuristic stays as the fallback when no series metadata exists. Chapter
  numbering/ordering behaviour is untouched.

### 2.6 Not in this round

Per-book typography overrides, custom CSS injection, read-aloud/TTS, on-demand font downloads —
each is its own project.

## 3. Verification

- User runs: `spotlessApply` → `spotlessCheck` → `assembleDebug` (or `:app:compileDebugKotlin`).
- Agent-side: `scripts/kotlinc-check/run-harness.sh` with extended `TypographyHarness` (new
  tri-states, force-dark rules) and `OpfMetadataHarness` (series parsing), plus `check.sh` over
  touched pure-JVM files.
- Release notes: entries under `### New` (fonts, picker, bold text, force dark, series import) and
  `### Fix` (comic loader, font import validation).
