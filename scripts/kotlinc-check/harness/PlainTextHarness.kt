// Assertions for the Moon+ Reader port's pure halves: the webnovel plain-text converter, the
// footnote extractor, the status line formatter, the new typography rules, and the auto-scroll
// command grammar. Every file asserted here is import-free by design, so it links on a plain JVM.
//
// The HTML entities the escape tests assert on are built from Kotlin unicode escapes rather than
// written literally, so no layer that handles this file can mistake them for source markup.
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelAutoScroll
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelHighlights
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelPlainText
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelScrollBridge
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelStatusLine
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypographyCss
import mihon.core.archive.EpubFootnotes

private val AMP = "\u0026"
private val LT_ENT = AMP + "lt;"
private val GT_ENT = AMP + "gt;"
private val AMP_ENT = AMP + "amp;"

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
}

private fun checkTrue(name: String, actual: Boolean) = check(name, actual, true)

private fun contains(name: String, haystack: String, needle: String) {
    check(name, haystack.contains(needle), true)
}

fun main() {
    // ---------------- NovelPlainText: the paragraph contract ----------------
    val all = NovelPlainText.Cleanup(trimBlankLines = true, trimSpaces = true, smartParagraphs = true)

    // The documented contract: line breaks ARE paragraph breaks.
    check(
        "smart: each line is a paragraph",
        NovelPlainText.toHtml("one\ntwo\nthree", all),
        "<p>one</p><p>two</p><p>three</p>",
    )

    // Blank lines collapse: three separators make two paragraphs, not six empty ones.
    check(
        "smart: blank runs collapse",
        NovelPlainText.toHtml("a\n\n\n\nb", all),
        "<p>a</p><p>b</p>",
    )

    // Non-smart: single newlines are soft wraps, blank lines separate.
    val wrapped = NovelPlainText.Cleanup(true, true, smartParagraphs = false)
    check(
        "non-smart: newline is a break",
        NovelPlainText.toHtml("line one\nline two\n\npara two", wrapped),
        "<p>line one<br>line two</p><p>para two</p>",
    )

    // Markup-shaped text survives as characters: the stored fragment carries entities, which the
    // engine renders back as the characters the author wrote.
    check(
        "escape",
        NovelPlainText.toHtml("<HP+10> & <gold>", all),
        "<p>" + LT_ENT + "HP+10" + GT_ENT + " " + AMP_ENT + " " + LT_ENT + "gold" + GT_ENT + "</p>",
    )

    // Cleanup: leading/trailing spaces and internal runs, but NOT the ideographic indent.
    check(
        "trim spaces",
        NovelPlainText.toHtml("   spaced   line\t\there  ", all),
        "<p>spaced line here</p>",
    )
    checkTrue(
        "ideographic indent survives",
        NovelPlainText.toHtml("\u3000\u3000indented", all).contains("\u3000\u3000indented"),
    )

    // Trim off, everything is kept as written (minus escaping).
    val raw = NovelPlainText.Cleanup(trimBlankLines = false, trimSpaces = false, smartParagraphs = true)
    check(
        "cleanup off keeps spacing",
        NovelPlainText.toHtml("  keep  \n  me  ", raw),
        "<p>  keep  </p><p>  me  </p>",
    )

    // CRLF and lone CR are the same line breaks.
    check(
        "crlf",
        NovelPlainText.toHtml("a\r\nb\rc", all),
        "<p>a</p><p>b</p><p>c</p>",
    )

    // Blank input converts to an empty fragment.
    check("blank input", NovelPlainText.toHtml("  \n \n ", all), "")

    // The cache token distinguishes every combination.
    check(
        "cache token varies",
        setOf(
            NovelPlainText.Cleanup(false, false, false).cacheToken,
            NovelPlainText.Cleanup(true, false, false).cacheToken,
            NovelPlainText.Cleanup(true, true, false).cacheToken,
            NovelPlainText.Cleanup(true, true, true).cacheToken,
        ).size,
        4,
    )

    // ---------------- EpubFootnotes: extraction out of a hostile document ----------------
    val aside = """<p>text</p><aside id="fn1" epub:type="footnote"><p>the note</p></aside><p>after</p>"""
    check(
        "aside by id",
        EpubFootnotes.extract(aside, "fn1"),
        """<aside id="fn1" epub:type="footnote"><p>the note</p></aside>""",
    )

    // Single-quoted id, and the legacy name anchor.
    checkTrue(
        "single quotes",
        EpubFootnotes.extract("""<li id='n2'>note two</li>""", "n2") == "<li id='n2'>note two</li>",
    )
    checkTrue(
        "legacy a name",
        EpubFootnotes.extract("""<a name="fn3">the note</a>""", "fn3") == """<a name="fn3">the note</a>""",
    )

    // An <aside> note that contains a link closes at its OWN tag, not at the link's.
    checkTrue(
        "inner link does not close",
        EpubFootnotes.extract(
            """<aside id="x">see <a href="q">this</a> note</aside>""",
            "x",
        ) == """<aside id="x">see <a href="q">this</a> note</aside>""",
    )

    // A missing close tag yields null (navigation fallback), not a truncated run to EOF.
    check(
        "no close tag",
        EpubFootnotes.extract("""<aside id="x">unterminated""", "x"),
        null,
    )

    // An id mentioned inside an attribute value is not an element carrying it.
    check(
        "attribute mention",
        EpubFootnotes.extract("""<p title="see fn9">nothing</p>""", "fn9"),
        null,
    )

    // An id-bearing shell with no text is a placeholder, not a note.
    check(
        "empty note",
        EpubFootnotes.extract("""<aside id="e">   </aside>""", "e"),
        null,
    )

    // Case-insensitive closing tag of a differently-cased opener.
    checkTrue(
        "case-insensitive close",
        EpubFootnotes.extract("""<ASIDE ID="c">note</aside>""", "c") == """<ASIDE ID="c">note</aside>""",
    )

    // The ceiling: a document whose note never closes within budget returns null.
    val huge = buildString {
        append("""<aside id="big">""")
        repeat(40_000) { append("x") }
    }
    check("oversize note", EpubFootnotes.extract(huge, "big"), null)

    // ---------------- NovelStatusLine: the margin facts ----------------
    val allFields = setOf(
        NovelStatusLine.Field.TIME,
        NovelStatusLine.Field.PROGRESS,
        NovelStatusLine.Field.CHAPTER,
        NovelStatusLine.Field.BATTERY,
    )
    check(
        "all fields",
        NovelStatusLine.format(allFields, "12:34", 42, "Chapter 3", 77, separator = " | "),
        "12:34 | 42% | Chapter 3 | 77%",
    )

    // A field with no value contributes nothing — including no separator.
    check(
        "missing values drop out",
        NovelStatusLine.format(allFields, null, 42, "", null, separator = " | "),
        "42%",
    )

    // An empty field set renders nothing.
    check("empty fields", NovelStatusLine.format(emptySet(), "12:34", 1, "c", 1), "")

    // Only the enabled subset renders, in the fixed field order.
    check(
        "subset order",
        NovelStatusLine.format(
            setOf(NovelStatusLine.Field.BATTERY, NovelStatusLine.Field.TIME),
            "09:00",
            10,
            "c",
            55,
            separator = " \u00B7 ",
        ),
        "09:00 \u00B7 55%",
    )

    // Unknown ordinals are dropped, not fatal (a hand-edited or migrated pref).
    check(
        "unknown ordinals dropped",
        NovelStatusLine.Field.fromOrdinals(setOf(0, 99, 3)),
        setOf(NovelStatusLine.Field.TIME, NovelStatusLine.Field.BATTERY),
    )

    // ---------------- Typography CSS: the new rules ----------------
    val italicOn = NovelTypographyCss.stylesheet(NovelTypography(italicText = true))
    contains("italic on", italicOn, "font-style: italic !important")
    val italicOff = NovelTypographyCss.stylesheet(NovelTypography(italicText = false))
    contains("italic off", italicOff, "font-style: normal !important")
    checkTrue(
        "italic follow emits nothing",
        !NovelTypographyCss.stylesheet(NovelTypography(italicText = null)).contains("font-style"),
    )

    val shadowOn = NovelTypographyCss.stylesheet(NovelTypography(textShadow = true))
    contains("shadow on", shadowOn, "text-shadow: 0.04em 0.04em 0.07em !important")
    val shadowOff = NovelTypographyCss.stylesheet(NovelTypography(textShadow = false))
    contains("shadow off", shadowOff, "text-shadow: none !important")

    // Indent length: the emitted depth is the reader's own, not the old fixed 1em.
    val indent2 = NovelTypographyCss.stylesheet(
        NovelTypography(paragraphIndent = true, paragraphIndentLengthEm = 2f),
    )
    contains("indent depth", indent2, "text-indent: 2em !important")
    // Off still emits 0 — "off" must beat a publisher who indents.
    contains(
        "indent off emits zero",
        NovelTypographyCss.stylesheet(NovelTypography(paragraphIndent = false)),
        "text-indent: 0 !important",
    )
    // Sanitised: an insane indent depth clamps, it does not reach the stylesheet.
    check(
        "indent sanitised",
        NovelTypography(paragraphIndentLengthEm = 99f).sanitized().paragraphIndentLengthEm,
        NovelTypography.MAX_INDENT_LENGTH_EM,
    )

    // ---------------- NovelAutoScroll: the command grammar ----------------
    checkTrue(
        "start installs and passes speed",
        NovelAutoScroll.startScript(90f).contains(".setSpeed(90.0)") &&
            NovelAutoScroll.startScript(90f).contains(".start(90.0)"),
    )
    checkTrue(
        "stop is engine-guarded",
        NovelAutoScroll.STOP_SCRIPT.contains("window." + NovelAutoScroll.BRIDGE_NAME),
    )
    checkTrue(
        "setSpeed present",
        NovelAutoScroll.setSpeedScript(120f).contains(".setSpeed(120.0)"),
    )
    checkTrue(
        "engine self-stops at the end",
        NovelAutoScroll.ENGINE_SCRIPT.contains("atEnd()"),
    )

    // ---------------- Read-aloud chunking ----------------
    check(
        "speech: sentence cuts",
        NovelPlainText.chunkForSpeech("One. Two. Three.", 10),
        listOf("One. Two.", "Three."),
    )
    checkTrue(
        "speech: chunks under budget",
        NovelPlainText.chunkForSpeech(
            "A sentence here. Another follows it. And a third one arrives.",
            24,
        ).all { it.length <= 24 },
    )
    check(
        "speech: empty input",
        NovelPlainText.chunkForSpeech("   \n "),
        emptyList<String>(),
    )
    checkTrue(
        "speech: oversized sentence hard-split",
        NovelPlainText.chunkForSpeech("abcdefghij", 4).all { it.length <= 4 },
    )
    checkTrue(
        "speech: CJK sentence ends honoured",
        NovelPlainText.chunkForSpeech("第一句。第二句。", 4) == listOf("第一句。", "第二句。"),
    )

    // ---------------- Highlight anchors: encode/decode round trip ----------------
    val highlights = listOf(
        NovelHighlights.Highlight(
            id = "h1",
            color = 2,
            quote = "the \"quoted\" text",
            prefix = "before it",
            suffix = "after it",
            createdAt = 1_700_000_000_000,
        ),
        NovelHighlights.Highlight("h2", 0, "plain", "", "", 5),
    )
    check("highlights: round trip", NovelHighlights.decode(NovelHighlights.encode(highlights)), highlights)
    checkTrue(
        "highlights: quote escaping survives",
        NovelHighlights.decode(NovelHighlights.encode(highlights))[0].quote == "the \"quoted\" text",
    )
    check(
        "highlights: empty store",
        NovelHighlights.decode(""),
        emptyList<NovelHighlights.Highlight>(),
    )
    check(
        "highlights: garbage store",
        NovelHighlights.decode("not json at all"),
        emptyList<NovelHighlights.Highlight>(),
    )
    checkTrue(
        "highlights: colour clamped",
        NovelHighlights.decode("""[{"id":"x","color":9,"quote":"q","prefix":"","suffix":"","created":1}]""")
            .first().color == NovelHighlights.COLORS.lastIndex,
    )

    // ---------------- The JSON-string decoder the TTS text collector reads ----------------
    check("bridge.string: plain", NovelScrollBridge.string("\"hello\""), "hello")
    check("bridge.string: escapes", NovelScrollBridge.string("\"a\\nb\\tc\""), "a\nb\tc")
    check("bridge.string: unicode escape", NovelScrollBridge.string("\"\\u0041\""), "A")
    check("bridge.string: null", NovelScrollBridge.string("null"), null)
    check("bridge.string: not a string", NovelScrollBridge.string("12"), null)
    check("bridge.string: quoted newline round trip", NovelScrollBridge.string("\"line\\nbreak\""), "line\nbreak")

    // The selection report the page hands back, id present and absent.
    val selJson = """{"quote":"a quote","prefix":"pre","suffix":"suf","id":null}"""
    checkTrue(
        "selection: decode without id",
        NovelHighlights.Selection.decode(selJson)?.existingId == null &&
            NovelHighlights.Selection.decode(selJson)?.quote == "a quote",
    )
    checkTrue(
        "selection: decode with id",
        NovelHighlights.Selection.decode("""{"quote":"q","prefix":"","suffix":"","id":"h1"}""")
            ?.existingId == "h1",
    )

    if (failures > 0) {
        println("FAILED: $failures")
        kotlin.system.exitProcess(1)
    }
    println("OK")
}
