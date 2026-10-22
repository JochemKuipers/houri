package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

// KMK -->
/**
 * Assigns a subcategory's role (Author, Artist, Illustrator, Designer) or clears it back to
 * unclassified with a `null` type, mirroring [SetCategoryTrackStatus]'s null-clears path.
 */
@Inject
class SetCategoryType(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(categoryId: Long, type: Int?) {
        if (categoryId == Category.UNCATEGORIZED_ID) return
        categoryRepository.updateType(categoryId, type)
    }
}
// KMK <--
