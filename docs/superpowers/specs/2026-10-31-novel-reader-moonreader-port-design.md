# Novel reader: Moon+ Reader feature port

**Date:** 2026-10-31
**Status:** Approved design
**Source:** static analysis of Moon+ Reader Pro 10.87 (`/home/branden/moonreader-decomp/` — jadx +
apktool output; main package `com.flyersoft.*` is unobfuscated: `books/Epub.java`,
`staticlayout/SoftHyphenStaticLayout.java`, `views/NewCurl3D.java`, `moonreaderp/PrefVisual.java`).
**Scope:** the WebView novel reader (`viewer/novel/`) — typography, webnovel plain text, publisher-CSS
override, page-turn animation, status line, auto-scroll, footnote popups.

---

## 1. What Moon+ Reader does that we ported

| Moon+ feature (decompiled) | Port |
|---|---|
| `hyphenation` + TeX/Liang pattern files, `textJustified` | already ours (CSS tri-states) |
| `indent_length` + `indent_paragraph` | **indent length** slider (em) |
| `fontItalic`, `fontshadow` | **italic text**, **text shadow** tri-states |
| `disable_css` family (drop the book's own CSS) | **ignore publisher styles** toggle |
| intelligent paragraph / `trimBlacnkSpace` / PLAIN handling | **webnovel plain-text** converter + cleanup toggles |
| 8 flip animations + `flipSpeed` | **page-turn animation** (none / slide-h / slide-v / fade / curl) + speed, paged mode |
| `PrefStatusSetting` (`status_show_time/progress/chapter`) | **status line** overlay (time / progress / chapter / battery, top or bottom) |
| 5 auto-scroll modes, real-time speed | **auto-scroll** for continuous mode (smooth rAF scroll, self-pauses on touch) |
| `footnotes` + `getFootnoteHtml` (aside extraction popup) | **footnote popups** (EPUB 3 `noteref` → popup with the note's markup) |
| TTS suite, highlights/annotations, dictionary, edge brightness | not this round — each is its own project |

## 2. Design decisions

### 2.1 Plain text (webnovel) — `NovelPlainText` (pure)
`TextPage.Kind.PLAIN` was never consumed. A PLAIN page's text now converts to HTML at the inline
build seam (`documentInline`/`fragmentInline`), so it rides the existing inline cache:
escape HTML, then per cleanup settings — trim blank lines, trim per-line spaces, and
"intelligent paragraphs" (single `\n` is a paragraph break, which is `PLAIN`'s documented contract).
Cleanup flags are hashed into the inline-cache kind so a settings change re-builds.

### 2.2 Ignore publisher styles — strip at build, not inline
`NovelDocument.build` gains `stripPublisherCss`: `<link rel=stylesheet>` and `<style>` blocks are
removed from the *final* document. Stripping after `NovelInline` means the inline cache stays valid
(the cached markup still carries the styles; the build drops them), and `NovelInline` has nothing
new to know.

### 2.3 Page-turn animation — one snapshot, animated away
Capturing the *next* page races the WebView's compositor. Instead: capture the current page bitmap
before the bridge's turn script runs, let the turn happen live underneath, and animate the old
page's bitmap away (slide / fade / `rotateY` curl with an edge shadow) in a `FrameLayout` overlay
wrapped around the WebView. One capture, no compositor dependency, and the live page is what the
reader ends on. Speed is a duration; `NONE` adds no overlay at all (today's behaviour).

### 2.4 Status line — Compose overlay beside the appbars
Pure model (`NovelStatusLine`) formats `HH:mm`, progress %, chapter/page name, battery %; a Compose
overlay renders it top or bottom inside the reader's safe insets, only for text chapters. Fields
individually toggleable; disabled by default.

### 2.5 Auto-scroll — a page-side rAF engine
A `KmkAutoScroll` script drives `window.scrollBy` from `requestAnimationFrame` at a px/s speed
(density-aware, set from a pref). The document's own scroll events drive the existing probe →
append pipeline, so the continuous window grows under the scroll and chapter ends turn pages
exactly as a manual drag does. `touchstart` self-pauses the engine; the host re-issues `start`
when the reader resumes it.

### 2.6 Footnote popups — JS detection + bounded extraction
A reader script (injected beside the bridge) intercepts clicks on note references: `epub:type~=
"noteref"` anchors, or anchors whose in-document target is `epub:type~="footnote|endnote|note"` /
id-class-matched. In-document targets hand their `outerHTML` to the host via
`addJavascriptInterface`; cross-document targets hand the URL and the host extracts the note from
the archive (`EpubFootnotes` in `core/archive`, pure bounded string extraction — the same approach
Moon+'s `getFootnoteHtml` takes). The popup is a small WebView loading the note markup at the
**notes document's** base URL through the same resource-serving path, so note images and CSS work.
Failure degrades to the old behaviour: the link navigates.

## 3. Verification

- `spotlessApply` → `spotlessCheck` → `assembleDebug` (user).
- Agent-side: `scripts/kotlinc-check/check.sh` over touched pure files; `run-harness.sh` extended
  (`TypographyHarness`: indent length / italic / shadow rules; new `PlainTextHarness`;
  `FootnoteHarness`; status-line formatting).
- Release notes: entries under `### New` and `### Improve`.
