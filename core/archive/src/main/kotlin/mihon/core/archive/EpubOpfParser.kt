package mihon.core.archive

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** An `<itemref>` before its idref has been resolved against the manifest. */
private data class SpineRef(val idref: String, val linear: Boolean)

/** Everything one pass over an OPF package document yields. */
internal data class OpfDocument(
    val metadata: EpubMetadata,
    val manifest: Map<String, EpubResource>,
    /** Itemrefs already resolved against [manifest]; entries with no matching item are dropped. */
    val spine: List<EpubSpineItem>,
)

/**
 * Parser for the OPF package document.
 *
 * One linear pass collects metadata, manifest and spine together: the three blocks appear in a fixed
 * order in the spec but a single walk avoids buffering, and a producer that emits them out of order
 * still parses correctly.
 *
 * Unresolved spine entries are dropped rather than turned into placeholder items — a manifest that
 * does not contain an itemref target is a broken package, and the reader reports it as "no readable
 * chapters" instead of showing an entry that can never open.
 */
internal object EpubOpfParser {

    fun parse(input: InputStream, opfPath: String): OpfDocument {
        val baseDir = EpubPaths.parentOf(opfPath)
        val xml = EpubXmlReader(input)

        val titles = mutableListOf<String>()
        val authors = mutableListOf<String>()
        val subjects = mutableListOf<String>()
        val identifiers = mutableMapOf<String, String>()
        val manifest = LinkedHashMap<String, EpubResource>()
        val spineRefs = mutableListOf<SpineRef>()

        var publisher: String? = null
        var language: String? = null
        var description: String? = null
        var date: String? = null
        var rights: String? = null
        var coverId: String? = null
        var ncxId: String? = null
        var uniqueIdentifierId: String? = null
        // dcterms:modified is a fallback only: it dates the *file*, not the work, so dc:date wins
        // regardless of which order the producer emitted them in.
        var modified: String? = null
        // KMK --> Series identity, in the two spellings producers use. The calibre pair is stamped
        // content of attributes; the EPUB3 form is a `belongs-to-collection` meta whose name only
        // means "series" once a `collection-type: series` refine confirms it — held pending until
        // that refine arrives, which in every real book is the very next line.
        var calibreSeries: String? = null
        var calibreSeriesIndex: String? = null
        var pendingCollection: String? = null
        var pendingCollectionId: String? = null
        var pendingCollectionIndex: String? = null
        var confirmedCollection = false
        // KMK <--

        var event = xml.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) {
                event = xml.next()
                continue
            }

