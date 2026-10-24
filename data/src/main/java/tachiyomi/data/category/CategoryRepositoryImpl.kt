package tachiyomi.data.category

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CategoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return handler.awaitOneOrNull { categoryQueries.getCategory(id, CategoryMapper::mapCategory) }
    }

    override suspend fun getAll(): List<Category> {
        return handler.awaitList { categoryQueries.getCategories(CategoryMapper::mapCategory) }
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return handler.subscribeToList { categoryQueries.getCategories(CategoryMapper::mapCategory) }
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return handler.awaitList {
            categoryQueries.getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
        }
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return handler.subscribeToList {
            categoryQueries.getCategoriesByMangaId(mangaId, CategoryMapper::mapCategory)
        }
    }

    // SY -->
    override suspend fun insert(category: Category): Long {
        return handler.awaitOneExecutable(true) {
            categoryQueries.insert(
                name = category.name,
                order = category.order,
                flags = category.flags,
                // KMK -->
                hidden = if (category.hidden) 1L else 0L,
                parentId = category.parentId,
                type = category.type?.toLong(),
                // KMK <--
            )
            categoryQueries.selectLastInsertedRowId()
        }
    }
    // SY <--

    override suspend fun updatePartial(update: CategoryUpdate) {
        handler.await {
            updatePartialBlocking(update)
        }
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        handler.await(inTransaction = true) {
            for (update in updates) {
                updatePartialBlocking(update)
            }
        }
    }

    private fun Database.updatePartialBlocking(update: CategoryUpdate) {
        categoryQueries.update(
            name = update.name,
            order = update.order,
            flags = update.flags,
            // KMK -->
            hidden = update.hidden?.let { if (it) 1L else 0L },
            parentId = update.parentId,
            type = update.type?.toLong(),
            // KMK <--
            id = update.id,
        )
    }

    override suspend fun updateAllFlags(flags: Long) {
        handler.await {
            categoryQueries.updateAllFlags(flags)
        }
    }

    override suspend fun delete(categoryId: Long) {
        handler.await {
            categoryQueries.delete(
                id = categoryId,
            )
        }
    }

    // KMK -->
    override suspend fun getSubcategories(parentId: Long): List<Category> {
        return handler.awaitList { categoryQueries.getSubcategories(parentId, CategoryMapper::mapCategory) }
    }

    override fun getSubcategoriesAsFlow(parentId: Long): Flow<List<Category>> {
        return handler.subscribeToList { categoryQueries.getSubcategories(parentId, CategoryMapper::mapCategory) }
    }

    override suspend fun getTopLevelCategories(): List<Category> {
        return handler.awaitList { categoryQueries.getTopLevelCategories(CategoryMapper::mapCategory) }
    }

    override suspend fun getMangaIdsInCategoryTree(categoryId: Long): List<Long> {
        return handler.awaitList { categoryQueries.getMangaIdsInCategoryTree(categoryId) }
    }

    override suspend fun deleteOrphanedSubcategories() {
        handler.await {
            categoryQueries.deleteOrphanedSubcategories()
        }
    }

    override suspend fun updateTrackStatus(categoryId: Long, trackStatus: Int?) {
        handler.await {
            categoryQueries.updateTrackStatus(
                trackStatus = trackStatus?.toLong(),
                id = categoryId,
            )
        }
    }

    override suspend fun updateUpdateInterval(categoryId: Long, updateInterval: Int?) {
        handler.await {
            categoryQueries.updateUpdateInterval(
                updateInterval = updateInterval?.toLong(),
                id = categoryId,
            )
        }
    }

    override suspend fun updateType(categoryId: Long, type: Int?) {
        handler.await {
            categoryQueries.updateType(
                type = type?.toLong(),
                id = categoryId,
            )
        }
    }
    // KMK <--
}
