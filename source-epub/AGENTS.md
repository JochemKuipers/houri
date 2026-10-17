# source-epub/ Module

Built-in EPUB library source. Folder = series, `.epub` file = chapter. KMP, like `source-local`.

## Why a separate source

The manga `LocalSource` already accepts `.epub` files as chapters, but renders them **as images**
(`EpubPageLoader` extracts illustrations and feeds the image viewers). That behaviour is relied on —
some users import art books as EPUB. Changing it in place would silently alter their libraries.

This source is a separate identity in the database with its own browse surface, its own settings and
text rendering. The manga source is untouched.

## Layout

```
<local>/novels/<Series>/<chapter>.epub
```

`EpubSourceFileSystem` roots at `<local>/novels` — one level under the manga library so the two
sources never list each other's folders, while still inheriting the storage permission the user has
already granted.

## Key classes

| Class | Purpose |
|-------|---------|
| `LocalEpubSource` | `CatalogueSource` + `TextSource` + `TextTocSource` + `TextSearchSource` + `TextResourceSource` + `TextBaseUrlSource` + `EpubArchiveSource` |
| `EpubSourceFileSystem` | Metro `@SingleIn(AppScope)` — locates the library |
| `EpubResourceReader` | Per-chapter registry over the shared `EpubResourceServer` |
| `EpubCover` | Cover bytes + cache name |

## The manga library serves books too

An `.epub` in the **manga** library used to render as illustrations and nothing else, because
`PageLoaderFactory` maps `Format.Epub` to `EpubPageLoader` and `EpubPageLoader` builds pages out of
`getImagesFromPages()`. That is still the behaviour for an entry resolved as `COMIC` — art-book
imports depend on it — and it is now one of two answers rather than the only one.

A manga-library entry resolved as `BOOK` is served as text by `source-local`'s
`LocalEpubTextAdapter`, which sits on the same `core:archive` primitives this source uses
(`EpubTextSource`, `EpubResourceServer`, `EpubArchiveUrls`). A book reads identically in either
library, and the two cannot drift.

The routing lives in `PageLoaderFactory`'s `TextEntryPageLoaderProvider`, which claims a chapter only
after the entry's type is resolved. Resolution order — and why a heuristic may never overrule a user —
is in `docs/superpowers/specs/2026-10-17-library-entry-types-design.md`.

### `core:archive` is the shared layer

`EpubTextSource`, `EpubResourceServer`, `EpubArchiveUrls` and `LimitedInputStream` live there because
`source-local` and `source-epub` cannot depend on each other and must not each grow their own copy.
`core:archive` takes an `implementation` on `source-api` for this; there is no cycle.

- **`TextSearchHit.pageIndex` is stamped by the caller.** `indexesOf` searches one document and knows
  nothing about which page it is.
- **`TextPage.index` is the spine index and is sparse.** A document that failed to read leaves a hole.
  Anything consuming `TextSearchHit.pageIndex` must match on `textPage.index`, never on a list
  position — the two agree only while nothing was skipped.
- **An entry's type lives in `is_light_novel` plus a `kmk.entryTypeExplicit` memo bit.** The column
  is shared with E-Hentai's provenance question and the two must not be conflated; see
  `docs/superpowers/specs/2026-10-17-library-entry-types-design.md`.
- **A resource without a conventional extension is sniffed, not refused.** `EpubMimeSniff` reads the
  first bytes; `EpubResourceServer.contentTypeFor` consults the extension table first and falls back to
  sniffing, so a WebP or a JPEG 2000 stored as `.bin` decodes instead of being handed to the renderer
  as an octet stream. `DocumentHarness` asserts the signatures, including the RIFF pair whose type
  sits past a variable-length field.
- **Incremental reading is a separate optional interface, `TextIncrementalSource`.** It splits the
  eager `getTextPageList` into `getTextPageIndex` — the spine enumerated with no text read, so its
  `TextPage.index` is dense — and `getTextPageText`, one document at a time. The eager path stays for
  `getTextPageList`'s callers and its index stays sparse; the two agree only while nothing was
  skipped, which is the same reason consumers match on `textPage.index` and never on a list position.

Pages load under `epub-archive://book.internal/`, a **reserved `.internal` scheme** (RFC 6761) that
cannot collide with a real site. The viewer's `shouldInterceptRequest` serves those from the archive;
every other URL is left alone so a book linking to an external image still works.

The URL space itself is `EpubArchiveUrls` in `core:archive`, and the lifetime of the mapped archives
is `EpubResourceServer`. Both are asserted by `DocumentHarness`.

### The base URL is the document's directory, not the archive root

`EpubArchiveUrls.documentBaseUrl(baseHref)` turns `TextPage.baseHref` — the spine item's in-archive
path — into the URL the renderer loads that document at. It keeps the **directory**, trailing slash
included.

