package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Turns a book's own XHTML into a document a layout engine can be handed directly.
 *
 * ### Why a builder at all
 *
 * The markup that comes out of an EPUB spine item is *XML*, not something the engine is contractually
 * obliged to accept as HTML, and in practice it is all four of the following at once:
 *
 *  - an `<?xml …?>` prolog, which is a bogus comment to an HTML parser and sits *before* the doctype,
 *    so it changes which doctype the engine sees;
 *  - an XHTML doctype naming a DTD over the network, which is pointless to resolve here, and for the
 *    `XHTML 1.1` flavour pulls the engine into a different layout mode than the one a book wants;
 *  - no `<meta charset>`, because XML takes the encoding from the prolog instead — and the prolog is
 *    exactly what was just discarded, so a Shift_JIS book renders as mojibake;
 *  - arbitrary well-formedness. A publisher who pasted HTML into an XHTML template leaves `&nbsp;`
 *    behind, which is a fatal error in XML and merely ordinary in HTML.
 *
 * Normalising to a plain `<!DOCTYPE html>` document with an explicit charset makes the reader behave
 * the same for every book instead of depending on which tool produced it.
 *
 * ### Why the `<base>` tag is emitted and not only passed to the engine
 *
 * The host also passes the base URL to `loadDataWithBaseURL`, which is what the engine uses. The tag
 * is belt and braces for one specific and very visible failure: if the two ever disagree, the
 * document says which base its own relative references resolve against, so a book renders the way the
 * source intended rather than the way the host guessed. A `<base>` already in the publisher's markup
 * is removed first, because the first one in the document is the one that counts and theirs would
 * silently win.
 *
 * No Android imports on purpose — this is the part of the reader that can be asserted off-device.
 */
object NovelDocument {

    /**
     * Builds the document to load for [source], resolved against [baseUrl].
     *
     * [baseUrl] must be absolute and end in `/`; see
     * [eu.kanade.tachiyomi.source.text.TextBaseUrlSource] for why.
     *
     * The head content is injected at **two** points, and the split is not cosmetic:
     *
     *  - `<base>` and the charset go at the very top of the head. A `<base>` has to precede every
     *    element that uses a relative URL — including the publisher's own `<link>`, which the preload
     *    scanner may well have resolved before the end of the head. And a charset declaration is only
     *    honoured near the top of a document, which is the one thing an XML prolog was doing for us
     *    and is no longer doing anything at all.
     *  - the reader's stylesheet and the scroll bridge go at the very **end**. They are `!important`
     *    for a reason — a publisher rule on `body` outranks a host rule on specificity grounds, and
     *    that is the single most common complaint about EPUB readers — but `!important` against
     *    `!important` is decided by source order, so a publisher that also uses it has to come second.
     */
    fun build(source: String, typography: NovelTypography, baseUrl: String): String {
        val document = normalize(source)
        val prologue = prologue(baseUrl)
        val epilogue = epilogue(typography)

        val headOpen = HEAD_OPEN.find(document)
        if (headOpen != null) {
            val headClose = HEAD_CLOSE.find(document, headOpen.range.last + 1)
            return if (headClose != null) {
                // Closed head: the later offset is patched first, so inserting there cannot shift the
                // earlier one out from under the second edit.
                document
                    .replaceRange(headClose.range, epilogue + headClose.value)
                    .replaceRange(headOpen.range, headOpen.value + prologue)
            } else {
                // A document that opens <head> and never closes it: common in generated EPUBs. Putting
                // everything after the opening tag is the best that can be done, and the rules still
                // precede the body, which is where the text is.
                document.replaceRange(headOpen.range, headOpen.value + prologue + epilogue)
            }
        }

        val htmlOpen = HTML_OPEN.find(document)
        return if (htmlOpen != null) {
            document.replaceRange(htmlOpen.range, "${htmlOpen.value}<head>$prologue$epilogue</head>")
        } else {
            // No <html> at all — a fragment, or a file whose root element is something else. Wrapping is
            // the only way to give the engine the head the base and the charset need.
            "$HTML_SHELL<head>$prologue$epilogue</head>\n$document\n</body>\n</html>"
        }
    }

