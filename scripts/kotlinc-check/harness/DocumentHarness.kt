// Assertions for NovelDocument (the markup handed to the WebView) and NovelScrollBridge (the JS
// contract with the loaded document). Both are deliberately Android-free, so they link directly.
// Run via scripts/kotlinc-check/run-harness.sh document
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelDocument
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelScrollBridge
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import mihon.core.archive.EpubDoctype
import java.io.ByteArrayInputStream

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    val ok = actual == expected
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name: expected=$expected actual=$actual")
}

private fun contains(name: String, haystack: String, needle: String) = check(name, haystack.contains(needle), true)

private fun absent(name: String, haystack: String, needle: String) = check(name, haystack.contains(needle), false)

private fun checkTrue(name: String, actual: Boolean) = check(name, actual, true)

/** Runs [EpubDoctype.strip] over a literal document. */
private fun stripped(doc: String): String =
    EpubDoctype.strip(ByteArrayInputStream(doc.toByteArray())).readBytes().decodeToString()

/** A spine item as a real EPUB producer emits it: XML prolog, XHTML doctype, no charset, relative refs. */
private val REAL_SPINE_ITEM = """
    <?xml version="1.0" encoding="utf-8"?>
    <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
    <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja" lang="ja">
    <head>
    <title>第一章</title>
    <link rel="stylesheet" type="text/css" href="../style/main.css" />
    </head>
    <body class="p">
    <p>これは最初の段落です。</p>
    <p><img src="../images/plate.jpg" alt="" /></p>
    </body>
    </html>
""".trimIndent()

private const val BASE = "epub-archive://book.internal/OEBPS/Text/"

