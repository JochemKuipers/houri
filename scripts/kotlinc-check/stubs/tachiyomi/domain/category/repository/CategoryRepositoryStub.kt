package tachiyomi.domain.category.repository

import tachiyomi.domain.category.model.Category

/** Offline stub for check.sh: only what PrefsUpscaleConfigSource calls. Never compiled into the app. */
interface CategoryRepository {
    suspend fun getCategoriesForManga(mangaId: Long): List<Category>
    suspend fun getAll(): List<Category>
}
