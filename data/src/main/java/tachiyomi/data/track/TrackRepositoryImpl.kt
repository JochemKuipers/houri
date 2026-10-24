package tachiyomi.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TrackRepositoryImpl(
    private val handler: DatabaseHandler,
) : TrackRepository {

    override suspend fun getTrackById(id: Long): Track? {
        return handler.awaitOneOrNull { manga_trackQueries.getTrackById(id, TrackMapper::mapTrack) }
    }

    // SY -->
    override suspend fun getTracks(): List<Track> {
        return handler.awaitList {
            manga_trackQueries.getTracks(TrackMapper::mapTrack)
        }
    }

    override suspend fun getTracksByMangaIds(mangaIds: List<Long>): List<Track> {
        return handler.awaitList {
            manga_trackQueries.getTracksByMangaIds(mangaIds, TrackMapper::mapTrack)
        }
    }
    // SY <--

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
        return handler.awaitList {
            manga_trackQueries.getTracksByMangaId(mangaId, TrackMapper::mapTrack)
        }
    }

    override fun getTracksAsFlow(): Flow<List<Track>> {
        return handler.subscribeToList {
            manga_trackQueries.getTracks(TrackMapper::mapTrack)
        }
    }

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> {
        return handler.subscribeToList {
            manga_trackQueries.getTracksByMangaId(mangaId, TrackMapper::mapTrack)
        }
    }

    override suspend fun delete(mangaId: Long, trackerId: Long) {
        handler.await {
            manga_trackQueries.delete(
                mangaId = mangaId,
                trackerId = trackerId,
            )
        }
    }

    // KMK -->
    override suspend fun deleteAllByTrackerId(trackerId: Long) {
        handler.await {
            manga_trackQueries.deleteByTrackerId(trackerId = trackerId)
        }
    }
    // KMK <--

    override suspend fun insert(track: Track) {
        insertValues(track)
    }

    override suspend fun insertAll(tracks: List<Track>) {
        insertValues(*tracks.toTypedArray())
    }

    private suspend fun insertValues(vararg tracks: Track) {
        handler.await(inTransaction = true) {
            tracks.forEach { mangaTrack ->
                // KMK: upsert, as the old table's ON CONFLICT REPLACE clause used to make the
                // plain insert; a tracker is attached to an entry once.
                manga_trackQueries.upsert(
                    mangaId = mangaTrack.mangaId,
                    trackerId = mangaTrack.trackerId,
                    remoteId = mangaTrack.remoteId,
                    remoteUrl = mangaTrack.remoteUrl,
                    libraryId = mangaTrack.libraryId,
                    title = mangaTrack.title,
                    lastChapterRead = mangaTrack.lastChapterRead,
                    totalChapters = mangaTrack.totalChapters,
                    status = mangaTrack.status,
                    score = mangaTrack.score,
                    startDate = mangaTrack.startDate,
                    finishDate = mangaTrack.finishDate,
                    private = mangaTrack.private,
                    // KMK -->
                    rereadCount = mangaTrack.rereadCount.toLong(),
                    // KMK <--
                )
            }
        }
    }
}
