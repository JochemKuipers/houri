package tachiyomi.data.source

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class StubSourceRepositoryImpl(
    private val handler: DatabaseHandler,
) : StubSourceRepository {

    override fun subscribeAll(): Flow<List<StubSource>> {
        return handler.subscribeToList { sourceQueries.findAll(::mapStubSource) }
    }

    override suspend fun getStubSource(id: Long): StubSource? {
        return handler.awaitOneOrNull { sourceQueries.findOne(id, ::mapStubSource) }
    }

    override suspend fun upsertStubSource(id: Long, lang: String, name: String) {
        // The two string arguments pass by name: swapping them would still compile
        handler.await { sourceQueries.upsert(id = id, name = name, language = lang) }
    }

    private fun mapStubSource(
        id: Long,
        name: String,
        lang: String,
    ): StubSource = StubSource(id = id, lang = lang, name = name)
}
