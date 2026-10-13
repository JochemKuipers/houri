package tachiyomi.domain.category.interactor

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Execution(ExecutionMode.CONCURRENT)
class SyncCategoriesWithTrackStatusTest {

    private fun category(id: Long, trackStatus: Int?) = Category(
        id = id,
        name = "cat$id",
        order = id,
        flags = 0,
        hidden = false,
        trackStatus = trackStatus,
    )

    private fun update(
        current: Set<Long>,
        bound: List<Category>,
        trackStatus: Int?,
    ): List<Long>? = SyncCategoriesWithTrackStatus.updateMembership(current.toMutableSet(), bound, trackStatus)

    // Matches the issue's example: wish to read -> reading.
    private val reading = 1
    private val planToRead = 3

    @Test
    fun `a category bound to the current status gains the entry`() {
        val result = update(
            current = setOf(10L),
            bound = listOf(category(1, planToRead), category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(10L, 2L)
    }

    @Test
    fun `a category bound to a different status loses the entry`() {
        val result = update(
            current = setOf(1L),
            bound = listOf(category(1, planToRead), category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `an unbound category is never touched`() {
        val plain = category(9, trackStatus = null)
        val result = update(
            current = setOf(9L),
            bound = listOf(plain, category(2, reading)),
            trackStatus = planToRead,
        )

        // Still present, even though its status does not match: nothing bound, nothing removed.
        result shouldContainExactlyInAnyOrder listOf(9L)
    }

    @Test
    fun `an unbound category is never added to either`() {
        val result = update(
            current = emptySet(),
            bound = listOf(category(9, null), category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `losing tracking clears the entry out of every bound category`() {
        val result = update(
            current = setOf(1L, 2L),
            bound = listOf(category(1, planToRead), category(2, reading)),
            trackStatus = null,
        )

        result shouldContainExactlyInAnyOrder emptyList<Long>()
    }

    @Test
    fun `two categories bound to the same status both gain the entry`() {
        val result = update(
            current = emptySet(),
            bound = listOf(category(2, reading), category(3, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L, 3L)
    }

    @Test
    fun `nothing bound means nothing to do`() {
        update(current = setOf(5L), bound = emptyList(), trackStatus = reading) shouldBe null
    }

    @Test
    fun `an entry already in the right category is left exactly as it was`() {
        val result = update(
            current = setOf(2L),
            bound = listOf(category(2, reading)),
            trackStatus = reading,
        )

        result shouldContainExactlyInAnyOrder listOf(2L)
    }

    // The cases below drive await() rather than updateMembership(), because the early-return that
    // decides whether anything is written at all lives in await() and compares against the
    // membership as it was read. updateMembership() mutates that same set, so a comparison against
    // the mutated value is trivially equal and nothing would ever be written. updateMembership()
    // alone cannot catch that.

    private fun harness(
        bound: List<Category>,
        currentIds: List<Long>,
    ): Triple<CategoryRepository, SetMangaCategories, SyncCategoriesWithTrackStatus> {
        val repository: CategoryRepository = mockk()
        val setMangaCategories: SetMangaCategories = mockk()
        coEvery { repository.getAll() } returns bound
        coEvery { repository.getCategoriesByMangaId(any()) } returns currentIds.map { category(it, null) }
        coEvery { setMangaCategories.await(any(), any()) } just runs
        return Triple(repository, setMangaCategories, SyncCategoriesWithTrackStatus(repository, setMangaCategories))
    }

    @Test
    fun `await writes the membership when the status moved it`() = runTest {
        val (_, setMangaCategories, interactor) = harness(
            bound = listOf(category(1, planToRead), category(2, reading)),
            currentIds = listOf(10L),
        )
        val written = slot<List<Long>>()
        coEvery { setMangaCategories.await(any(), capture(written)) } just runs

        interactor.await(mangaId = 10L, trackStatus = reading)

        // 10 is an unbound category the user chose, so it stays; 2 is bound to this status, so the
        // entry joins it.
        written.captured.toSet() shouldBe setOf(10L, 2L)
    }

    @Test
    fun `await does not write when the status did not change the membership`() = runTest {
        val (_, setMangaCategories, interactor) = harness(
            bound = listOf(category(2, reading)),
            currentIds = listOf(2L),
        )

        interactor.await(mangaId = 10L, trackStatus = reading)

        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `await does not write when no category is bound to any status`() = runTest {
        val (_, setMangaCategories, interactor) = harness(
            bound = listOf(category(9, null)),
            currentIds = listOf(9L),
        )

        interactor.await(mangaId = 10L, trackStatus = reading)

        coVerify(exactly = 0) { setMangaCategories.await(any(), any()) }
    }

    @Test
    fun `await ignores an invalid manga id`() = runTest {
        val (repository, _, interactor) = harness(
            bound = listOf(category(2, reading)),
            currentIds = listOf(10L),
        )

        interactor.await(mangaId = 0L, trackStatus = reading)

        coVerify(exactly = 0) { repository.getAll() }
        coVerify(exactly = 0) { repository.getCategoriesByMangaId(any()) }
    }
}
