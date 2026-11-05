package mtl.store

import java.io.File

/**
 * The disk [PageStore]: one directory per layer, key-as-filename, writes through temp files.
 *
 * - **content-addressed** layer (`content/`): `sha256(image bytes) + identity → translated
 *   bytes`. Always written; pruned LRU-style to [contentMaxEntries] so a long session cannot
 *   grow it without bound. It answers "has this exact page image been translated under this
 *   exact configuration before" for any chapter.
 * - **saved-page** layer (`saved/`): `manga/chapter/page + identity → translated bytes`,
 *   written only when the [persistence policy][Persistence] says so. This is the layer the
 *   reader shows after the manga leaves the library; the sidecar title rides in the
 *   filename's sibling `.title` file.
 *
 * Keys are filesystem-safe (the spine's key builders already hash everything volatile); a
 * key that somehow still isn't is skipped with a warning rather than crashing a page job.
 */
class DiskPageStore(
    private val baseDir: File,
    private val persistence: Persistence = Persistence.AUTO,
    private val contentMaxEntries: Int = DEFAULT_CONTENT_MAX_ENTRIES,
) : PageStore {

    enum class Persistence {
        /** Keep nothing after the session; the content layer still answers within it. */
        NEVER,

        /** Keep translated pages only while the manga is in the library — the app decides by pointing [baseDir] at a per-session directory. */
        WHILE_READING,

        /** Keep everything; the reader shows translated pages for offline manga too. */
        AUTO,
    }

    private val contentDir = File(baseDir, "content").apply { mkdirs() }
    private val savedDir = File(baseDir, "saved").apply { mkdirs() }

    override suspend fun lookupContent(contentKey: String): ByteArray? =
        readFile(contentDir, contentKey)

    override suspend fun lookupSavedPage(savedKey: String): ByteArray? {
        if (persistence == Persistence.NEVER) return null
        return readFile(savedDir, savedKey)
    }

    override suspend fun storeContent(contentKey: String, translatedBytes: ByteArray) {
        writeFile(contentDir, contentKey, translatedBytes)
        pruneLru(contentDir, contentMaxEntries)
    }

    override suspend fun storeSavedPage(savedKey: String, translatedBytes: ByteArray, mangaTitle: String?) {
        if (persistence == Persistence.NEVER) return
        writeFile(savedDir, savedKey, translatedBytes)
        if (mangaTitle != null) {
            val titleFile = File(savedDir, safeKey(savedKey) + TITLE_SUFFIX)
            runCatching { titleFile.writeText(mangaTitle) }
        }
    }

    /** Total translated bytes on disk, for the settings screen's storage row. */
    fun sizeBytes(): Long = contentDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } +
        savedDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Clears both layers. Called when the user wipes translation storage. */
    fun clear() {
        contentDir.listFiles()?.forEach { it.delete() }
        savedDir.listFiles()?.forEach { it.delete() }
    }

    private fun readFile(dir: File, key: String): ByteArray? {
        val file = File(dir, safeKey(key))
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun writeFile(dir: File, key: String, bytes: ByteArray) {
        val target = File(dir, safeKey(key))
        val tmp = File(dir, safeKey(key) + TMP_SUFFIX)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // Rename across mount points fails; fall back to a direct write.
                target.writeBytes(bytes)
                tmp.delete()
            }
        } catch (_: Exception) {
            // A failed cache write must never fail the page job — the pipeline already
            // wraps store calls, and this layer deliberately stays free of Android logging
            // so it compiles (and runs) in the pure-JVM harness.
            runCatching { tmp.delete() }
        }
    }

    private fun safeKey(key: String): String {
        // Keys are hex/base64-ish today; this guards future formats and path traversal.
        return key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
    }

    /**
     * Keeps the content layer bounded: oldest-mtime-first eviction by access, since a cache
     * read refreshes nothing. Cheap enough to run per write (directory listing only) at the
     * write rate a reader produces.
     */
    private fun pruneLru(dir: File, maxEntries: Int) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
        const val TITLE_SUFFIX = ".title"

        /** ~2000 pages × ~700 KB WEBP ≈ 1.4 GB ceiling for the content layer. */
        const val DEFAULT_CONTENT_MAX_ENTRIES = 2000
    }
}
