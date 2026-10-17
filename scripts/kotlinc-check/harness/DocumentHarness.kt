// Assertions for NovelDocument (the markup handed to the WebView) and NovelScrollBridge (the JS
// contract with the loaded document). Both are deliberately Android-free, so they link directly.
// Run via scripts/kotlinc-check/run-harness.sh document
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelDocument
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelScrollBridge
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubDoctype
import mihon.core.archive.EpubMimeSniff
import mihon.core.archive.MAX_SEARCH_HITS
import mihon.core.archive.indexesOf
import mihon.core.archive.plainTextOf
import mihon.core.archive.stripImages
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

/** The document sits in `OEBPS/Text`, so `../style/main.css` has to resolve to `OEBPS/style/main.css`. */
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

    // ---------------- markup transforms ----------------
// Pure heuristics over a book's own markup, and the part most likely to be quietly wrong: each is a
// guess with no other way to tell it guessed badly.
    check("text keeps its words", plainTextOf("<p>hello <b>there</b></p>"), " hello there ")
    absent("script contents are not prose", plainTextOf("<p>a</p><script>var x = 1;</script>"), "var x")
    absent("style contents are not prose", plainTextOf("<style>body{color:red}</style><p>a</p>"), "color:red")
    absent("head contents are not prose", plainTextOf("<head><title>t</title></head><p>a</p>"), "<title>")
    check("whitespace collapsed", plainTextOf("<p>a</p>\n\n<p>b</p>"), " a b ")

    // Images are removed, not broken: a broken image still occupies a line and leaves a gap.
    absent("img removed", stripImages("""<p>a<img src="x.png"/>b</p>"""), "img")
    check("text either side survives", stripImages("""<p>a<img src="x.png"/>b</p>"""), "<p>ab</p>")
    absent("uppercase IMG removed", stripImages("""<p><IMG SRC="x.png"/></p>"""), "IMG")
    absent("attributes removed with it", stripImages("""<img alt="caption" src="a.png"/>"""), "caption")
    absent("empty figure removed", stripImages("<figure><img src='a.png'/></figure>"), "figure")
    absent("empty picture removed", stripImages("<picture><img src='a.png'/></picture>"), "picture")
    absent("css image url removed", stripImages("""<div style="background:url(a.png)">x</div>"""), "a.png")
    check("a figure with a caption is left alone", stripImages("<figure><p>caption</p></figure>"), "<figure><p>caption</p></figure>")

    // Offsets travel in stripped units, so the strip and the offset have to agree.
    val plain = plainTextOf("<p>The quick brown fox</p><img src='x.png'/><p>jumps over</p>")
    val hit = plain.indexesOf("brown", plain.length).single()
    check("offset points at the match in the stripped text", plain.substring(hit.documentOffset, hit.documentOffset + 5), "brown")
    check("length is the stripped length", hit.documentLength, plain.length)
    check("case-insensitive", plain.indexesOf("BROWN", plain.length).size, 1)
    check("no match is empty, not an error", plain.indexesOf("zebra", plain.length).size, 0)
    check("empty text searches to nothing", "".indexesOf("a", 0).size, 0)
    check("needle longer than the text", "ab".indexesOf("abcdef", 2).size, 0)
    check("hit count is capped", "x".repeat(MAX_SEARCH_HITS + 50).indexesOf("x", MAX_SEARCH_HITS + 50).size, MAX_SEARCH_HITS)
    checkTrue("offset is inside the snippet", hit.matchOffset >= 0 && hit.matchOffset < hit.snippet.length)

