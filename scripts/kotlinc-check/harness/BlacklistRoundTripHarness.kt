import kotlinx.serialization.json.JsonObject
import tachiyomi.data.BlacklistColumnAdapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.isChapterBlacklisted
import tachiyomi.domain.chapter.service.applyScanlatorVisibility
import tachiyomi.domain.chapter.service.scanlatorBlacklistKey

/**
 * Issue #68: a blacklisted chapter came back after leaving the series and reopening it.
 *
 * The blacklist keys are free text — a scanlator name can carry ", " and a narrowed key
 * embeds a url — and the column used to store them joined on ", ", so the decoded list no
 * longer held the key that was written and the chapter silently un-hid. These cases run the
 * REAL key derivation, the REAL matcher and the REAL column adapter through the exact
 * write → store → read cycle the app performs.
 *
 * The lone-vs-sibling choice below mirrors MangaScanlatorController.blacklistChapters (that
 * class needs the app graph and cannot link here); the key derivation itself is the real
 * domain function.
 */

private var failures = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
        println("ok: $name")
    } else {
        failures++
        println("FAIL: $name${if (detail.isEmpty()) "" else " — $detail"}")
    }
}

private fun chapter(id: Long, number: Double, scanlator: String?, url: String) = Chapter(
    id = id,
    mangaId = 1L,
    read = false,
    bookmark = false,
    lastPageRead = 0L,
    dateFetch = 0L,
    sourceOrder = id,
    url = url,
    name = "Chapter $number",
    dateUpload = 0L,
    chapterNumber = number,
    scanlator = scanlator,
    lastModifiedAt = 0L,
    version = 0L,
    memo = JsonObject(emptyMap()),
)

/** Write-side derivation, mirroring the controller: narrow only when a sibling exists. */
private fun writeKey(chapter: Chapter, all: List<Chapter>): String {
    val hasSiblings = all.any { other ->
        other.id != chapter.id &&
            other.chapterNumber == chapter.chapterNumber &&
            other.scanlator?.trim().orEmpty() == chapter.scanlator?.trim().orEmpty()
    }
    return if (hasSiblings && chapter.url.isNotBlank()) {
        scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator, chapter.url)
    } else {
        scanlatorBlacklistKey(chapter.chapterNumber, chapter.scanlator)
    }
}

fun main() {
    // A scanlator name carrying the old separator: the exact shape that used to shatter.
    val jointScanlator = "Group A, Group B"

    // 1. The adapter round-trips any single key exactly.
    val keys = listOf(
        scanlatorBlacklistKey(12.0, "Group"),
        scanlatorBlacklistKey(12.0, jointScanlator),
        scanlatorBlacklistKey(13.5, null),
        scanlatorBlacklistKey(14.0, jointScanlator, "https://example.site/manga/ch-14#fragment"),
        scanlatorBlacklistKey(15.0, "Teams: X & Y", "https://example.site/read/1,000/15"),
    )
    val stored = BlacklistColumnAdapter.encode(keys)
    check("round-trip preserves every key", BlacklistColumnAdapter.decode(stored) == keys, "stored=$stored")

    // 2. The old encoding demonstrably corrupted this list (the regression this guards).
    val oldJoin = keys.joinToString(", ")
    val oldSplit = oldJoin.split(", ")
    check(
        "legacy ', ' encoding corrupted comma-bearing keys (documenting the defect)",
        oldSplit != keys,
    )

    // 3. Legacy rows (pre-adapter data on existing devices) decode the old way.
    check(
        "legacy single-key row decodes",
        BlacklistColumnAdapter.decode("12.0@Group") == listOf("12.0@Group"),
    )
    check(
        "legacy multi-key row decodes",
        BlacklistColumnAdapter.decode("12.0@Group, 13.0@Other") == listOf("12.0@Group", "13.0@Other"),
    )
    check("empty column decodes empty", BlacklistColumnAdapter.decode("").isEmpty())

    // 4. End to end: blacklist one upload among siblings, store, read back, hide.
    val ch12a = chapter(1L, 12.0, jointScanlator, "https://example.site/ch/12/a")
    val ch12b = chapter(2L, 12.0, jointScanlator, "https://example.site/ch/12/b")
    val lone = chapter(3L, 13.0, jointScanlator, "https://example.site/ch/13")
    val all = listOf(ch12a, ch12b, lone)

    val blacklisted = listOf(ch12a, lone)
    val keyList = blacklisted.map { writeKey(it, all) }
    val persisted = BlacklistColumnAdapter.decode(BlacklistColumnAdapter.encode(keyList))

    val visible = all.applyScanlatorVisibility(
        priority = emptyList(),
        blacklistedChapters = persisted,
        mergeEnabled = false,
    )
    check("narrowed key hides only its own upload after storage", ch12b in visible && ch12a !in visible)
    check("lone chapter stays hidden after storage", lone !in visible)

    // 5. And the matcher itself answers directly on the round-tripped list.
    check(
        "matcher sees the round-tripped narrowed key",
        isChapterBlacklisted(ch12a, persisted),
    )
    check(
        "matcher leaves the sibling alone",
        !isChapterBlacklisted(ch12b, persisted),
    )

    // 6. No blacklist: visibility is a no-op.
    check(
        "empty blacklist hides nothing",
        all.applyScanlatorVisibility(emptyList(), emptyList(), mergeEnabled = false) == all,
    )

    if (failures > 0) {
        println("FAIL: $failures case(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("PASS: BlacklistRoundTripHarness")
}
