package mihon.core.archive

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Namespace-aware pull parser over the EPUB structural documents.
 *
 * Uses [XmlPullParser] with namespace processing enabled rather than Jsoup's XML mode. Jsoup keeps
 * the literal prefix in the tag name (`dc:title`), so every lookup would depend on whichever prefix
 * the publisher happened to choose — a book that binds Dublin Core to `dcterms:` instead of `dc:`
 * would parse into an empty title. Matching on the namespace URI is the only correct way, and it is
 * what the spec defines.
 *
 * Deliberately a thin wrapper, not a DOM: the structural documents are small and streaming them
 * keeps a 500-chapter book's `nav.xhtml` from being materialised as a tree the parser never needs
 * twice.
 *
 * Callers drive it with the usual pull-parser shape:
 * ```
 * val xml = EpubXmlReader(stream)
 * while (xml.next() == XmlPullParser.START_TAG) { ... }
 * ```
 */
internal class EpubXmlReader private constructor(private val parser: XmlPullParser) {

    /**
     * Builds the parser for an EPUB structural document.
     *
     * A companion function rather than inline constructor code because it has to run *before* `this`
     * exists: the secondary constructor delegates to the primary one, so anything reaching an instance
     * member from in here is "access before initialisation". See [checkedParser].
     */
    constructor(input: InputStream) : this(checkedParser(input))

    /** Advances to the next event and returns it. */
    fun next(): Int = parser.next()

    /** Local name of the current element, with any prefix already removed by the parser. */
    val name: String get() = parser.name

    /** Namespace URI of the current element, or "" when it declares none. */
    val namespace: String get() = parser.namespace.orEmpty()

    /** True when the current element is [localName] in [namespaceUri]. */
    fun matches(namespaceUri: String, localName: String): Boolean =
        parser.name == localName && parser.namespace.orEmpty() == namespaceUri

    /**
     * Attribute value by local name, ignoring its namespace.
     *
     * `href`, `media-type` and `full-path` are unprefixed in every producer, and matching the bare
     * name tolerates the stray prefix some writers put on OPF attributes.
     */
    fun attr(name: String): String? = parser.getAttributeValue(null, name)

    /**
     * Attribute value looked up by namespace **and** local name.
     *
     * Required for prefixed attributes once [FEATURE_PROCESS_NAMESPACES] is on: `epub:type` is not
     * in any namespace under the bare name `type`, so [attr] cannot see it at all. Getting this
     * wrong means the EPUB 3 navigation document is read as a landmarks list and the user is shown
     * "Start of content" instead of the chapter list.
     */
    fun attr(namespaceUri: String, localName: String): String? =
        parser.getAttributeValue(namespaceUri, localName)

    fun attrOrEmpty(name: String): String = attr(name).orEmpty().trim()

    fun hasAttr(name: String): Boolean = attr(name) != null

    /**
     * Reads the full text content of the element the parser is currently positioned on, including
     * child elements, re-serialised as escaped markup.
     *
     * `dc:description` routinely carries HTML paragraphs, and dropping the tags would silently
     * discard what the publisher wrote. Re-serialising keeps the round-trip lossless: the caller
     * decides later whether to parse it as HTML.
     *
     * The parser must be positioned on a START_TAG. On return it is positioned on the matching
     * END_TAG, so a caller that walks with `next()` continues from the right place.
     */
    fun textContent(): String {
        val out = StringBuilder()
        appendTextContentTo(out)
        return out.toString()
    }

    /**
     * Appends the current element's content, abandoning the element once [MAX_TEXT_CHARS] is
     * reached.
     *
     * Bounded because both callers are exposed to hostile input: a book's TOC can nest a label
     * containing anything, and a `dc:description` is unbounded markup re-serialised into a
     * `StringBuilder` before anybody decides whether it is worth showing. The parser is left
     * mid-subtree, which is safe — every caller's loop terminates on `END_DOCUMENT`.
     */
    private fun appendTextContentTo(out: StringBuilder) {
        val startDepth = parser.depth
        while (out.length < MAX_TEXT_CHARS) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    out.append(parser.text.orEmpty())
                    // Truncate rather than abandon: a single text node can itself be enormous, and a
                    // 40 MB `dc:description` should cost a truncated description, not the whole parse.
                    if (out.length > MAX_TEXT_CHARS) out.setLength(MAX_TEXT_CHARS)
                }
                XmlPullParser.START_TAG -> {
                    out.append('<').append(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        out.append(' ')
                            .append(parser.getAttributeName(i))
                            .append("=\"")
                            .append(
                                parser.getAttributeValue(i).orEmpty()
                                    .replace("&", "&amp;")
                                    .replace("\"", "&quot;")
                                    .replace("<", "&lt;"),
                            )
                            .append('"')
                    }
                    out.append('>')
                }
                // A self-closing element re-emits as <x></x> rather than <x/>; both parse identically.
                XmlPullParser.END_TAG -> if (parser.depth < startDepth) return
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }

    companion object {
        /**
         * The platform parser, over an input with any doctype removed by [EpubDoctype.strip].
         *
         * `FEATURE_PROCESS_DOCDECL = false` is the parser-level half of the same XXE defence; see
         * [EpubDoctype] for why the input-level half is the one that carries the guarantee, and for
         * why asserting things about the parser implementation was the wrong way round.
         */
        private fun checkedParser(input: InputStream): XmlPullParser =
            Xml.newPullParser().apply {
                setInput(EpubDoctype.strip(input), null)
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }

        /**
         * Ceiling on one [textContent] result.
         *
         * A real TOC label is under a kilobyte and a real `dc:description` under a few. This is two
         * orders of magnitude above both, so nothing legitimate is affected, and it is low enough
         * that a book cannot make the parse allocate its way into a low-memory kill.
         */
        const val MAX_TEXT_CHARS = 256 * 1024

        const val NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
        const val NS_OPF = "http://www.idpf.org/2007/opf"
        const val NS_DC = "http://purl.org/dc/elements/1.1/"
        const val NS_NCX = "http://www.daisy.org/z3986/2005/ncx/"
        const val NS_ENCRYPTION = "http://www.w3.org/2001/04/xmlenc#"
        const val NS_ENC_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"

        /** `epub:type` vocabulary, used to pick the TOC out of a nav document. */
        const val NS_EPUB = "http://www.idpf.org/2007/ops"

        /** XHTML vocabulary used by EPUB content and navigation documents. */
        const val NS_XHTML = "http://www.w3.org/1999/xhtml"
    }
}

/** Splits a whitespace-separated attribute value such as `nav scripted`. */
internal fun String.propertyTokens(): List<String> =
    split(' ', '\t', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
