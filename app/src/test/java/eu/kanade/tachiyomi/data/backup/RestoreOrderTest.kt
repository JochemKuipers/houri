package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.restoreOrderComparator
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class RestoreOrderTest {

    private fun manga(source: Long, url: String, lastModifiedAt: Long = 0L) =
        BackupManga(source = source, url = url, title = url, lastModifiedAt = lastModifiedAt)

    /**
     * An entry the database already holds has to be restored before one it does not,
     * or the update lands on nothing and the insert adds a second row for the same
     * series.
     */
    @Test
    fun `known entries are restored before unknown ones`() {
        val known = manga(source = 1L, url = "/known")
        val unknown = manga(source = 1L, url = "/unknown")

        val sorted = listOf(unknown, known)
            .sortedWith(restoreOrderComparator(mapOf(1L to setOf("/known"))))

        sorted shouldBe listOf(known, unknown)
    }

    @Test
    fun `a url is only known for its own source`() {
        val fromOtherSource = manga(source = 2L, url = "/known")
        val newFromSource1 = manga(source = 1L, url = "/known")

        val sorted = listOf(fromOtherSource, newFromSource1)
            .sortedWith(restoreOrderComparator(mapOf(1L to setOf("/known"))))

        sorted shouldBe listOf(newFromSource1, fromOtherSource)
    }

    @Test
    fun `merged entries are restored last`() {
        val plain = manga(source = 1L, url = "/plain")
        val merged = manga(source = MERGED_SOURCE_ID, url = "/merged")

        listOf(merged, plain).sortedWith(restoreOrderComparator(emptyMap())) shouldBe
            listOf(plain, merged)
    }

    @Test
    fun `equally ranked entries restore newest first`() {
        val older = manga(source = 1L, url = "/older", lastModifiedAt = 1L)
        val newer = manga(source = 1L, url = "/newer", lastModifiedAt = 2L)

        listOf(older, newer).sortedWith(restoreOrderComparator(emptyMap())) shouldBe
            listOf(newer, older)
    }
}
