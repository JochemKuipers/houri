package tachiyomi.domain.chapter.service

fun interface ChapterTombstones {
    suspend fun remember(keys: Collection<String>)

    companion object {
        /**
         * Must match the SyncYomi server's chapter key byte for byte
         * (`<sourceId>|<mangaUrl>\u001f<chapterUrl>`). Keep in lockstep with
         * `eu.kanade.tachiyomi.data.sync.chapterTombstoneKey`.
         */
        fun key(sourceId: Long, mangaUrl: String, chapterUrl: String): String =
            "$sourceId|$mangaUrl\u001f$chapterUrl"
    }
}