// ---------------- identifying a resource from its bytes ----------------
    // The extension table answers for a book that named its files properly. This is for the ones that
    // did not, and it exists because the fallback the table's own comment promised ("the host falls
    // back to sniffing") never existed: an unknown extension became application/octet-stream, which a
    // renderer declines to decode, so the plate silently never arrived.
    fun sniff(vararg bytes: Int) = EpubMimeSniff.of(ByteArray(bytes.size) { bytes[it].toByte() })

    check("png", sniff(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0), "image/png")
    check("jpeg", sniff(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0), "image/jpeg")
    check("gif", sniff(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0), "image/gif")
    check("bmp", sniff(0x42, 0x4D, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0), "image/bmp")
    check("avif", sniff(0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66, 0, 0, 0, 0), "image/avif")
    check(
        "webp, whose type sits past a variable-length field",
        sniff(0x52, 0x49, 0x46, 0x46, 0x1A, 0, 0, 0, 0x57, 0x45, 0x42, 0x50),
        "image/webp",
    )
    check(
        "webp with a different chunk length is still webp",
        sniff(0x52, 0x49, 0x46, 0x46, 0xFF.toByte().toInt(), 0xFF.toByte().toInt(), 0, 0, 0x57, 0x45, 0x42, 0x50),
        "image/webp",
    )
    // A RIFF that is not WebP — a WAV, which is the other thing a book might carry — must not be
    // served as an image. The wildcard skips the length, never the brand.
    check(
        "a riff that is not webp is not an image",
        sniff(0x52, 0x49, 0x46, 0x46, 0x1A, 0, 0, 0, 0x57, 0x41, 0x56, 0x45),
        null,
    )

    // Two containers that agree everywhere except one brand byte, and one that is a bare two-byte
    // prefix. Ordered longest-first, so none of them reads as another.
    check(
        "jp2 container",
        sniff(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A),
        "image/jp2",
    )
    check(
        "jxl container is not jp2",
        sniff(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A),
        "image/jxl",
    )
    check("jxl naked codestream", sniff(0xFF, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "image/jxl")

    // A font served as an octet stream is a book that silently loses its publisher's typography,
    // which reads as "this reader ignores the book's styling" rather than as one missing file.
    check("woff2", sniff(0x77, 0x4F, 0x46, 0x32, 0, 0, 0, 0, 0, 0, 0, 0), "font/woff2")
    check("opentype", sniff(0x4F, 0x54, 0x54, 0x4F, 0, 0, 0, 0, 0, 0, 0, 0), "font/otf")

    // A stylesheet that is not recognised must answer null rather than a guess: a caller that gets
    // null falls back to its own last resort, and one that gets a wrong type never gets to.
    check("xml is not a guessable type", sniff(0x3C, 0x3F, 0x78, 0x6D, 0x6C, 0, 0, 0, 0, 0, 0, 0), null)
    check("random bytes", sniff(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C), null)
    check("too short to tell", sniff(0xFF, 0xD8), null)
    check("empty", EpubMimeSniff.of(ByteArray(0)), null)

    // The stream form has to leave the stream where it found it. Handing the renderer a stream already
    // advanced by a sniff is a resource that decodes as garbage — indistinguishable from the bug this
    // exists to fix.
    val peekable = ByteArrayInputStream(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9, 9, 9))
    check("stream sniff", EpubMimeSniff.ofStream(peekable), "image/png")
    check(
        "stream is rewound",
        peekable.readBytes().take(4).toByteArray().toList(),
        listOf<Byte>(0x89.toByte(), 0x50, 0x4E, 0x47),
    )
    // A stream that does not support mark is the normal case for an archive entry, so the buffering
    // path has to produce the same answer and leave the same bytes.
    val unmarkable = object : java.io.InputStream() {
        private val delegate = ByteArrayInputStream(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7, 7, 7, 7, 7, 7, 7, 7, 7),
        )
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    }
    check("stream sniff without mark support", EpubMimeSniff.ofStream(unmarkable), "image/jpeg")