            when {
                xml.matches(EpubXmlReader.NS_OPF, "package") -> {
                    uniqueIdentifierId = xml.attr("unique-identifier")
                    event = xml.next()
                }

                // <meta> carries everything not expressible as dc:*, always as attributes.
                xml.matches(EpubXmlReader.NS_OPF, "meta") -> {
                    val name = xml.attr("name")?.trim()
                    val property = xml.attr("property")?.trim()
                    when {
                        name == "cover" -> {
                            xml.attr("content")?.let { coverId = it.trim() }
                        }
                        // KMK --> Calibre's own pair: stamped in attributes, no confirmation needed.
                        name == "calibre:series" -> {
                            xml.attr("content")?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { calibreSeries = it }
                        }
                        name == "calibre:series_index" -> {
                            xml.attr("content")?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { calibreSeriesIndex = it }
                        }
                        // KMK <--

                        property == "dcterms:modified" -> {
                            modified = xml.textContent().ifBlank { modified }
                        }

                        // KMK --> The EPUB3 collection forms, each carrying its value as text content.
                        // `collection-type` and `group-position` confirm themselves *to the pending
                        // collection*: a refine names it by id, and a book with but one collection
                        // may omit the refine entirely. A confirm arriving for a collection that has
                        // not been seen is ignored rather than buffered — producers emit these in the
                        // spec's own order, and buffering confirmations before their target would be
                        // a second pending state for one malformed book. `textContent()` follows the
                        // parser contract and leaves the walk on the element's own closing tag.
                        property == "belongs-to-collection" -> {
                            pendingCollectionId = xml.attr("id")?.trim()
                            val text = xml.textContent().trim()
                            if (text.isNotBlank()) pendingCollection = text
                        }
                        property == "collection-type" -> {
                            val refines = xml.attr("refines")?.trim()?.removePrefix("#")
                            val type = xml.textContent().trim()
                            if (type.equals("series", ignoreCase = true) &&
                                (refines == null || refines == pendingCollectionId)
                            ) {
                                confirmedCollection = true
                            }
                        }
                        property == "group-position" -> {
                            val refines = xml.attr("refines")?.trim()?.removePrefix("#")
                            val value = xml.textContent().trim()
                            if (value.isNotBlank() && (refines == null || refines == pendingCollectionId)) {
                                pendingCollectionIndex = value
                            }
                        }
                        // KMK <--
                    }
                    event = xml.next()
                }

                // KMK --> Every Dublin Core branch steps past the tag it just read, and that step is
                // load-bearing rather than tidy. `textContent()` leaves the parser on the element's
                // closing tag; a branch that returns without advancing leaves `event` still saying
                // START_TAG, so the loop re-enters `when` *on that closing tag*. `matches()` is true
                // of an END_TAG exactly as it is of a START_TAG, so the same branch ran a second time
                // — and the second read starts from an end tag and walks out of the element,
                // consuming every sibling up to the end of `<metadata>`. Any book with more than one
                // `dc:` element, which is every real book, therefore lost its title, author,
                // publisher, language and date, and the file name stood in for all of them.
                //
                // Every field here except `dc:description` is what a book *displays as text* — its
                // title, its author line — and reads as plain text (`textContentAsPlainText`):
                // producers nest markup inside these elements, and serialising it preserved junk a
                // user can see (`<creator>J.K. Rowling` rendered in a subtitle`). `dc:description`
                // is authored HTML on purpose and keeps `textContent()` so the round-trip to the
                // description renderer stays lossless.
                xml.matches(EpubXmlReader.NS_DC, "title") -> {
                    titles += xml.textContentAsPlainText()
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "creator") -> {
                    authors += xml.textContentAsPlainText()
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "publisher") -> {
                    publisher = xml.textContentAsPlainText().ifBlank { publisher }
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "language") -> {
                    language = xml.textContentAsPlainText().ifBlank { language }
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "description") -> {
                    description = xml.textContent().ifBlank { description }
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "date") -> {
                    date = xml.textContentAsPlainText().ifBlank { date }
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "rights") -> {
                    rights = xml.textContentAsPlainText().ifBlank { rights }
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "subject") -> {
                    subjects += xml.textContentAsPlainText()
                    event = xml.next()
                }
                xml.matches(EpubXmlReader.NS_DC, "identifier") -> {
                    val id = xml.attr("id") ?: identifiers.size.toString()
                    identifiers[id] = xml.textContentAsPlainText()
                    event = xml.next()
                }

                xml.matches(EpubXmlReader.NS_OPF, "item") -> {
                    val id = xml.attr("id")?.trim().orEmpty()
                    val href = xml.attr("href")?.trim().orEmpty()
                    val mediaType = xml.attr("media-type")?.trim().orEmpty()
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        manifest[id] = EpubResource(
                            id = id,
                            href = EpubPaths.resolve(baseDir, href),
                            mediaType = mediaType,
                            properties = xml.attrOrEmpty("properties").propertyTokens(),
                        )
                    }
                    event = xml.next()
                }

                xml.matches(EpubXmlReader.NS_OPF, "spine") -> {
                    ncxId = xml.attr("toc")?.trim()
                    event = xml.next()
                }

                xml.matches(EpubXmlReader.NS_OPF, "itemref") -> {
                    val idref = xml.attr("idref")?.trim()
                    if (!idref.isNullOrEmpty()) {
                        val linearAttr = xml.attr("linear")?.trim()?.lowercase()
                        spineRefs += SpineRef(idref, linearAttr != "no")
                    }
                    event = xml.next()
                }

                else -> event = xml.next()
            }
        }

        // A spine entry survives only if the manifest actually describes it. A dangling idref means
        // a broken package, and surfacing it as a placeholder would produce a chapter that can never
        // open — better to report the book as having fewer chapters.
        val spine = spineRefs.mapNotNull { ref ->
            val resource = manifest[ref.idref] ?: return@mapNotNull null
            EpubSpineItem(
                id = resource.id,
                href = resource.href,
                mediaType = resource.mediaType,
                linear = ref.linear,
                properties = resource.properties,
            )
        }

        val identifier = uniqueIdentifierId?.let { identifiers[it] } ?: identifiers.values.firstOrNull()

        // KMK --> Series fields, calibre's pair outranking the confirmed collection — when a book
        // carries both it carries both from the same producer, and the stamp needs no refine. The
        // index is parsed leniently: it is an out-of-band editorial number ("2" and "3.5"), and a
        // producer that wrote something which simply is not a number costs the index, not the series.
        val series = calibreSeries ?: pendingCollection.takeIf { confirmedCollection }
        val seriesIndex = (calibreSeriesIndex ?: pendingCollectionIndex.takeIf { confirmedCollection })
            ?.let { it.trim().toFloatOrNull() }
        // KMK <--

        val metadata = EpubMetadata(
            title = titles.firstOrNull { it.isNotBlank() }?.takeIf { it.isNotBlank() },
            subtitle = titles.drop(1).filter { it.isNotBlank() }.joinToString(": ").ifBlank { null },
            authors = authors.filter { it.isNotBlank() },
            publisher = publisher,
            language = language,
            description = description,
            date = date ?: modified,
            rights = rights,
            subjects = subjects.filter { it.isNotBlank() },
            coverManifestId = coverId,
            ncxId = ncxId,
            identifier = identifier,
            series = series,
            seriesIndex = seriesIndex,
        )

        return OpfDocument(metadata, manifest, spine)
    }
}
