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
| `LocalEpubSource` | `CatalogueSource` + `TextSource` + `TextTocSource` + `TextSearchSource` + `TextResourceSource` |
| `EpubSourceFileSystem` | Metro `@SingleIn(AppScope)` — locates the library |
| `EpubResourceReader` | Streams images/CSS/fonts out of the open archive; owns the per-chapter mmap lifetime |
| `EpubCover` | Cover bytes + cache name |

## Resource resolution

Pages load under `epub-archive://book.internal/`, a **reserved `.internal` scheme** (RFC 6761) that
cannot collide with a real site. The viewer's `shouldInterceptRequest` serves those from the archive;
every other URL is left alone so a book linking to an external image still works.

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