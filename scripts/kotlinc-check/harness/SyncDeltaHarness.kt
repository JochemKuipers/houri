// Pure-JVM assertions for SyncYomi delta filtering and chapter tombstone keys.
// Run with: scripts/kotlinc-check/run-harness.sh SyncDelta
import eu.kanade.tachiyomi.data.sync.chaptersForDelta
import eu.kanade.tachiyomi.data.sync.chapterTombstoneKey
import eu.kanade.tachiyomi.data.sync.mangaNeedsDelta
import eu.kanade.tachiyomi.data.sync.remainingTombstones

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
    if (!ok) failures++
}

private var failures = 0

private data class Ch(val lastModifiedAt: Long)

fun main() {
    // --- mangaNeedsDelta ---
    check("manga needs delta by manga stamp", mangaNeedsDelta(10, listOf(1, 2), 5), true)
    check("manga needs delta by chapter stamp", mangaNeedsDelta(1, listOf(1, 9), 5), true)
    check("manga unchanged before since", mangaNeedsDelta(4, listOf(1, 4), 5), false)
    check("manga equal to since counts", mangaNeedsDelta(5, emptyList(), 5), true)

    // --- chaptersForDelta: manga change keeps full list ---
    val chapters = listOf(Ch(1), Ch(8), Ch(3))
    check(
        "manga change keeps all chapters",
        chaptersForDelta(10, chapters, 5) { it.lastModifiedAt },
        chapters,
    )
    check(
        "chapter-only change keeps changed chapters",
        chaptersForDelta(1, chapters, 5) { it.lastModifiedAt },
        listOf(Ch(8)),
    )
    check(
        "chapter-only with none changed keeps empty",
        chaptersForDelta(1, chapters, 20) { it.lastModifiedAt },
        emptyList<Ch>(),
    )

    // --- chapterTombstoneKey matches SyncYomi: source|mangaUrl + 0x1f + chapterUrl ---
    check(
        "tombstone key separator",
        chapterTombstoneKey(42, "/manga/a", "/ch/1"),
        "42|/manga/a\u001f/ch/1",
    )
    check(
        "tombstone key preserves urls",
        chapterTombstoneKey(1, "https://x/y?z=1", "https://x/c#frag"),
        "1|https://x/y?z=1\u001fhttps://x/c#frag",
    )

    // --- remainingTombstones drain ---
    check(
        "drain removes sent keys only",
        remainingTombstones(setOf("a", "b", "c"), listOf("b", "x")),
        setOf("a", "c"),
    )
    check(
        "drain of empty sent is identity",
        remainingTombstones(setOf("a"), emptyList()),
        setOf("a"),
    )

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}