    /**
     * Drops the XML prolog, reduces the doctype to `<!DOCTYPE html>`, and removes the `<base>` and
     * `<meta http-equiv>` the publisher supplied.
     *
     * The prolog is a bogus comment that hides the doctype from the HTML parser's doctype sniffing,
     * and an XHTML doctype's SYSTEM identifier is a network fetch that cannot succeed inside a
     * document loaded from a string.
     *
     * The two removals are for the same reason, which is that each one silently outranks something
     * the reader depends on. A `<base>` is first-match-wins, so theirs would beat the one we emit —
     * and it is exactly wrong, being written for whatever the producer rendered the book with. A
     * `<meta http-equiv="refresh">` is honoured by the engine with no involvement from us at all, so
     * a book carrying one navigates the reader's document to a URL of the book's choosing: the text
     * they were reading is replaced, without a tap, by something we then decline to serve.
     */
    private fun normalize(source: String): String {
        val withoutProlog = XML_PROLOG.replaceFirst(source, "")
        val withoutDoctype = DOCTYPE.replaceFirst(withoutProlog, "<!DOCTYPE html>")
        // A <base> already in the publisher's markup would silently win over ours, because the first one
        // in the document is the one that counts — and it is exactly the wrong one, since it was written
        // for whatever the producer rendered the book with.
        val withoutBase = BASE_TAG.replace(withoutDoctype, "")
        // Global, not first: a second one is inert while the first survives, but if the first is
        // malformed enough for the engine to skip it, the next in document order takes effect.
        return META_REFRESH.replace(withoutBase, "").trimStart()
    }

    /** Goes at the very top of the head: everything here has to be seen before the publisher's own URLs are. */
    private fun prologue(baseUrl: String): String = buildString {
        appendLine(HEAD_MARKER)
        appendLine("""<base href="${escapeAttribute(baseUrl)}">""")
        // Ours lands before any the publisher declared, and the first charset declaration is the one
        // that wins — so a book that got its encoding wrong is corrected rather than left alone.
        appendLine("""<meta http-equiv="Content-Type" content="text/html; charset=utf-8">""")
    }

    /** Goes at the very end of the head, so it outranks the publisher's rules on source order. */
    private fun epilogue(typography: NovelTypography): String = buildString {
        appendLine(NovelTypographyCss.styleTag(typography))
        // Wrapped, obviously. Script text emitted bare is not script: it is the first thing on the
        // page, printed in the reader's own font, and it is not the last thing anyone notices.
        append("<script>")
        append(NovelScrollBridge.BRIDGE_SCRIPT.replace("</script", """<\/script"""))
        appendLine("</script>")
    }

    /** Neutralises the markup-significant characters in an attribute value. */
    private fun escapeAttribute(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    /** An XML declaration, which is only ever the first thing in the file. */
    private val XML_PROLOG = Regex("""^\s*<\?xml[\s\S]*?\?>""", RegexOption.IGNORE_CASE)

    /**
     * A doctype, including the internal subset form.
     *
     * `[^>\[]*` rather than `.*?` so a `>` inside a quoted public identifier cannot end the match
     * early — XHTML doctypes quote one, and it routinely contains no `>` but nothing guarantees it.
     */
    private val DOCTYPE = Regex("""^\s*<!DOCTYPE[^>\[]*(?:\[[\s\S]*?\])?\s*>""", RegexOption.IGNORE_CASE)

    private val HTML_OPEN = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val HEAD_CLOSE = Regex("""</head\s*>""", RegexOption.IGNORE_CASE)

    private val BASE_TAG = Regex("""<base\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * `<meta http-equiv="refresh" …>`.
     *
     * `http-equiv` matched separately from `content` so a malformed attribute order does not smuggle
     * a refresh past the pattern.
     */
    private val META_REFRESH = Regex(
        """<meta\b[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    private const val HEAD_MARKER = "<!--kmk:document-->"

    private const val HTML_SHELL = """<!DOCTYPE html><html><body>"""
}
