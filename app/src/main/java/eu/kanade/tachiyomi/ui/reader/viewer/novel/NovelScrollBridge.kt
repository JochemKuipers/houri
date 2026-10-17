package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * The reader's half of the contract with the document it is showing.
 *
 * A reflowable document has no pages, so the host cannot ask "which page am I on" — it can only ask
 * "how far through this document am I", and that answer has to come from the engine. These are the
 * two scripts that make the conversation possible: [BRIDGE_SCRIPT] installs `window.KmkReader` in
 * every document, and the `SNIPPETS` are what the host calls into it with.
 *
 * ### Scroll fraction, not pixel offset
 *
 * Everything is expressed as a fraction of the scrollable range so a position survives a font-size
 * change, an orientation flip and a page-count change. That is what makes [NovelProgress.Position]
 * restorable — see its own comment for why a page index or a pixel offset is not.
 *
 * ### Kept out of the viewer deliberately
 *
 * The viewer owns *what* is on screen; this owns *how the document is measured*. Every string here is
 * pure, so the parse side — which is where the bugs live, because `evaluateJavascript` hands back JSON
 * rather than a number — can be asserted off-device.
 *
 * No Android imports on purpose.
 */
object NovelScrollBridge {

    /**
     * How close to a document's end, in pixels, counts as being at it.
     *
     * Two rather than zero because a browser rounds a fractional scroll position and a document that
     * is one pixel short of its end is at its end as far as a reader is concerned. Sub-pixel
     * accuracy here would be a strict improvement nobody could perceive, and an exact-zero test can
     * report "not yet" forever on a document whose height settles a fraction short.
     *
     * Declared before [BRIDGE_SCRIPT_BODY] because an object's properties initialise in declaration
     * order and the script below interpolates this one.
     */
    private const val EDGE_TOLERANCE = 2

    /**
     * The bridge itself.
     *
     * `maxScroll` is the whole scrollable range, recomputed on every call: a document reflows while
     * images load, so a value cached at load time is wrong within a second of the book opening.
     *
     * Declared before [BRIDGE_SCRIPT] on purpose — an object's properties initialise in declaration
     * order, so a reader below it would otherwise take this one before it exists.
     */
    private val BRIDGE_SCRIPT_BODY: String =
        """
        (function () {
          if (window.KmkReader) return;
          var d = document;
          var self = {
            maxScroll: function () {
              var el = d.documentElement;
              var body = d.body;
              var height = Math.max(
                el ? el.scrollHeight : 0,
                body ? body.scrollHeight : 0
              );
              return Math.max(0, height - window.innerHeight);
            },
            top: function () {
              var el = d.documentElement;
              var y = window.pageYOffset;
              if (typeof y !== 'number') y = el ? el.scrollTop : 0;
              return y || 0;
            },
            fraction: function () {
              var max = self.maxScroll();
              if (max <= 0) return 0;
              return Math.min(1, Math.max(0, self.top() / max));
            },
            setFraction: function (f) {
              var max = self.maxScroll();
              var target = isFinite(f) ? f : 0;
              target = Math.min(1, Math.max(0, target));
              window.scrollTo(0, Math.round(max * target));
              return true;
            },
            scrollViewport: function (dir) {
              var max = self.maxScroll();
              if (max <= 0) return 0;
              var step = Math.max(1, Math.round(window.innerHeight * 0.9));
              var before = self.top();
              window.scrollTo(0, before + step * (dir < 0 ? -1 : 1));
              // Re-read rather than trusting the requested amount: near the end of a document the
              // scroll is clamped, and a caller that cannot tell "moved" from "did not move" turns
              // the document every time the reader taps past the last screen.
              return self.top() - before;
            },
            // Which way this document has run out of room. See EDGE for the codes: the host used to
            // infer this from a scroll that did not move, which cannot tell the top from the bottom
            // and cannot tell "at the end" from "has not laid out yet".
            edge: function () {
              var max = self.maxScroll();
              if (max <= 0) return 3;
              var t = self.top();
              if (t <= ${EDGE_TOLERANCE}) return 1;
              if (t >= max - ${EDGE_TOLERANCE}) return 2;
              return 0;
            }
          };
          window.KmkReader = self;
        })();
        """

    /** Installed into every document's head; defines `window.KmkReader`. */
    val BRIDGE_SCRIPT: String = BRIDGE_SCRIPT_BODY.trimIndent()

    /** Current scroll position as a fraction of the scrollable range, 0..1. */
    const val FRACTION: String = "(window.KmkReader && window.KmkReader.fraction()) || 0"

    /** Moves one viewport. Returns how far it actually moved, so a caller can tell a scroll from a no-op. */
    const val SCROLL_BY_VIEWPORT: String =
        "(function(){var r=window.KmkReader;if(!r)return 0;return r.scrollViewport(1);})()"

    const val SCROLL_BACK_BY_VIEWPORT: String =
        "(function(){var r=window.KmkReader;if(!r)return 0;return r.scrollViewport(-1);})()"

