package mtl.sched

/** Identifies one chapter's work stream. */
data class ChapterKey(val mangaId: Long, val chapterId: Long)

/** Dispatch priority: what the reader is showing beats speculative work. */
enum class PagePriority { VISIBLE, READ_AHEAD }

/**
 * One page's unit of work, as the queue carries it.
 *
 * [imageBytes] are the original encoded page; the pipeline decodes, and the content hash for
 * the cache is computed over exactly these bytes.
 */
data class PageWork(
    val key: ChapterKey,
    val pageIndex: Int,
    val imageBytes: ByteArray,
    val sourceLangHint: String,
    val priority: PagePriority = PagePriority.VISIBLE,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