This is the one detail in the module that fails silently and totally. A spine item at
`OEBPS/Text/chapter1.xhtml` links to `../styles/main.css`; against the archive root that resolves to
`styles/main.css` instead of `OEBPS/styles/main.css`, so the stylesheet is never found. Every image,
every embedded font and every relative link in the book misses the same way, and what the reader shows
is the book's text wearing only the reader's own stylesheet. `EpubArchiveUrls.BASE_URL` remains the
fallback, correct only for a spine item that already sits at the root.

`baseUrlFor` on each source is the bridge to the host: a separate optional interface rather than a
method on `TextResourceSource`, because that interface already ships and extensions implement it
across a classloader boundary.

## Metro: the plugin is load-bearing

`build.gradle.kts` must apply **both** `alias(libs.plugins.metro)` (compiler plugin) and
`implementation(libs.metro.runtime)`.

The runtime dependency alone puts `@Inject` / `@SingleIn` on the class but **never generates the
binding**, and `:app`'s `AppGraph` then fails with:

```
[Metro/MissingBinding] No binding found for EpubSourceFileSystem
```

`source-local` is the reference: copy its `plugins {}` block rather than hand-writing one. This is
the single easiest thing to get wrong when adding a new DI-providing module.

## Diagnosing "a book opens as pictures"

Almost always one of two things, and the log says which.

Read these lines in order — together they answer every variant:

| Line | Emitted by | Says |
|---|---|---|
| `Reader: choosing a page loader for …` | `PageLoaderRegistry.create` | Which **source** serves the chapter and whether it is a `TextSource`. |
| `TextEntryPageLoaderProvider: claiming … as a book` | same module | The manga library claimed it as text. Its absence means the entry resolved to COMIC. |
| `Reader: <url> detected BOOK/COMIC from content` | same module | Which way the heuristic went, and that nothing had been declared. |
| `Reader: loader chosen = <Loader>` | `PageLoaderRegistry.create` | `EpubPageLoader` here means the entry resolved to COMIC. |
| `isTextChapter: chapter=… loader=… isText=… pages=…` | `ViewerRegistry.isTextChapter` | The state the viewer fork was decided from. `loader=null` = chapter not loaded yet. |
| `ReaderActivity.updateViewer: DEFERRED - no page loader yet` | `ReaderActivity.updateViewer` | Viewer not built yet, waiting on the loader. Normal and transient. |
| `ReaderActivity.updateViewer: built <Viewer> …` | same | Which renderer the reader actually got. |
| `NovelViewerProvider: DECLINED - …` | `NovelViewerProvider.create` | Why the book renderer refused, and the loader it found instead. |
| `EpubTextSource: <url> -> N text page(s) of M spine item(s)` | `EpubTextSource` | The spine it found and how many documents it produced. |
| `could not read spine item N (href)` | same | A file that would not decode — the cause of an empty chapter. |

Filter with `adb logcat | grep -E "Reader: |isTextChapter|ViewerRegistry|NovelViewerProvider|EpubTextSource"`.

- `loader chosen = EpubPageLoader` and no `detected BOOK` line → the entry resolved to COMIC. Either
  the folder holds non-EPUB files too, or the spine is image-dominated. "Render as comic"/"Render as
  book" in manga info is the override.
- `detected COMIC` on something that is obviously a novel → detection is wrong, which is expected
  sometimes. Override it; the heuristic is a hint, not a verdict.
- `loader chosen = TextPageLoader` but `built …PagerViewer` → `NovelViewerProvider` is not registered.
  Check the `Failed to register novel viewer` line from `App.kt`.

## Things that will bite you

- **One open book at a time.** `EpubResourceServer` holds an mmap + file descriptor per chapter.
  `EpubArchiveSource.releaseResourceReader(url)` must be called or a session leaks one per book.
- **`fillFromBook` takes the chapter explicitly.** Inside `reader.use { }` the innermost implicit
  receiver is the `EpubReader`, so a bare `name = ...` resolves against the *reader*, not the
  chapter. This was a real bug.
- **Chapter URL is `"<series>/<file>"`** and is the identity for reading position and annotations.
  Renaming a file orphans its position.
- **`TextSearchHit.pageIndex` is stamped by the caller.** `indexesOf` searches one document and knows
  nothing about which page it is.
- **`TextPage.index` is the spine index and is sparse.** A document that failed to read leaves a hole,
  so anything consuming `TextSearchHit.pageIndex` must match on `textPage.index` and never on a list
  position — the two agree only while nothing was skipped.
- **An entry's type lives in `is_light_novel` plus a `kmk.entryTypeExplicit` memo bit.** The column
  is shared with E-Hentai's provenance question and the two must not be conflated; see
  `docs/superpowers/specs/2026-10-17-library-entry-types-design.md`.
- Source id `7812L` is **fixed forever** — it is in every database row referencing a book.

## Conventions

- Strings: `KMR` + `i18n-kmk/…/base/` (Komikku-only feature), never `MR`.
- Package root `tachiyomi.source.epub.*`; fork markers `// KMK -->` / `// KMK <--`.
- Never run `./gradlew`; use `scripts/kotlinc-check/check.sh` for a fast syntax pass.