    /**
     * Which way this document has run out of room, if either.
     *
     * `window.KmkReader` grew an `edge()` alongside the scroll helpers because the host was
     * inferring this from *movement*, and movement is ambiguous in two directions at once:
     *
     *  - a scroll that did not move is not only "at the end", it is also "the document has not laid
     *    out yet" and "the bridge is not installed". Reading all three as "turn the document" makes
     *    the reader skip a page on every tap of a freshly-loaded document, and skip backwards the
     *    same way.
     *  - and the same test cannot tell the *top* from the *bottom*, so a tap that failed for either
     *    reason turned forward, which is how reaching the end of a document came to require the
     *    page navigator instead of the reader's own gesture.
     *
     * The document is the only thing that knows, so it answers directly. Returns a code rather than
     * an object because [number] is the parser this folder already has, and because a small integer
     * survives the JSON round trip without the escaping rules a string of untrusted text would need.
     *
     * | Code | Meaning |
     * |---|---|
     * | `-3` | no bridge — the host has no opinion and must not turn |
     * | `1` | at the very top; only a backward turn has anywhere to go |
     * | `0` | room in both directions |
     * | `2` | at the very bottom; only a forward turn has anywhere to go |
     * | `3` | the whole document is one screen, so either turn is the only turn there is |
     *
     * The two-pixel tolerances are the same idea as the smallest scroll step: a document that is
     * one pixel short of its end is at its end as far as a reader is concerned, and a browser that
     * rounds a fractional scroll position must not be able to answer "not yet" forever.
     */
    const val EDGE: String =
        "(function(){var r=window.KmkReader;if(!r)return -3;return r.edge();})()"

    /** [EDGE] narrowed to "is there nowhere left to go forward", for a caller that only turns forward. */
    const val AT_END: String =
        "(function(){var r=window.KmkReader;if(!r)return 0;var e=r.edge();return (e===2||e===3)?1:0;})()"

    /** Restores a previously reported fraction. Ignored until the document has laid out. */
    const val SET_FRACTION: String =
        "(function(){var r=window.KmkReader;if(!r)return false;r.setFraction(%f);return true;})()"

    /**
     * Scrolls to the element a table-of-contents entry names.
     *
     * Both spellings are checked because publishers use both and the href says which is which in
     * practice only by the book being well made: `id` is XHTML, `name` on an `<a>` is the HTML 4
     * habit that a great deal of EPUB2-era content still uses.
     */
    const val SCROLL_TO_ANCHOR: String =
        "(function(id,offset){" +
            "var el=null;try{el=document.getElementById(id);}catch(e){el=null;}" +
            "if(!el){var as=document.getElementsByTagName('a');" +
            "for(var i=0;i<as.length;i++){if(as[i].getAttribute('name')===id){el=as[i];break;}}}" +
            "if(!el){window.scrollTo(0,0);return false;}" +
            "var top=el.getBoundingClientRect().top+window.pageYOffset-(offset||0);" +
            "window.scrollTo(0,Math.max(0,top));return true;})(%s,%d)"

    /**
     * [SCROLL_TO_ANCHOR] with the fragment id already quoted as a JS string literal.
     *
     * `%d` is substituted **first**, and the order is load-bearing rather than cosmetic. These are
     * sequential literal replaces, and the second one scans the string the first already produced — so
     * doing `%s` first would rewrite the untrusted fragment text itself. A TOC id containing `%d`
     * would come out altered (`a%db` becomes `a32b`), the `getElementById` lookup misses, and the
     * reader silently lands at the top of the document with nothing to explain it.
     *
     * Doing `%d` first is safe because at that moment the string contains only the template's own
     * placeholder: the fragment has not been inserted yet, and the substituted digits cannot be
     * re-scanned afterwards because `%s` is a single replacement that does not rescan its result.
     */
    fun scrollToAnchor(fragment: String, topInsetPx: Int): String =
        SCROLL_TO_ANCHOR.replace("%d", topInsetPx.toString()).replace("%s", quoteForJs(fragment))

    /**
     * Encodes [value] as a JavaScript string literal.
     *
     * Hand-built rather than delegated to a JSON library because this runs on the host, where the
     * only JSON dependency is the extension API's serializer — and because the value is *not
     * trusted*: it comes from the book's own table of contents, so a fragment named
     * `"); alert(1); //` is a real input, not a hypothetical one.
     *
     * Also escapes the two sequences that can close an enclosing element, `</` and U+2028/U+2029,
     * which are line terminators to a JS parser but not to [String.trimIndent]'s block handling —
     * getting either wrong turns a contents entry into a script injection.
     */
    fun quoteForJs(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> append("\\/")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c.code < 0x20) {
                    append("\\u").append(String.format("%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /**
     * Parses a value out of an `evaluateJavascript` callback.
     *
     * The callback receives the JSON encoding of the script's return value, so a script returning the
     * number 0 arrives as the four characters `0` and one returning `NaN` arrives as the five
     * `null`. Both must read as "no movement", or the reader turns the page every time a tap lands on
     * a document that is not scrollable.
     */
    fun number(raw: String?): Double {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return 0.0
        return trimmed.toDoubleOrNull() ?: 0.0
    }
}