fun main() {
    val t = NovelTypography.DEFAULT
    val built = NovelDocument.build(REAL_SPINE_ITEM, t, BASE)

    // ---------------- the prolog and the doctype ----------------
    absent("xml prolog dropped", built, "<?xml")
    contains("doctype normalised", built, "<!DOCTYPE html>")
    absent("remote dtd dropped", built, "xhtml11.dtd")

    // ---------------- charset ----------------
    // The prolog carried the encoding and it is gone, so an explicit meta is the only thing left that
    // can stop a Shift_JIS book rendering as mojibake.
    contains("charset declared", built, "charset=utf-8")
    checkTrue(
        "charset before the publisher's stylesheet link",
        built.indexOf("charset=utf-8") < built.indexOf("main.css"),
    )

    // ---------------- base url ----------------
    // This is the one that matters: the document sits in OEBPS/Text, so `../style/main.css` has to
    // resolve to OEBPS/style/main.css and not to style/main.css at the archive root.
    contains("base url emitted", built, """<base href="$BASE">""")
    checkTrue("base url ends with a slash", built.contains("""<base href="$BASE">"""))
    checkTrue("only one base tag", built.split("<base").size - 1 == 1)

    // A publisher's own <base> would outrank ours because the first one in the document wins.
    val withBase = NovelDocument.build(
        """<html><head><base href="http://example.com/"></head><body>x</body></html>""",
        t,
        BASE,
    )
    absent("publisher base removed", withBase, "example.com")
    checkTrue("our base survives", withBase.contains("""<base href="$BASE">"""))

    // ---------------- typography + bridge land in the head ----------------
    contains("typography injected", built, "kmk-typography")
    // The bridge has to be a script *element*. Bare, its source is the first thing on the page, printed
    // in the reader's own font - a very loud way to render nothing.
    contains("bridge inside a script element", built, "<script>")
    val bridgeAt = built.indexOf("window.KmkReader")
    val scriptAt = built.lastIndexOf("<script>", bridgeAt)
    checkTrue("bridge is inside the script element", scriptAt in 0 until bridgeAt)
    checkTrue(
        "bridge closed",
        built.indexOf("</script>", bridgeAt) > bridgeAt,
    )
    checkTrue(
        "typography inside the head",
        built.indexOf("kmk-typography") > built.indexOf("<head>") &&
            built.indexOf("kmk-typography") < built.indexOf("</head>"),
    )
    // The reader's rules are !important because a publisher rule on body outranks a host rule on
    // specificity. !important against !important is decided by source order, so ours has to be last -
    // a publisher that also uses it has to come second.
    checkTrue(
        "typography after the publisher stylesheet link",
        built.indexOf("kmk-typography") > built.indexOf("main.css"),
    )
    // The base is the opposite case: it has to precede every element with a relative URL, and the
    // publisher's own <link> is one.
    checkTrue(
        "base before the publisher stylesheet link",
        built.indexOf("<base href") < built.indexOf("main.css"),
    )

    // ---------------- the book's own content survives ----------------
    contains("body kept", built, "これは最初の段落です。")
    contains("relative image kept", built, """src="../images/plate.jpg"""")
    contains("publisher stylesheet kept", built, """href="../style/main.css"""")

    // ---------------- malformed input ----------------
    // A document that opens <head> and never closes it is common in generated EPUBs; the injection
    // must not depend on finding </head>.
    val unclosedHead = NovelDocument.build("<html><head><title>t</title><body><p>hi</p>", t, BASE)
    contains("unclosed head still injected", unclosedHead, "kmk-typography")
    contains("unclosed head keeps body", unclosedHead, "<p>hi</p>")

    // No <head> at all: one has to be created, or there is nowhere to put the base and the charset.
    val headless = NovelDocument.build("<html><body><p>hi</p></body></html>", t, BASE)
    contains("headless gets a head", headless, "<head>")
    checkTrue("headless gets the base", headless.contains("""<base href="$BASE">"""))

    // A bare fragment, which is what a web-novel source returns.
    val fragment = NovelDocument.build("<p>just a paragraph</p>", t, BASE)
    checkTrue("fragment wrapped", fragment.startsWith("<!DOCTYPE html>"))
    checkTrue("fragment gets the base", fragment.contains("""<base href="$BASE">"""))
    contains("fragment keeps content", fragment, "<p>just a paragraph</p>")

    // An empty document must still produce something loadable rather than an empty string.
    val empty = NovelDocument.build("", t, BASE)
    checkTrue("empty still has a doctype", empty.contains("<!DOCTYPE html>"))
    checkTrue("empty still has the base", empty.contains("""<base href="$BASE">"""))

    // The base url is escaped, so a directory name with a quote cannot break out of the attribute.
    val quoted = NovelDocument.build("<p>x</p>", t, """epub-archive://book.internal/OEBPS/a"b/""")
    check("base attribute escaped", quoted.contains("""<base href="epub-archive://book.internal/OEBPS/a&quot;b/">"""), true)

    // ---------------- redirects ----------------
    // A <meta http-equiv="refresh"> is honoured by the engine with no involvement from the host, so a
    // book carrying one replaces the document the reader is part-way through, untapped, with a URL of
    // the book's choosing. It is not a rendering concern and has no legitimate use in an EPUB.
    val refreshed = NovelDocument.build(
        """<html><head><meta http-equiv="refresh" content="0;url=http://example.com/"></head><body><p>hi</p></body></html>""",
        t,
        BASE,
    )
    // Counted rather than matched: the prologue's own charset declaration is itself a
    // <meta http-equiv>, so "no http-equiv anywhere" would fail against a correct document. One left
    // is ours; a second would be the publisher's.
    check("only our http-equiv survives", refreshed.split("http-equiv").size - 1, 1)
    absent("refresh target removed", refreshed, "example.com")
    contains("refresh body kept", refreshed, "<p>hi</p>")

    // Quoted or unquoted, uppercase or not: all four spellings the parser accepts.
    for (variant in listOf(
        """<META HTTP-EQUIV=refresh CONTENT="0;url=x">""",
        """<meta http-equiv='REFRESH' content='0;url=x'>""",
        """<meta content="0;url=x" http-equiv="refresh">""",
    )) {
        val out = NovelDocument.build("<html><head>$variant</head><body>x</body></html>", t, BASE)
        absent("meta refresh removed: $variant", out, "url=x")
    }

    // Only refresh goes. A <meta charset> is how the document declares its encoding, and ours is
    // injected at the top of the head - removing the publisher's as well would leave a Shift_JIS book
    // with the prolog gone and nothing to say how it is encoded.
    val withCharset = NovelDocument.build(
        """<html><head><meta charset="Shift_JIS"></head><body>x</body></html>""",
        t,
        BASE,
    )
    contains("publisher charset kept", withCharset, "Shift_JIS")

    // ---------------- the scroll bridge ----------------
    val script = NovelScrollBridge.BRIDGE_SCRIPT
    checkTrue("bridge guards against double install", script.contains("if (window.KmkReader) return"))
    checkTrue("bridge exposes fraction", script.contains("fraction"))
    checkTrue("bridge reports real movement", script.contains("return self.top() - before"))

    // evaluateJavascript hands back the JSON encoding, so a script returning NaN arrives as "null".
    // Reading that as anything but "no movement" turns the document on every tap at a page edge.
    check("parses a number", NovelScrollBridge.number("42.5"), 42.5)
    check("parses zero", NovelScrollBridge.number("0"), 0.0)
    check("null means no movement", NovelScrollBridge.number("null"), 0.0)
    check("undefined means no movement", NovelScrollBridge.number("undefined"), 0.0)
    check("empty means no movement", NovelScrollBridge.number(""), 0.0)
    check("garbage means no movement", NovelScrollBridge.number("{\"a\":1}"), 0.0)
    check("absent means no movement", NovelScrollBridge.number(null), 0.0)

    // The restore snippet carries a substitution, so a broken template would silently do nothing.
    checkTrue("set fraction is templated", NovelScrollBridge.SET_FRACTION.contains("%f"))
    check(
        "set fraction substitutes cleanly",
        NovelScrollBridge.SET_FRACTION.replace("%f", "0.25").contains("0.25"),
        true,
    )

    // ---------------- quoting the contents entry ----------------
    // A contents fragment id comes out of the book's own table of contents, so it is untrusted input
    // that ends up inside a script. These are the cases that turn an entry into script execution.
    val anchor = NovelScrollBridge.scrollToAnchor("id", 32)
    checkTrue("anchor script substitutes the id", anchor.contains("\"id\""))
    checkTrue("anchor script substitutes the inset", anchor.endsWith("(\"id\",32)"))

    val injected = NovelScrollBridge.scrollToAnchor("\"); alert(1); //", 0)
    checkTrue("quotes escaped", !injected.contains(""""); alert(1); //"""))

    checkTrue("closing tag escaped", NovelScrollBridge.quoteForJs("</script>").contains("""<\/script>"""))
    checkTrue(
        "script break-out escaped",
        !NovelScrollBridge.quoteForJs("</script><img onerror=x>").contains("</script"),
    )

    // U+2028/U+2029 are line terminators to a JS parser but ordinary characters to Kotlin, so a raw
    // one ends the script early in the browser and carries on as source.
    checkTrue("line separator escaped", NovelScrollBridge.quoteForJs("a\u2028b").contains("""\u2028"""))
    checkTrue("paragraph separator escaped", NovelScrollBridge.quoteForJs("a\u2029b").contains("""\u2029"""))
    checkTrue("backslash escaped", NovelScrollBridge.quoteForJs("""a\b""") == """"a\\b"""")
    checkTrue("quote escaped", NovelScrollBridge.quoteForJs("a\"b") == """"a\"b"""")
    checkTrue("control char escaped", NovelScrollBridge.quoteForJs("a\u0001b").contains("""\u0001"""))
    checkTrue("plain text untouched", NovelScrollBridge.quoteForJs("chapter1") == "\"chapter1\"")

    // A fragment id is substituted into a template that also carries a `%d`. Sequential literal
    // replaces mean the second one scans the string the first already produced, so substituting `%s`
    // first lets it rewrite the untrusted id itself: `a%db` came out as `a32b`, the element lookup
    // missed, and the reader landed at the top of the document with nothing to explain it.
    checkTrue("percent-d in a fragment survives", NovelScrollBridge.scrollToAnchor("a%db", 32).contains("\"a%db\""))
    checkTrue("percent-s in a fragment survives", NovelScrollBridge.scrollToAnchor("a%sb", 32).contains("\"a%sb\""))
    checkTrue("percent-d alone survives", NovelScrollBridge.scrollToAnchor("%d", 0).contains("\"%d\""))
    checkTrue("inset still substituted after an id with percent-d", NovelScrollBridge.scrollToAnchor("a%db", 32).endsWith("(\"a%db\",32)"))
    checkTrue(
        "percent-d in an id does not eat the inset",
        NovelScrollBridge.scrollToAnchor("100%d", 8).endsWith("(\"100%d\",8)"),
    )

    // ---------------- XXE: the doctype never reaches a parser ----------------
    // The defence is on the input rather than on the parser's class name, precisely so that it can be
    // checked here. An assertion about which implementation we got could not fail usefully; the
    // absence of a SYSTEM identifier in these bytes is the property that actually matters.
    val xxe = "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"
    absent("external entity declaration stripped", stripped(xxe), "SYSTEM")
    absent("internal subset stripped", stripped(xxe), "<!ENTITY")
    absent("doctype keyword gone", stripped(xxe), "DOCTYPE")

    val xhtmlDoctype =
        "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">" +
            "<html><body>ok</body></html>"
    absent("dtd-fetching doctype stripped", stripped(xhtmlDoctype), "xhtml11.dtd")
    absent("no doctype survives an XHTML document", stripped(xhtmlDoctype), "DOCTYPE")
    contains("document body survives doctype stripping", stripped(xhtmlDoctype), "<html><body>ok</body></html>")

    // A real structural document: the doctype is removed, the root element and its namespace
    // declaration — the parts the whole parser exists to read — are untouched.
    val opf = "<!DOCTYPE package SYSTEM \"oasis.dtd\">\n" +
        "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata/></package>"
    absent("opf doctype stripped", stripped(opf), "oasis.dtd")
    contains("opf root kept", stripped(opf), "xmlns=\"http://www.idpf.org/2007/opf\"")
    contains("opf metadata kept", stripped(opf), "<metadata/>")

    // Nothing to strip is a pass-through, and the leading bytes must survive byte for byte.
    check("document without a doctype is unchanged", stripped("<package><metadata/></package>"), "<package><metadata/></package>")
    check("empty input is unchanged", stripped(""), "")

    // A doctype behind a prolog, which is where a producer that emits both actually puts it.
    val behindProlog = "<?xml version=\"1.0\"?>\n<!DOCTYPE html SYSTEM \"http://evil/x.dtd\">\n<html/>"
    absent("doctype after a prolog is stripped", stripped(behindProlog), "evil")
    contains("prolog survives", stripped(behindProlog), "<?xml version=\"1.0\"?>")
    contains("root survives", stripped(behindProlog), "<html/>")

    // Unterminated: there is no complete SYSTEM identifier to act on, so passing it through is safe —
    // and the parser's own DOCTYPE feature rejects the document, which is the right outcome for one
    // that is malformed anyway.
    contains("unterminated doctype is left for the parser to reject", stripped("<!DOCTYPE html SYSTEM \"http://e/"), "http://e/")

    // A `>` inside a quoted public identifier must not end the match early, or a doctype would be
    // half-removed and the rest left to the parser.
    val tricky = "<!DOCTYPE r PUBLIC \"a>b\" \"http://evil/x.dtd\"><r/>"
    absent("quoted > does not end the match early", stripped(tricky), "evil")

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}