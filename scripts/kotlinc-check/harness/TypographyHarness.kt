// Assertions for NovelTypography CSS generation and NovelProgress. Pure JVM by design:
// neither file imports Android, so both link directly. Run via run-harness.sh or kotlinc-check.
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelProgress
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypographyCss

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
}

private fun checkTrue(name: String, actual: Boolean) = check(name, actual, true)

/** Asserts haystack contains needle. Negative cases use check(.., .., false) instead. */
private fun contains(name: String, haystack: String, needle: String) {
    check(name, haystack.contains(needle), true)
}

private fun containsTrue(name: String, haystack: String, needle: String) = contains(name, haystack, needle)

fun main() {
    // ---------------- typography defaults & sanitizing ----------------
    val d = NovelTypography.DEFAULT
    check("default not customised", d.isCustomised, false)
    checkTrue("default still emits css", NovelTypographyCss.stylesheet(d).isNotEmpty())

    val t = NovelTypography(fontSizePt = 20, fontColor = "#e0e0e0", backgroundColor = "#101010")
    checkTrue("customised when changed", t.isCustomised)

    // ---------------- font size + scale ----------------
    check("effective size", NovelTypography(fontSizePt = 20, fontScale = 1.5f).effectiveFontSizePt, 30f)
    check("effective size clamps low", NovelTypography(fontSizePt = 10, fontScale = 0.5f).effectiveFontSizePt, 8f)
    check("effective size clamps high", NovelTypography(fontSizePt = 40, fontScale = 3f).effectiveFontSizePt, 72f)

    // ---------------- sanitize ----------------
    val s = NovelTypography(
        fontSizePt = 999,
        lineSpacing = 99f,
        fontSpacing = 99f,
        fontScale = 99f,
        marginLeft = 0.99f,
        paragraphSpacing = -5f,
    ).sanitized()
    check("sanitize fontSize", s.fontSizePt, 40)
    check("sanitize lineSpacing", s.lineSpacing, 3.0f)
    check("sanitize fontSpacing", s.fontSpacing, 1.0f)
    check("sanitize fontScale", s.fontScale, 3.0f)
    check("sanitize marginLeft", s.marginLeft, 0.45f)
    check("sanitize paragraphSpacing", s.paragraphSpacing, 0f)

    // ---------------- CSS: each requested preference ----------------
    val full = NovelTypography(
        fontSizePt = 24,
        fontColor = "#ffcc00",
        backgroundColor = "#000000",
        fontFamily = "Georgia",
        smoothText = true,
        justified = true,
        hyphenation = true,
        paragraphSpacing = 1.2f,
        lineSpacing = 2.0f,
        fontSpacing = 0.05f,
        fontScale = 1.25f,
        marginLeft = 0.1f,
        marginRight = 0.12f,
        marginTop = 0.08f,
        marginBottom = 0.2f,
    )
    val css = NovelTypographyCss.stylesheet(full)

    contains("font size = base x scale", css, "30pt")   // fontSizePt 24 * fontScale 1.25
    contains("font colour", css, "#ffcc00")
    contains("background", css, "#000000")
    contains("font family", css, "Georgia")
    contains("smooth", css, "-webkit-font-smoothing: antialiased")
    contains("justified", css, "text-align: justify")
    contains("hyphenation", css, "hyphens: auto")
    contains("paragraph spacing", css, "1.2em")
    contains("line spacing", css, "--kmk-line: 2")
    contains("font spacing", css, "0.05em")
    contains("margins order T R B L", css, "8% 12% 20% 10%")

    val unjust = NovelTypographyCss.stylesheet(NovelTypography(justified = false))  // explicit "off"
    contains("not justified", unjust, "text-align: left")
    val nohyph = NovelTypographyCss.stylesheet(NovelTypography(hyphenation = false))
    contains("hyphenation off", nohyph, "hyphens: manual")
    val unsmooth = NovelTypographyCss.stylesheet(NovelTypography(smoothText = false))
    contains("subpixel when off", unsmooth, "subpixel-antialiased")
    val vertical = NovelTypographyCss.stylesheet(NovelTypography(verticalWriting = true))
    contains("vertical writing", vertical, "writing-mode: vertical-rl")
    val horiz = NovelTypographyCss.stylesheet(NovelTypography(verticalWriting = false))
    contains("horizontal explicit", horiz, "writing-mode: horizontal-tb")
    // null must NOT emit the rule, so a vertical book keeps its own writing mode
    val inherited = NovelTypographyCss.stylesheet(NovelTypography())
    check("writing mode omitted when null", inherited.contains("writing-mode"), false)
    check("text-align omitted when null", inherited.contains("text-align"), false)
    check("hyphens omitted when null", inherited.contains("-webkit-hyphens"), false)

    // generic family must not be quoted; a real name with a space must be
    contains("generic unquoted", NovelTypographyCss.stylesheet(NovelTypography(fontFamily = "serif")), "font-family: serif !important")
    contains("spaced name quoted", NovelTypographyCss.stylesheet(NovelTypography(fontFamily = "Noto Serif JP")), "\"Noto Serif JP\"")

    // ---------------- style tag ----------------
    val tag = NovelTypographyCss.styleTag(t)
    containsTrue("styleTag injects", tag, "kmk-typography")
    containsTrue("styleTag carries the rules", tag, "font-family")
    // A font family is free text the user typed. A closing tag in it would end the <style> element in
    // the HTML parser and silently drop every rule after it, base font size included.
    val hostile = NovelTypographyCss.styleTag(NovelTypography(fontFamily = "Evil</style><p>"))
    check("closing tag escaped", hostile.contains("</style><p>"), false)
    checkTrue("escaped form present", hostile.contains("""<\/style>"""))

    // ---------------- percentage progress ----------------
    val lens = listOf(1000, 2000, 1000)   // total 4000
    val p0 = NovelProgress.Position(0, 500, 1000)
    check("chapter pct mid first doc", NovelProgress.chapterPercent(p0, lens), 13)   // 12.5 rounds up
    val p1 = NovelProgress.Position(1, 0, 2000)
    check("chapter pct start of 2nd", NovelProgress.chapterPercent(p1, lens), 25)
    val p1end = NovelProgress.Position(1, 2000, 2000)
    check("chapter pct end of 2nd", NovelProgress.chapterPercent(p1end, lens), 75)
    check("chapter pct last char", NovelProgress.chapterPercent(NovelProgress.Position(2, 1000, 1000), lens), 100)

    // weighting: equal-count would give 33%, length-weighting gives 75% at end of doc 2
    check("weighted not equal-count", NovelProgress.chapterPercent(p1end, lens), 75)

    check("book pct chapter 0 start", NovelProgress.bookPercent(0, lens, NovelProgress.Position(0, 0, 1000)), 0)
    // within-chapter term uses position.characterLength (1000), not chapterLengths[1] (2000):
    // before=1000 + within=500 => 1500/4000 = 38%
    check("book pct chapter 1 mid", NovelProgress.bookPercent(1, lens, NovelProgress.Position(0, 500, 1000)), 38)
    check("book pct last chapter end", NovelProgress.bookPercent(2, lens, NovelProgress.Position(0, 1000, 1000)), 100)

    check("empty lens -> 0", NovelProgress.chapterPercent(p0, emptyList()), 0)
    check("zero length -> 0", NovelProgress.chapterPercent(p0, listOf(0)), 0)
    check("out of range doc clamps", NovelProgress.chapterPercent(NovelProgress.Position(99, 0, 10), lens), 100)

    // offset <-> fraction round trip
    check("offset for 0.5", NovelProgress.characterOffsetFor(0.5f, 1000), 500)
    check("fraction for 500", NovelProgress.fractionFor(500, 1000), 0.5f)
    check("offset past end clamps", NovelProgress.characterOffsetFor(2f, 1000), 1000)
    check("fraction past end clamps", NovelProgress.fractionFor(9999, 1000), 1f)
    check("zero length fraction", NovelProgress.fractionFor(10, 0), 0f)

    // persistence round trip
    val enc = p1.encode()
    check("encode", enc, "1:0:2000")
    check("decode", NovelProgress.Position.decode(enc), p1)
    check("decode garbage", NovelProgress.Position.decode("nope"), null)
    check("decode blank", NovelProgress.Position.decode(""), null)
    check("decode partial", NovelProgress.Position.decode("1:0"), null)
    check("decode negative", NovelProgress.Position.decode("-1:0:10"), null)

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}