// ---------------- the shared archive URL space ----------------
    // Two libraries serve books now and both resolve references through this, so a disagreement
    // between them is not detectable by reading either one. It has to be asserted.
    check("root document base url", EpubArchiveUrls.documentBaseUrl(null), EpubArchiveUrls.BASE_URL)
    check("blank href falls back to root", EpubArchiveUrls.documentBaseUrl("   "), EpubArchiveUrls.BASE_URL)
    check("fragment stripped", EpubArchiveUrls.documentBaseUrl("OEBPS/a.xhtml#top"), "epub-archive://book.internal/OEBPS/")
    check("query stripped", EpubArchiveUrls.documentBaseUrl("OEBPS/a.xhtml?x=1"), "epub-archive://book.internal/OEBPS/")

    // The single most consequential line in the EPUB path: a spine item must load at the directory
    // that *contains* it, or every relative reference in the book 404s and it renders as bare text.
    // The file name is not part of it — a document at `OEBPS/chapter1.xhtml` sits in `OEBPS/`, so
    // `../style/main.css` resolves to `style/main.css` at the root, which is what the publisher meant
    // by writing it. Appending the file name would resolve that one reference up into the book.
    check(
        "document base url is the document's directory",
        EpubArchiveUrls.documentBaseUrl("OEBPS/chapter1.xhtml"),
        "epub-archive://book.internal/OEBPS/",
    )
    check(
        "a document nested deeper keeps every level",
        EpubArchiveUrls.documentBaseUrl("OEBPS/Text/chapter1.xhtml"),
        "epub-archive://book.internal/OEBPS/Text/",
    )
    check(
        "a document at the root needs no directory",
        EpubArchiveUrls.documentBaseUrl("chapter1.xhtml"),
        EpubArchiveUrls.BASE_URL,
    )
    check(
        "a trailing slash is not treated as the file",
        EpubArchiveUrls.documentBaseUrl("OEBPS/chapter1/"),
        "epub-archive://book.internal/OEBPS/",
    )

    // A `..` in a producer's href must not walk the base URL off the reserved host. Note what the
    // invariant actually is: traversal clamps *within* the host, it does not reset to the root. What
    // matters is that the host is unchanged and the path is normalised, because a base URL pointing
    // somewhere the renderer will not be served leaves every relative reference unresolvable.
    val escaped = EpubArchiveUrls.documentBaseUrl("OEBPS/../../etc/passwd")
    checkTrue("traversal stays on the reserved host", escaped.startsWith(EpubArchiveUrls.BASE_URL))
    check("traversal is normalised, not preserved", escaped, "epub-archive://book.internal/etc/")

    val climbedToRoot = EpubArchiveUrls.documentBaseUrl("a/b/../../../x.xhtml")
    checkTrue("climbing past the root clamps", climbedToRoot.startsWith(EpubArchiveUrls.BASE_URL))

    checkTrue("archive url recognised", EpubArchiveUrls.isArchiveUrl("epub-archive://book.internal/OEBPS/a.png"))
    checkTrue("external url refused", EpubArchiveUrls.isArchiveUrl("https://example.com/a.png").not())

    check("archive path extracted", EpubArchiveUrls.archivePathOf("epub-archive://book.internal/OEBPS/a.png"), "OEBPS/a.png")
    check("archive path percent-decoded", EpubArchiveUrls.archivePathOf("epub-archive://book.internal/OEBPS/My%20a.png"), "OEBPS/My a.png")
    check("fragment dropped", EpubArchiveUrls.archivePathOf("epub-archive://book.internal/OEBPS/a.xhtml#s1"), "OEBPS/a.xhtml")
    check("traversal normalised", EpubArchiveUrls.archivePathOf("epub-archive://book.internal/OEBPS/../a.png"), "a.png")
    check("external url has no archive path", EpubArchiveUrls.archivePathOf("https://example.com/a.png"), null)
    check("garbage has no archive path", EpubArchiveUrls.archivePathOf("epub-archive://book.internal/"), null)

    // Content types are load-bearing for the renderer, not conventional: a stylesheet served as
    // text/plain is ignored and the book loses its own appearance with no error anywhere.
    check("xhtml is html to the engine", EpubArchiveUrls.contentTypeForPath("OEBPS/a.xhtml"), "text/html")
    check("svg keeps its own type", EpubArchiveUrls.contentTypeForPath("a.svg"), "image/svg+xml")
    check("css", EpubArchiveUrls.contentTypeForPath("main.css"), "text/css")
    check("woff2", EpubArchiveUrls.contentTypeForPath("f.woff2"), "font/woff2")

    // The extensions a real archive uses that the original table had no row for. Each of these fell
    // through to a null type, and a null type became application/octet-stream, which a renderer will
    // not decode — so each was an illustration that never appeared.
    check("avif", EpubArchiveUrls.contentTypeForPath("plate.avif"), "image/avif")
    check("jfif", EpubArchiveUrls.contentTypeForPath("plate.jfif"), "image/jpeg")
    check("apng", EpubArchiveUrls.contentTypeForPath("plate.apng"), "image/png")
    check("tiff", EpubArchiveUrls.contentTypeForPath("plate.tif"), "image/tiff")
    check("jpeg 2000", EpubArchiveUrls.contentTypeForPath("plate.jp2"), "image/jp2")
    check("jxl", EpubArchiveUrls.contentTypeForPath("plate.jxl"), "image/jxl")

    // A query string is not part of the extension, and the old fallback handed this function the
    // whole URL rather than the archive path — so `plate.jpg?v=2` stopped being a JPEG.
    check("query string is not part of the extension", EpubArchiveUrls.contentTypeForPath("a.jpg?v=2"), "image/jpeg")
    check("unknown is null not a guess", EpubArchiveUrls.contentTypeForPath("a.bin"), null)
    check("no extension is null", EpubArchiveUrls.contentTypeForPath("plate"), null)

    println(if (failures == 0) "\nALL PASS" else "\n$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}
