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
| `LocalEpubSource` | `CatalogueSource` + `TextSource` + `TextTocSource` + `TextSearchSource` + `TextResourceSource` + `TextBaseUrlSource` |
| `EpubSourceFileSystem` | Metro `@SingleIn(AppScope)` — locates the library |
| `EpubResourceReader` | Streams images/CSS/fonts out of the open archive; owns the per-chapter mmap lifetime, and derives each document's base URL |
| `EpubCover` | Cover bytes + cache name |

## Resource resolution

Pages load under `epub-archive://book.internal/`, a **reserved `.internal` scheme** (RFC 6761) that
cannot collide with a real site. The viewer's `shouldInterceptRequest` serves those from the archive;
every other URL is left alone so a book linking to an external image still works.

### The base URL is the document's directory, not the archive root

`EpubResourceReader.documentBaseUrl(baseHref)` turns `TextPage.baseHref` — the spine item's in-archive
path — into the URL the renderer loads that document at. It keeps the **directory**, trailing slash
included.

This is the one detail in the module that fails silently and totally. A spine item at
`OEBPS/Text/chapter1.xhtml` links to `../styles/main.css`; against the archive root that resolves to
`styles/main.css` instead of `OEBPS/styles/main.css`, so the stylesheet is never found. Every image,
every embedded font and every relative link in the book misses the same way, and what the reader shows
is the book's text wearing only the reader's own stylesheet. `EpubResourceReader.BASE_URL` remains the
fallback, correct only for a spine item that already sits at the root.

`LocalEpubSource.baseUrlFor` is the bridge to the host: a separate optional interface rather than a
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

This is the most common report against this source, and it is almost never a bug in the reader — it is
a **manga-library book**. `source-local`'s `LocalSource` also accepts `.epub` files and deliberately
renders them **as images** (`EpubPageLoader` → `getImagesFromPages`), because some users import art
books as EPUB and that behaviour is relied upon. A book sitting in the ordinary local library is
therefore served by the *manga* chain and correctly shows illustrations, not text.

Text reading comes from `LocalEpubSource` (id `7812`) alone, rooted at `<local>/novels/`. Two distinct
sources, two different renderings, one file extension.

Read these log lines in order — together they answer every variant of the question:

| Line | Emitted by | Says |
|---|---|---|
| `Reader: choosing a page loader for …` | `PageLoaderRegistry.create` | Which **source** serves the chapter and whether it is a `TextSource`. The fork that matters most. |
| `Reader:   <Provider> -> chosen/declined` | same | Per-provider decision, so a wrong `order` is visible. |
| `Reader: loader chosen = <Loader>` | same | The final loader. `EpubPageLoader` here means the manga chain claimed it. |
| `isTextChapter: chapter=… loader=… isText=… pages=…` | `ViewerRegistry.isTextChapter` | The state the viewer fork was decided from. `loader=null` = chapter not loaded yet. |
| `ReaderActivity.updateViewer: DEFERRED - no page loader yet` | `ReaderActivity.updateViewer` | Viewer not built yet, waiting on the loader. Normal and transient. |
| `ReaderActivity.updateViewer: built <Viewer> …` | same | Which renderer the reader actually got. |
| `NovelViewerProvider: DECLINED - …` | `NovelViewerProvider.create` | Why the book renderer refused, and the loader it found instead. |
| `LocalEpubSource.getTextPageList: chapter=… spineItems=…` | `LocalEpubSource` | The spine it found and how many documents it produced. |
| `could not read spine item N (href)` | same | A file that would not decode — the cause of an empty chapter. |

Filter with `adb logcat | grep -E "Reader: |isTextChapter|ViewerRegistry|NovelViewerProvider|LocalEpubSource"`.
Two shapes mean two different bugs:

- `loader chosen = EpubPageLoader` → the chapter is in the **manga** library. Not a reader bug; move
  the book under `<local>/novels/` and re-add it so the EPUB source owns it.
- `loader chosen = TextPageLoader` but `built …PagerViewer` → `NovelViewerProvider` is not
  registered. Check the `Failed to register novel viewer` line from `App.kt`.

## Things that will bite you

- **One open book at a time.** `EpubResourceReader` holds an mmap + file descriptor per chapter.
  `release(chapterUrl)` / `releaseAll()` must be called or a session leaks one per book opened.
- **`fillFromBook` takes the chapter explicitly.** Inside `reader.use { }` the innermost implicit
  receiver is the `EpubReader`, so a bare `name = ...` resolves against the *reader*, not the
  chapter. This was a real bug.
- **Chapter URL is `"<series>/<file>"`** and is the identity for reading position and annotations.
  Renaming a file orphans its position.
- **`TextSearchHit.pageIndex` is stamped by the caller.** `indexesOf` searches one document and knows
  nothing about which page it is.
- Source id `7812L` is **fixed forever** — it is in every database row referencing a book.

## Conventions

- Strings: `KMR` + `i18n-kmk/…/base/` (Komikku-only feature), never `MR`.
- Package root `tachiyomi.source.epub.*`; fork markers `// KMK -->` / `// KMK <--`.
- Never run `./gradlew`; use `scripts/kotlinc-check/check.sh` for a fast syntax pass.