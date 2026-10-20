// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubMimeSniff
import java.io.File
import java.util.Base64
import java.util.Locale

/**
 * The fonts a document can be set in: the [built-in][NovelBuiltinFonts] families the app ships,
 * and the ones [imported][PREFIX] by the reader, each reaching the page the same way every other
 * byte of it does — inside the markup, as a `data:` URI.
 *
 * ### Why the files are copied rather than referenced
 *
 * A document in this reader is a string handed to the engine, and a `file://` reference in it is
 * worth nothing here: the reader's own hardening closes the file scheme, and a SAF permission
 * granted to a picker is not the document's to inherit. So the picked file is copied once into
 * private storage — the same trust boundary the book covers live behind — and the font reaches the
 * document the same way every other byte of it does: inside the markup, as a `data:` URI the
 * engine cannot refuse (see [NovelInline] for why that channel in particular).
 *
 * ### The family name
 *
 * A stored family value of `"user/My Font"` names the font file `My Font.ttf`: the prefix keeps
 * user fonts from colliding with CSS's generic keywords (`serif`, `monospace`…), and the whole
 * value is also the CSS family name, quoted by [NovelTypographyCss.quoteFamily] — a CSS family
 * name is an arbitrary string, so the prefix costs nothing and the `@font-face` declaration and
 * the `font-family` rule cannot disagree about what the chosen font is called. The bundled
 * families follow the same convention (`builtin/<Family>`, see [NovelBuiltinFonts]), so both
 * answers to "which font" are one spelling apart and `load` is a two-way switch.
 */
object NovelFonts {

    /** Marks a font family value as one of the reader's own files. */
    const val PREFIX = "user/"

    /** A font file larger than this is refused; the largest real font is a few megabytes. */
    const val MAX_FONT_BYTES = 16L * 1024 * 1024

    private fun directory(context: Context): File =
        File(context.filesDir, "novel-fonts").apply { mkdirs() }

    /**
     * The reader's fonts, as family values — [PREFIX] plus the file's name without its extension.
     */
    fun list(context: Context): List<String> =
        directory(context).listFiles()
            ?.filter { it.isFile }
            ?.map { PREFIX + it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    /** Why an import did not happen. Each value answers its own toast, so "no" says *why*. */
    enum class ImportResult {
        /** Copied into the reader's storage; offered as `user/<name>`. */
        Success,

        /**
         * Readable, inside the size limit, and not a font by its bytes — the signatures
         * [EpubMimeSniff] already knows for the four shipping font formats.
         *
         * Validating the bytes rather than the picked file's extension is the point: providers
         * hand files over with names (`user-font`, `download (1)`) that say nothing, and this used
         * to import anything that fit — a text file named `my-font.ttf` arrived, displayed, was
         * chosen, and then silently did nothing, because the engine cannot load what is not a
         * font. Refusing at the door turns "the setting does nothing" into a toast.
         */
        NotAFont,

        /** Larger than [MAX_FONT_BYTES]. Real fonts are small; nothing legitimate hits this. */
        TooLarge,

        /** Unreadable through the picker, or a name that cannot safely become a file. */
        Unreadable,
    }

    /**
     * Copies the picked [uri] in, named after the document it names.
     *
     * The name is sanitised rather than trusted: it came from another app, and it is about to
     * become both a file name and part of a CSS string. The result is not an exception — for
     * anything the picker hands over that cannot become a font the reader will use, this is a
     * toast, not a crash.
     */
    fun import(context: Context, uri: Uri): ImportResult {
        val display = displayName(context, uri) ?: return ImportResult.Unreadable
        val safe = display.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (safe.isBlank()) return ImportResult.Unreadable
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return ImportResult.Unreadable
        if (bytes.isEmpty()) return ImportResult.Unreadable
        if (bytes.size > MAX_FONT_BYTES) return ImportResult.TooLarge
        // The sniff answers with an image MIME just as happily — only a font one passes here.
        if (EpubMimeSniff.of(bytes)?.startsWith("font/") != true) return ImportResult.NotAFont
        return try {
            File(directory(context), safe).writeBytes(bytes)
            ImportResult.Success
        } catch (_: Exception) {
            ImportResult.Unreadable
        }
    }

    /** One imported font: its bytes and the MIME type to declare them under. */
    data class Face(val bytes: ByteArray, val mimeType: String)

    /**
     * The font named by a family value, or null when it is gone or unreadable.
     *
     * The two prefixes the reader stores each name a font differently: `builtin/<Family>` is a
     * file inside the APK's assets, `user/<name>` one in this app's own storage. Anything without
     * a prefix still names a user file — the spelling an older stored preference carries — so the
     * unsuffixed case keeps reaching the same reader's files rather than falling out of the switch.
     */
    fun load(context: Context, family: String): Face? = when {
        family.startsWith(NovelBuiltinFonts.PREFIX) ->
            NovelBuiltinFonts.byValue(family)?.let { builtin(context, it) }
        else -> user(context, family)
    }

    /**
     * Removes a reader-imported font, by the family value the list offered.
     *
     * False for a built-in value — the app's own fonts have no counterpart in private storage to
     * delete, and a caller that offers delete for one is the caller's bug, not a crash here.
     */
    fun delete(context: Context, family: String): Boolean {
        if (!family.startsWith(PREFIX)) return false
        val file = userFile(context, family) ?: return false
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The reader-imported font's own file, when it exists — the picker previews from it.
     *
     * Separate from [user] because a preview wants the file for [Typeface.createFromFile] while
     * reaching the page wants the bytes, and the lookup — by name with or without its extension —
     * must be the same one or the picker would offer a font the page then cannot load.
     */
    fun userFile(context: Context, family: String): File? {
        val name = family.removePrefix(PREFIX)
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
        return try {
            val directory = directory(context)
            File(directory, name).takeIf { it.isFile }
                ?: directory.listFiles()?.firstOrNull { it.nameWithoutExtension == name }
        } catch (_: Exception) {
            null
        }
    }

    private fun user(context: Context, family: String): Face? = try {
        userFile(context, family)?.let { file ->
            Face(
                bytes = file.readBytes(),
                mimeType = when (file.extension.lowercase(Locale.US)) {
                    "woff2" -> "font/woff2"
                    "woff" -> "font/woff"
                    "otf", "otc" -> "font/otf"
                    else -> "font/ttf"
                },
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The [built-in][NovelBuiltinFonts] face, read out of the assets.
     *
     * The MIME type comes from the same extension table the streaming resource path uses — the
     * bundle is maintained like any other archive's font set, and a future `.otf` asset must not
     * be declared as a TTF by assumption.
     */
    private fun builtin(context: Context, family: NovelBuiltinFonts.Family): Face? = try {
        context.assets.open(family.asset).use { stream ->
            Face(
                bytes = stream.readBytes(),
                mimeType = EpubArchiveUrls.contentTypeForPath(family.asset) ?: "font/ttf",
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The `@font-face` that teaches the document the font [family] names.
     *
     * The family name is quoted — it is an arbitrary reader-chosen string, and CSS family names
     * in `@font-face` and in `font-family` must be spelled identically to find each other.
     */
    fun faceCss(family: String, face: Face): String {
        val encoded = Base64.getEncoder().encodeToString(face.bytes)
        return "@font-face { font-family: \"${family.replace("\"", "")}\"; " +
            "src: url(data:${face.mimeType};base64,$encoded); }"
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}
// KMK <--
