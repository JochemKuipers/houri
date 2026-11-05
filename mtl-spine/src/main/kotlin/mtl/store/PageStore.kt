package mtl.store

/**
 * Where translated pages live, in two layers:
 *
 * - **content-addressed**: hash(image bytes) + identity → translated bytes. Answers "has this
 *   page image been translated under this configuration before" for any chapter.
 * - **saved-page**: manga/chapter/position + identity → translated bytes, with the per-manga
 *   sidecar so a reader can show translated pages after the manga leaves the library.
 *
 * Implementations do IO (disk, pruning, sidecars); the spine only sees these operations.
 * Persistence policy (never / while-reading / auto) is a property of the implementation's
 * construction, not a flag threaded through calls.
 */
interface PageStore {

    /** Content-addressed lookup. Returns the encoded translated page, or null. */
    suspend fun lookupContent(contentKey: String): ByteArray?

    /** Saved-page lookup. */
    suspend fun lookupSavedPage(savedKey: String): ByteArray?

    /** Writes the content-addressed entry. Cheap and always-on. */
    suspend fun storeContent(contentKey: String, translatedBytes: ByteArray)

    /**
     * Writes the saved-page entry. Honors the implementation's persistence policy: callers
     * call this unconditionally and the store decides whether to keep it.
     */
    suspend fun storeSavedPage(
        savedKey: String,
        translatedBytes: ByteArray,
        mangaTitle: String?,
    )
}

/** In-memory implementation for tests and for the no-persistence mode. */
class InMemoryPageStore(
    private val persistSavedPages: Boolean = true,
) : PageStore {

    private val content = HashMap<String, ByteArray>()
    private val saved = HashMap<String, ByteArray>()
    private val titles = HashMap<String, String?>()

    override suspend fun lookupContent(contentKey: String): ByteArray? =
        synchronized(content) { content[contentKey] }

    override suspend fun lookupSavedPage(savedKey: String): ByteArray? =
        synchronized(saved) { if (persistSavedPages) saved[savedKey] else null }

    override suspend fun storeContent(contentKey: String, translatedBytes: ByteArray) {
        synchronized(content) { content[contentKey] = translatedBytes }
    }

    override suspend fun storeSavedPage(savedKey: String, translatedBytes: ByteArray, mangaTitle: String?) {
        if (!persistSavedPages) return
        synchronized(saved) {
            saved[savedKey] = translatedBytes
            titles[savedKey] = mangaTitle
        }
    }

    fun savedTitles(): Map<String, String?> = synchronized(saved) { titles.toMap() }
}
