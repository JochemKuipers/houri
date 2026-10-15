// Stub for the libarchive-backed ArchiveReader, which cannot compile without the native library on
// the classpath. Only used by /tmp/opencode/synchk/check.sh.
@file:Suppress("unused")

package mihon.core.archive

import java.io.Closeable
import java.io.InputStream

class ArchiveReader : Closeable {
    val encrypted: Boolean = false

    fun getInputStream(name: String): InputStream? = null

    override fun close() = Unit
}