package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.core.archive.EpubReader
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fills manga and chapter metadata using this epub file's metadata.
 *
 * ### Why every step here is nullable
 *
 * This runs against whatever `.epub` happens to sit in a folder, and a fair number of those are not
 * really books: a partial download, a file an extractor renamed, something with no OPF at all. Every
 * lookup below therefore degrades to "leave the field as it was" rather than throwing, because a
 * library refresh that dies on one malformed file takes the whole refresh — and every other folder —
 * with it.
 *
 * The one that used to be a hard crash is `getPackageDocument`, which returned `Document` and was
 * dereferenced with `!!`. A book whose `container.xml` names a file the archive does not contain is
 * malformed, not exceptional, and it took the scan down with a `NullPointerException`.
 */
fun EpubReader.fillMetadata(manga: SManga, chapter: SChapter) {
    val ref = getPackageHref()
    val doc = getPackageDocument(ref) ?: return

    val title = doc.getElementsByTag("dc:title").first()
    val publisher = doc.getElementsByTag("dc:publisher").first()
    val creator = doc.getElementsByTag("dc:creator").first()
    val description = doc.getElementsByTag("dc:description").first()
    var date = doc.getElementsByTag("dc:date").first()
    if (date == null) {
        date = doc.select("meta[property=dcterms:modified]").first()
    }

    creator?.text()?.let { manga.author = it }
    description?.text()?.let { manga.description = it }

    title?.text()?.let { chapter.name = it }

    if (publisher != null) {
        chapter.scanlator = publisher.text()
    } else if (creator != null) {
        chapter.scanlator = creator.text()
    }

    if (date != null) {
        parseEpubDate(date.text())?.let { chapter.date_upload = it }
    }
}

/**
 * Parses an EPUB `dc:date`, or null when it is not one this understands.
 *
 * `Locale.ROOT` rather than the default locale, and that is not pedantry: `SimpleDateFormat` picks
 * its calendar from the locale, so on a Thai device the Buddhist calendar shifts every year by 543
 * and every chapter's date lands seven centuries away. The pattern itself is fixed-width and has no
 * localised text, so there is nothing for a locale to disambiguate.
 *
 * The era in `Z` is also ignored deliberately — `Z` matches both `+02:00` and `Z`, and an offset is
 * an hour-scale detail that has never mattered for "when was this chapter added".
 */
private fun parseEpubDate(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.length < MIN_DATE_LENGTH) return null
    return try {
        SimpleDateFormat(DATE_PATTERN, Locale.ROOT).apply { isLenient = false }.parse(trimmed)?.time
    } catch (e: ParseException) {
        null
    }
}

/** `yyyy-MM-dd'T'HH:mm:ss` plus the offset or `Z` that producers append. */
private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ"

/** The shortest date worth trying: `yyyy-MM-dd`. Anything shorter is not parseable by the pattern. */
private const val MIN_DATE_LENGTH = 10
