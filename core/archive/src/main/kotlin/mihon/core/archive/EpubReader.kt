package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.InputStream

/**
 * A parsed EPUB file.
 *
 * Wraps [ArchiveReader] and adds the three things a reader needs that a plain zip does not give
 * you: a package document that says what the files mean, a spine that fixes the reading order, and
 * a table of contents.
 *
 * ### What changed from the original implementation
 *
 * The previous version parsed the OPF with Jsoup's XML mode and looked elements up **by tag name** —
 * `doc.getElementsByTag("dc:title")`. That couples correctness to the producer's namespace prefix: a
 * book that binds Dublin Core to `dcterms:` instead of `dc:` produced an empty title, an empty
 * author and an empty description, silently. It also read only four metadata fields, had no TOC, no
 * `encryption.xml` check, and detected the path separator by looking for a backslash inside
 * `container.xml` — which is never there, so the result was always `/` by accident rather than by
 * design.
 *
 * Parsing is now namespace-aware (see [EpubXmlReader]) and produces a complete [EpubBook].
 *
 * ### Laziness
 *
 * [book] is parsed once on first access and cached. Every accessor after that is a field read. The
 * underlying [ArchiveReader] stays open for the lifetime of this object — [close] releases it — so
 * content documents can be streamed on demand rather than held in memory, which is what keeps a
 * 50 MB illustrated EPUB from becoming a 50 MB heap allocation.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /** Path of the OPF package document, as recorded in `container.xml`. */
    private val opfPath: String by lazy { findPackageHref() }

    /** The parsed book: metadata, manifest, spine, TOC and encryption state. */
    val book: EpubBook by lazy { parseBook() }

    /**
     * The spine items a reader should show, in order.
     *
     * Non-linear items and non-content documents are excluded, so this is directly usable as a
     * chapter list.
     */
    val contentDocuments: List<EpubSpineItem> get() = book.readableSpine

    /** Dublin Core metadata. */
    val metadata: EpubMetadata get() = book.metadata

    /** The book's table of contents, flattened depth-first. Empty when it has none. */
    val toc: List<EpubTocNode> get() = book.flatToc

    /** Archive path of the cover image, or null when the book has none. */
    val coverHref: String? get() = book.coverHref

    /**
     * True when the book is DRM-protected and therefore unreadable.
     *
     * Font obfuscation — which most "protected" books actually use — does not set this; it leaves
     * the text perfectly readable.
     */
    val hasDrm: Boolean get() = book.encryption.hasDrm

    // SY/Upstream kept this narrow API; it still serves the image-oriented local source, which
    // treats an EPUB as a comic and renders its illustrations.
    // KMK -->

    /** Returns the path of the package document. */
    fun getPackageHref(): String = opfPath

    /**
     * Returns the package document as a Jsoup tree.
     *
     * Retained for the image-based local source. New code should read [metadata] instead: this
     * exposes namespace prefixes as literal tag names, so every selector against it has to hardcode
     * `dc:`.
     */
    fun getPackageDocument(ref: String): Document =
        getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }

    /**
     * Returns the path of all the images found in the epub file.
     *
     * Per-page, not per-document: the original walked the spine and collected `img` and `image`
     * references from each page's markup. That is the right answer for "render this EPUB as a
     * comic", where each illustration is a page. [textDocuments] is the right answer for reading it.
     */
    fun getImagesFromPages(): List<String> {
        val result = mutableListOf<String>()

        for (spineItem in book.spine) {
            val markup = readText(spineItem.href) ?: continue
            val pageDir = EpubPaths.parentOf(spineItem.href)
            val document = Jsoup.parse(markup)

            for (element in document.allElements) {
                when (element.tagName()) {
                    "img" -> element.attr("src")
                    "image" -> element.attr("xlink:href").ifEmpty { element.attr("href") }
                    else -> continue
                }.takeIf { it.isNotBlank() }?.let { result += EpubPaths.resolve(pageDir, it) }
            }
        }

        return result
    }
    // KMK <--

    /**
     * Opens an entry by archive path.
     *
     * Tries the path as given, then its percent-decoded and percent-encoded spellings, because
     * producers disagree about whether the name stored in the zip matches the `href` in the
     * manifest byte for byte. Without that fallback roughly half the library 404s on its own images.
     */
    fun getInputStream(entryName: String): InputStream? {
        for (candidate in EpubPaths.lookupCandidates(entryName)) {
            reader.getInputStream(candidate)?.let { return it }
        }
        return null
    }

    /**
     * True when [path] names an entry that exists.
     *
     * Cheaper than opening the entry, which is why the image and cover paths probe first.
     */
    fun contains(path: String): Boolean =
        EpubPaths.lookupCandidates(path).any { candidate ->
            reader.getInputStream(candidate)?.use { true } ?: false
        }

    /**
     * Reads an entry as text, or null when it does not exist.
     *
     * Charset comes from the XML declaration when there is one, because EPUB content files may be
     * UTF-16 or a legacy encoding; defaulting to UTF-8 renders mojibake for those. The whole entry
     * is read once and decoded afterwards — sniffing has to inspect the same bytes that are then
     * decoded, so the prolog cannot be consumed separately.
     */
    fun readText(path: String): String? = getInputStream(path)?.use { stream ->
        val bytes = stream.readBytes()
        String(bytes, charsetOf(bytes))
    }

    /**
     * The documents that make up the book's text, in reading order.
     *
     * This is the spine a reader paginates over — as opposed to [getImagesFromPages], which is the
     * spine treated as illustrations.
     */
    fun textDocuments(): List<EpubSpineItem> = book.readableSpine

    /**
     * Raw markup of one content document, suitable for handing straight to a WebView.
     *
     * Returned untouched on purpose. Publishers ship their own CSS, and the stripping that makes
     * text extraction easier is exactly what destroys the book — tables, drop caps, ruby
     * annotations, vertical writing. Cleanup belongs in the renderer, which at least can see the
     * document.
     */
    fun readDocument(item: EpubSpineItem): String? = readText(item.href)

    /** Best-effort encoding detection from the XML prolog, falling back to UTF-8. */
    private fun charsetOf(bytes: ByteArray): java.nio.charset.Charset {
        val prolog = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val match = ENCODING_PATTERN.find(prolog) ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(match.groupValues[1]) }
            .getOrDefault(Charsets.UTF_8)
    }

    /**
     * Locates the OPF through `META-INF/container.xml`, falling back to the conventional paths.
     *
     * The fallback is not paranoia: files produced by tools that skip `container.xml` still open
     * fine in every other reader, so refusing them would be a regression for no benefit.
     */
    private fun findPackageHref(): String {
        getInputStream("META-INF/container.xml")?.use { stream ->
            val href = EpubContainerParser.parseRootfilePath(stream)
            if (!href.isNullOrBlank()) return EpubPaths.normalize(href)
        }
        return FALLBACK_PACKAGE_PATHS.firstOrNull { contains(it) } ?: FALLBACK_PACKAGE_PATHS.first()
    }

    private fun parseBook(): EpubBook {
        val opf = getInputStream(opfPath)?.use { EpubOpfParser.parse(it, opfPath) }
            ?: OpfDocument(EpubMetadata(), emptyMap(), emptyList())

        val manifest = opf.manifest

        val navHref = manifest.values.firstOrNull { it.isNavigation }?.href
        val ncxHref = opf.metadata.ncxId?.let(manifest::get)?.href
        val toc = EpubTocParser.parse(navHref, ncxHref) { getInputStream(it) }

        val encryption = getInputStream("META-INF/encryption.xml")
            ?.use { EpubEncryptionParser.parse(it) }
            ?: EpubEncryption.NONE

        return EpubBook(
            metadata = opf.metadata,
            manifest = manifest,
            spine = opf.spine,
            toc = toc,
            encryption = encryption,
        )
    }

    override fun toString(): String = "EpubReader(package=$opfPath, spine=${book.spine.size})"

    private companion object {
        val ENCODING_PATTERN = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_.:-]+)[\"']")

        /** Conventional locations, tried in order when `container.xml` is missing or unreadable. */
        val FALLBACK_PACKAGE_PATHS = listOf(
            "OEBPS/content.opf",
            "EPUB/content.opf",
            "content.opf",
        )
    }
}

/** Reads `META-INF/container.xml` and returns the `full-path` of the first `rootfile`. */
internal object EpubContainerParser {

    fun parseRootfilePath(input: InputStream): String? {
        val xml = EpubXmlReader(input)
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.matches(EpubXmlReader.NS_CONTAINER, "rootfile")) {
                xml.attr("full-path")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }
}
