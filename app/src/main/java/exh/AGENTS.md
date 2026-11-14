# exh/ Module

E-Hentai/ExHentai integration and the Komga built-in source. Package root: `exh.*`.

Every delegated source except E-Hentai was removed: NHentai, 8Muses, Pururin,
LANraragi and the whole MangaDex client stack (`exh/md/`, OAuth login, follows
sync, recommendations) no longer exist. Their IDs stay reserved in
`source-api/.../SourceIds.kt` / `TrackerId.kt` so restored library rows keep
resolving.

## Key directories

| Path | Purpose |
|------|---------|
| `exh/eh/` | E-Hentai update helpers, tag definitions |
| `exh/recs/` | Recommendation system (AniList, MAL, MangaUpdates, MangaUpdates-similar, Comick) |
| `exh/favorites/` | Two-way favorites sync with ExHentai |
| `exh/ui/metadata/` | Metadata viewer + EHentai description adapter |
| `exh/ui/batchadd/` | Batch gallery adding from URL list (E-Hentai URLs only) |
| `exh/ui/smartsearch/` | Smart search UI |
| `exh/search/` | Custom search engine with query parsing |
| `exh/smartsearch/` | Fuzzy title matching for library lookup |
| `exh/uconfig/` | E-Hentai server profile auto-configuration |
| `exh/log/` | XLog logging wrappers (KMK) |

## Source implementations

**`EHentai.kt`** (1463 lines) – Core E-Hentai/ExHentai source:
- Implements5 interfaces: `HttpSource`, `MetadataSource`, `UrlImportableSource`, `NamespaceSource`, `PagePreviewSource`
- Handles both EH and EXH (controlled by `exh: Boolean` constructor param)
- KMK: per-language source IDs (36 total:18 EH +18 EXH)
- Login via cookies: memberId, passHash, igneous, sk, s, hath_perks

## Metadata system

**Two-tier model:**
1. `FlatMetadata` (DB) = `SearchMetadata` + tags + titles
2. `RaisedSearchMetadata` (runtime) = typed subclasses deserialized from JSON

**Per-source metadata:** `EHentaiSearchMetadata` is the only per-source metadata
class left (`source-api/.../metadata/metadata/`); the NHentai/Pururin/8Muses/
LANraragi/MangaDex classes were deleted with their sources, and the shared
generic layer (`base/FlatMetadata`, `RaisedSearchMetadata`, `RankedSearchMetadata`)
remains for ABI + DB mapping.

**UI:** `EHentaiDescriptionAdapter` for metadata display

## Recommendation system

`RecommendationPagingSource` base class with implementations:
- AniList (GraphQL), MAL (Jikan v4), MangaUpdates, MangaUpdates-similar, Comick
- `StaticResultPagingSource` for pre-computed batch results
- `RecommendationSearchHelper` processes entire library, ranks by frequency

## Key patterns

- Fork markers: `// KMK -->` for Komikku additions
- DI: Injekt with `injectLazy()`, `ExhPreferences`
- URL import: `GalleryAdder.pickSource()` → `matchesUri()` → `mapUrlToMangaUrl()`
- Source ID constants in `source-api/.../SourceIds.kt`

## Conventions

- Prefer `// KMK -->` for new Komikku-only code (not `// EXH -->`)
- Logging: `xLogE()` / `xLog()` from `exh.log`
- Strings: `KMR` + `i18n-kmk/`
