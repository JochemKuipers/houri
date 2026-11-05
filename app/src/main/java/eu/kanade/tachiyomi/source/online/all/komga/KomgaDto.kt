package eu.kanade.tachiyomi.source.online.all.komga

import kotlinx.serialization.Serializable

/**
 * DTOs for the Komga API shapes this source reads. Only the fields the app acts on are
 * declared; the server sends more and the rest is ignored (no `ignoreUnknownKeys = false`
 * anywhere — the shared `Json` in `globalAppGraph.json` is lenient).
 */

@Serializable
data class PageWrapperDto<T>(
    val content: List<T>,
    val totalElements: Long = 0,
    val totalPages: Int = 1,
    val number: Int = 0,
    val last: Boolean = true,
)

@Serializable
data class LibraryDto(
    val id: String,
    val name: String,
)

@Serializable
data class SeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String? = null,
    val booksCount: Int = 0,
    val deleted: Boolean = false,
    val metadata: SeriesMetadataDto,
    val booksMetadata: BookMetadataAggregationDto? = null,
)

@Serializable
data class SeriesMetadataDto(
    val title: String = "",
    val titleSort: String = "",
    val summary: String = "",
    val status: String = "",
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val publisher: String = "",
    val language: String = "",
)

@Serializable
data class BookMetadataAggregationDto(
    val authors: List<AuthorDto> = emptyList(),
    val releaseDate: String? = null,
    val summary: String = "",
    val summaryNumber: String = "",
    val created: String = "",
    val lastModified: String = "",
)

@Serializable
data class AuthorDto(
    val name: String,
    val role: String = "",
)

@Serializable
data class BookDto(
    val id: String,
    val libraryId: String = "",
    val seriesId: String = "",
    val seriesTitle: String = "",
    val name: String = "",
    val number: Int = 0,
    val oneshot: Boolean = false,
    val fileHash: String = "",
    val created: String? = null,
    val fileLastModified: String? = null,
    val readProgress: ReadProgressDto? = null,
    val media: MediaDto = MediaDto(),
    val metadata: BookMetadataDto = BookMetadataDto(),
)

@Serializable
data class BookMetadataDto(
    val title: String = "",
    val number: String = "",
    val numberSort: Float = 0f,
    val releaseDate: String? = null,
    val summary: String = "",
    val authors: List<AuthorDto> = emptyList(),
)

@Serializable
data class MediaDto(
    val status: String = "",
    val mediaProfile: String = "",
    val mediaType: String = "",
    val pagesCount: Int = 0,
    val epubDivinaCompatible: Boolean = false,
    val epubIsKepub: Boolean = false,
)

@Serializable
data class ReadProgressDto(
    val completed: Boolean = false,
    val page: Int = 0,
    val readDate: String = "",
)

@Serializable
data class PageDto(
    val number: Int,
    val fileName: String = "",
    val mediaType: String = "image/jpeg",
)
