package tachiyomi.domain.category.model

/** Offline stub for check.sh. Never compiled into the app. */
data class Category(
    val id: Long,
    val name: String,
    val order: Int = 0,
    val flags: Long = 0,
)
