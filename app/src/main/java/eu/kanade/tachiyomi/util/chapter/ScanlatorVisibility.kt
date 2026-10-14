package eu.kanade.tachiyomi.util.chapter

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.isChapterBlacklisted

// KMK -->
private const val TENTHS = 10L

/**
 * The one entry point every surface uses to decide which of a manga's chapters are visible.
 *
 * Three surfaces need this answer - the details list, the library's continue-reading pick, and the
 * reader's chapter sheet - and they used to each re-implement it with a slightly different gate.
 * That drift had already caused the reader to disagree with the details screen about which
 * chapters exist, so the rules live here once and the callers only choose *when* to ask.
 *
 * Two things are decided, in order:
 *
 * 1. Blacklisted chapters are removed, always. Blacklisting is a reading preference in its own
 *    right, so it must not be bundled behind the merge toggle: turning that off has to un-merge
 *    chapters, never un-hide ones the user deliberately removed.
 * 2. When [mergeEnabled], each chapter number is collapsed to a single upload. Which upload wins is
 *    [priority], and when the user has never set one, [deriveScanlatorPriority] ranks the scanlators
 *    present in this very list. That is what makes the behaviour work on a manga nobody has
 *    configured: before, an empty priority made the merge a no-op and every scanlator stayed
 *    visible, so a series with three groups of the same chapter read as three times as long.
 *
 * Merging is per chapter *number*, not per scanlator, so a series where one group published 1-50
 * and another 51-100 keeps all of it - the primary wins the numbers it has and the other covers
 * the rest. Chapters with no usable number are never deduplicated, since there is nothing to
 * deduplicate them against.
 *
 * [mergeEnabled] is the global user switch. Passing a non-empty [priority] alone does not force a
 * merge, so the toggle can always turn this off.
 *
 * [alwaysVisibleChapterId] is for the reader, which owns exactly one chapter that must not be
 * filtered away: the one open right now. It survives both steps - it stays in the list even when it
 * is blacklisted, and if the merge picks a different upload for its number the result falls back
 * to the unmerged list so prev/next still works and the reader's `chapterList.first { it.id == ... }`
 * lookup cannot throw. Every *other* chapter stays subject to the blacklist in that case.
 */
fun List<Chapter>.applyScanlatorVisibility(
    priority: List<String>,
    blacklistedChapters: Collection<String>,
    rangeRules: List<String> = emptyList(),
    mergeEnabled: Boolean,
    alwaysVisibleChapterId: Long? = null,
): List<Chapter> {
    val visible = if (blacklistedChapters.isEmpty()) {
        this
    } else {
        filterNot { it.id != alwaysVisibleChapterId && isChapterBlacklisted(it, blacklistedChapters) }
    }
    if (!mergeEnabled) return visible

    val merged = visible.applyScanlatorPriority(
        priority = priority.ifEmpty { visible.deriveScanlatorPriority() },
        blacklistedChapters = blacklistedChapters.toSet(),
        rangeRules = rangeRules,
    )
    return if (alwaysVisibleChapterId != null && merged.none { it.id == alwaysVisibleChapterId }) {
        visible
    } else {
        merged
    }
}

/**
 * Ranks the scanlators present in this list from best to worst, for use as an implicit
 * [applyScanlatorPriority] order. Best means "covers the most of the story", not "published the
 * most files".
 *
 * Ranking key, in order:
 *
 * 1. **Longest unbroken run of whole chapters.** This is the main signal and it is deliberately
 *    ahead of raw count, because it is what a reader actually cares about. A group holding
 *    1-100 plus a stray 105 has 101 chapters to one group's 100, but the other group is the
 *    better source: it has no hole in the middle. Ranking by count would hand the series to the
 *    group with the gap.
 * 2. **Number of distinct recognised chapter numbers**, including half-chapters. A group that
 *    additionally ships 0.5 or an omake edge is more complete than one that does not, and this
 *    catches that without letting half-chapter noise break the run measurement above.
 * 3. **Total chapters**, which only separates groups that look identical by coverage - a group that
 *    re-uploads the same numbers under different names.
 * 4. **Name**, case-insensitively, so the order is stable across openings. Without this the merge
 *    could pick a different winner for the same chapter between two runs purely on hash order, and
 *    a series would appear to swap upload at random.
 *
 * Numbers are compared in tenths of a chapter, matching [scanlatorCoverage], so float noise from
 * source parsing neither breaks a run nor changes the ranking.
 *
 * Only chapters with a positive, recognised number are counted; a nameless chapter belongs to no
 * scanlator and chapters with no number cannot be ranked. A group whose numbers are all half-steps
 * therefore scores zero on the run and is separated by the later keys rather than being dropped.
 */
fun List<Chapter>.deriveScanlatorPriority(): List<String> {
    val ranks = groupBy { it.scanlator?.trim().orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .map { (scanlator, chapters) -> scanlator to ScanlatorRank.of(chapters) }

    return ranks
        .sortedWith(
            compareByDescending<Pair<String, ScanlatorRank>> { it.second.longestWholeRun }
                .thenByDescending { it.second.distinctNumbers }
                .thenByDescending { it.second.chapterCount }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first }
                .thenBy { it.first },
        )
        .map { it.first }
}

/**
 * How completely one scanlator covers a series. See [deriveScanlatorPriority] for why each field
 * is measured, and why they are consulted in that order.
 */
private class ScanlatorRank private constructor(
    val longestWholeRun: Int,
    val distinctNumbers: Int,
    val chapterCount: Int,
) {
    companion object {
        fun of(chapters: List<Chapter>): ScanlatorRank {
            val tenths = chapters
                .filter { it.chapterNumber > 0.0 }
                .map { Math.round(it.chapterNumber * TENTHS) }
                .filter { it > 0L }
                .distinct()

            return ScanlatorRank(
                longestWholeRun = tenths.longestRun { it % TENTHS == 0L },
                distinctNumbers = tenths.size,
                chapterCount = chapters.size,
            )
        }

        /**
         * Length of the longest run of whole chapters stepping by one chapter. Half-chapters are
         * excluded rather than treated as breaks: `1, 2, 0.5, 3` is a complete 1-3 with an extra,
         * not two fragments, and counting the extra as a gap would rank it below `1, 2`.
         */
        private fun Collection<Long>.longestRun(isWhole: (Long) -> Boolean): Int {
            val whole = filter(isWhole).sorted()
            var longest = 0
            var current = 0
            var previous = 0L
            whole.forEach { value ->
                current = if (current > 0 && value == previous + TENTHS) current + 1 else 1
                previous = value
                if (current > longest) longest = current
            }
            return longest
        }
    }
}
// KMK <--
