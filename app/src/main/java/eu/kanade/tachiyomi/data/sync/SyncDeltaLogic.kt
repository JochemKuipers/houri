package eu.kanade.tachiyomi.data.sync

// Pure helpers for SyncYomi protocol v2 delta uploads. Kept free of backup-model and Android
// imports so scripts/kotlinc-check/harness/SyncDeltaHarness.kt can compile them on a plain JVM.
// Server contract (SyncYomi docs/sync-protocol-v2.md): a manga without chapters in the body means
// "nothing to say about chapters", never "no chapters". chaptersForDelta still sends the full
// chapter list when the manga itself changed, as a local safety net against a server that treated
// absence as authoritative.

/** True when the manga row or any of its chapters was modified at or after [since]. */
fun mangaNeedsDelta(
    mangaLastModifiedAt: Long,
    chapterLastModifiedAts: List<Long>,
    since: Long,
): Boolean = mangaLastModifiedAt >= since || chapterLastModifiedAts.any { it >= since }

/**
 * Chapters to include for a manga that needs a delta.
 * Manga metadata change → full chapter list; chapter-only change → only the changed chapters.
 */
fun <T> chaptersForDelta(
    mangaLastModifiedAt: Long,
    chapters: List<T>,
    since: Long,
    lastModifiedAt: (T) -> Long,
): List<T> =
    if (mangaLastModifiedAt >= since) {
        chapters
    } else {
        chapters.filter { lastModifiedAt(it) >= since }
    }

/**
 * Must match [tachiyomi.domain.chapter.service.ChapterTombstones.key] and the SyncYomi server
 * byte for byte, or the server tombstones nothing. Inlined here so the offline harness can compile
 * this file without the suspend fun-interface (coroutines) dependency.
 */
fun chapterTombstoneKey(sourceId: Long, mangaUrl: String, chapterUrl: String): String =
    "$sourceId|$mangaUrl\u001f$chapterUrl"

/**
 * Pending tombstone keys that remain after a successful deletions POST acknowledges [sent].
 * Pure set arithmetic so a harness can pin the drain without PreferenceStore.
 */
fun remainingTombstones(pending: Set<String>, sent: Collection<String>): Set<String> =
    pending - sent.toSet()
