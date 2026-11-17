# data/ Module

SQLDelight database layer with23 tables/views,46 migrations, and18 repository implementations. Package root: `tachiyomi.data.*`.

## Schema organization

**SQL files** in `src/main/sqldelight/tachiyomi/data/`:
- `mangas.sq` (30 queries), `chapters.sq` (12), `history.sq` (6), `categories.sq` (5)
- `manga_sync.sq` (5), `merged.sq` (10), `saved_search.sq` (7), `feed_saved_search.sq` (10)
- `sources.sq` (3), `excluded_scanlators.sq` (3), `extension_store.sq` (5)
- `libraryUpdateError.sq` (5), `libraryUpdateErrorMessage.sq` (3)
- EH-specific: `search_titles.sq`, `search_tags.sq`, `search_metadata.sq`, `eh_favorites.sq`

**Views** in `src/main/sqldelight/tachiyomi/view/`:
- `libraryView` (~100 lines) – Manga + aggregated chapter stats + categories (UNION for merged source 6969)
- `updatesView` (~60) – Chapter updates with excluded scanlator awareness
- `historyView` (~50) – History + max read tracking + chapter count stats
- `libraryUpdateErrorView` – Error details with manga metadata

**Migrations** in `src/main/sqldelight/tachiyomi/migrations/`: `1.sqm` through `58.sqm`

## Repository implementations (18)

All in `tachiyomi.data.*`:
- `MangaRepositoryImpl` (219 lines), `ChapterRepositoryImpl` (198), `CategoryRepositoryImpl` (93)
- `HistoryRepositoryImpl` (120), `TrackRepositoryImpl` (86), `SourceRepositoryImpl` (116)
- `SavedSearchRepositoryImpl` (67), `FeedSavedSearchRepositoryImpl` (115)
- `MangaMergeRepositoryImpl` (127), `UpdatesRepositoryImpl` (105)
- `ExtensionStoreRepositoryImpl` (149), `LibraryUpdateErrorRepositoryImpl` (74)
- `MangaMetadataRepositoryImpl` (119), `FavoritesEntryRepositoryImpl` (66)
- `CustomMangaRepositoryImpl` – user-edited manga metadata in `custom_manga_info` (KMK/SY table, migration 58); process-wide cache in front of the table because `Manga` snapshots edits in a `@Transient` initialiser

**Common patterns:**
```kotlin
// Simple query with mapper
handler.awaitOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }

// Reactive query (Flow)
handler.subscribeToOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }

// Transaction with partial update
handler.await(inTransaction = true) { updates.forEach { mangasQueries.update(...) } }
```

## Mappers (11)

Objects: `MangaMapper`, `ChapterMapper`, `CategoryMapper`, `HistoryMapper`, `TrackMapper`, `MergedMangaMapper`, `SavedSearchMapper`, `FeedSavedSearchMapper`

Lambdas (KMK): `LibraryUpdateErrorMapper`, `LibraryUpdateErrorWithRelationsMapper`, `LibraryUpdateErrorMessageMapper`

## Column adapters

`DateColumnAdapter` (Long↔Date), `StringListColumnAdapter` (String↔List<String>), `UpdateStrategyColumnAdapter` (Long↔UpdateStrategy), `MemoColumnAdapter` (ByteArray↔JsonObject), `BlacklistColumnAdapter` (String↔List<String>, JSON — lossless; the shared ", "-joined adapter corrupts any key carrying a comma-space, which is how blacklisted chapters came back, issue #68; decode still reads legacy rows)

**A JSON-encoded column cannot be matched by SQL text comparisons that assume the old encoding.** `libraryView` and `historyView` keep blacklisted chapters out of the badge and the History totals with `instr()` against `blacklisted_chapters`; after the blacklist moved to JSON those predicates matched nothing and every blacklisted chapter was counted again. Both views now test both encodings — `instr(col, '"key"')` for the JSON array and the old `', '||col||', '` form for legacy rows — in all three arms (non-merged, merged, history). Any future change to a column's encoding needs the views and migrations revisited in the same change, and `migrations/NN.sqm` must carry the recreated view body verbatim (no schema bump recreates a view by itself).

## SQL patterns

- **Coalesce updates:** `SET col = coalesce(:param, col)` for partial updates
- **UPSERT:** `INSERT ... ON CONFLICT DO UPDATE`
- **Sync-aware versioning:** Triggers increment `version` only when `is_syncing = 0`
- **Merged source handling:** All views use `UNION` for source ID 6969

## Key file

`DatabaseHandler.kt` / `AndroidDatabaseHandler.kt` – Core DB handler with coroutine dispatching

## Conventions

- After schema changes: create new `.sqm`, often add `// KMK` blocks in `.sq` / mappers
- Regenerate: `./gradlew :data:generateSqlDelightInterface`
- Views require full `DROP + CREATE` (SQLDelight limitation)
