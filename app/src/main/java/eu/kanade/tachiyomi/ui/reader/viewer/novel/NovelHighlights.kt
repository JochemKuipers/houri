package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.webkit.JavascriptInterface

/**
 * Highlights and the selection actions they ride in with — Moon+ Reader's annotation feature,
 * built for this reader's architecture.
 *
 * The model is the classic quotation anchor (Moon+ stores the same shape): the selected text plus
 * enough surrounding context to find it again after every reflow. A character offset means
 * something different at every font size, so — exactly as [NovelProgress] argues — the anchor is
 * what survives. The quotation is whitespace-normalised on both ends, and the prefix/suffix are
 * best-effort context: matching prefers an annotation whose context also matches, and falls back
 * to the quote alone, because a reader who highlighted a common word is better served by the
 * highlight landing on *a* occurrence than on none.
 *
 * Persistence goes through [eu.kanade.tachiyomi.ui.reader.setting.NovelTypographyPreferences],
 * beside reading positions: a JSON list per chapter under a `__PRIVATE_` preference — the same
 * store, the same privacy reasoning (annotations describe a file the backup does not contain).
 *
 * Everything here is JSON-by-hand, not kotlinx.serialization: this file is asserted by the offline
 * harness, which has no serialization compiler plugin (see `scripts/kotlinc-check`'s notes).
 */
object NovelHighlights {

    /** The four pen colours. Index-stable — a stored colour is a list index, so never reorder. */
    val COLORS = listOf("#F9A825", "#2E7D32", "#1565C0", "#AD1457")

    /** A stored highlight. */
    data class Highlight(
        val id: String,
        val color: Int,
        val quote: String,
        val prefix: String,
        val suffix: String,
        val createdAt: Long,
    )

    /** Caps, so one page of pathological selecting cannot bloat the chapter's preference. */
    const val MAX_PER_CHAPTER = 500
    const val MAX_QUOTE_CHARS = 1_200
    const val MAX_CONTEXT_CHARS = 160

    // ------------------------------------------------------------ JSON (hand-rolled, see class doc)

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u").append(String.format("%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
    }

    /** Encodes one list to a JSON array string; also the payload the page-side script reads. */
    fun encode(list: List<Highlight>): String = buildString {
        append('[')
        list.forEachIndexed { index, h ->
            if (index > 0) append(',')
            append("""{"id":""")
            append('"').append(escape(h.id)).append('"')
            append(""","color":${h.color}""")
            append(""","quote":"""")
            append(escape(h.quote))
            append('"')
            append(""","prefix":"""")
            append(escape(h.prefix))
            append('"')
            append(""","suffix":"""")
            append(escape(h.suffix))
            append('"')
            append(""","created":${h.createdAt}""")
            append('}')
        }
        append(']')
    }

    /** Parses what [encode] wrote, or an empty list when anything at all is off. */
    fun decode(raw: String): List<Highlight> {
        if (raw.isBlank()) return emptyList()
        fun str(field: String, body: String): String? =
            Regex(""""$field":"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1)
        fun num(field: String, body: String): Long? =
            Regex(""""$field":(-?[0-9]+)""").find(body)?.groupValues?.get(1)?.toLongOrNull()

        // One object at a time; the per-object extraction above is deliberately field-scoped so a
        // control character inside one field cannot leak into the next object's parse.
        val objects = Regex("""\{[^{}]*\}""").findAll(raw).toList()
        if (objects.isEmpty()) return emptyList()
        return objects.mapNotNull { match ->
            val body = match.value
            val id = Regex(""""id":"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val color = Regex(""""color":([0-9]+)""").find(body)?.groupValues?.get(1)
                ?.toIntOrNull() ?: return@mapNotNull null
            val quote = str("quote", body) ?: return@mapNotNull null
            Highlight(
                id = id,
                color = color.coerceIn(0, COLORS.lastIndex),
                quote = unescape(quote),
                prefix = unescape(str("prefix", body) ?: ""),
                suffix = unescape(str("suffix", body) ?: ""),
                createdAt = num("created", body) ?: 0L,
            )
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '"' -> append('"')
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        val hex = value.drop(i + 2).take(4)
                        hex.toIntOrNull(16)?.let {
                            append(it.toChar())
                            i += 4
                        } ?: append('u')
                    }
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }

    // ------------------------------------------------------------ page-side script

    /**
     * The page-side engine, installed once per document.
     *
     * `apply(json)` re-marks every annotation it can find, idempotently: matching skips nodes
     * that already sit inside a `mark[data-kmk-hl]`, so re-running it after an append or a
     * re-apply never doubles a highlight. Matching is whitespace-tolerant and prefers the
     * context-anchored form (prefix + quote + suffix) over the bare quote, so a highlighted
     * common phrase lands where it was taken rather than on the first occurrence.
     *
     * `watch()` reports selections back through the `KmkHighlight` interface, debounced, carrying
     * the normalised selection text, its context, and — when the selection sits inside an
     * existing highlight — that highlight's id, which is what turns the action sheet's
     * "remove" into a per-highlight action rather than a guess.
     */
    val ENGINE_SCRIPT: String = """
        (function() {
          if (window.KmkHighlights) { return; }
          function norm(s) { return s.replace(/\s+/g, ' '); }
          function reEscape(s) { return s.replace(/[.*+?^${'$'}{}|()[\]\\]/g, '\\$&'); }
          function spaceRe(s) { return norm(s).trim().split('').map(reEscape).join('\\s*'); }
          function collect(root) {
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
              acceptNode: function(n) {
                if (!n.nodeValue || !n.nodeValue.trim()) { return NodeFilter.FILTER_REJECT; }
                var p = n.parentNode;
                while (p && p !== root) {
                  if (p.nodeType === 1) {
                    var t = p.tagName;
                    if (t === 'SCRIPT' || t === 'STYLE') { return NodeFilter.FILTER_REJECT; }
                    if (t === 'MARK' && p.hasAttribute('data-kmk-hl')) { return NodeFilter.FILTER_REJECT; }
                  }
                  p = p.parentNode;
                }
                return NodeFilter.FILTER_ACCEPT;
              }
            });
            var nodes = [], starts = [], whole = '', n;
            while ((n = walker.nextNode())) {
              var v = norm(n.nodeValue);
              nodes.push(n); starts.push(whole.length); whole += v;
            }
            return { nodes: nodes, whole: whole };
          }
          function findSpan(c, re) {
            var m = re.exec(c.whole);
            if (!m) { return null; }
            return [m.index, m.index + m[0].length];
          }
          function wrap(c, span, id, color) {
            for (var i = 0; i < c.nodes.length; i++) {
              var value = c.nodes[i].nodeValue;
              var normStart = c.starts[i], normLen = norm(value).length;
              var normEnd = normStart + normLen;
              if (normEnd <= span[0] || normStart >= span[1]) { continue; }
              var lo = rawIndex(value, span[0] - normStart, +1);
              var hi = rawIndex(value, span[1] - normStart, -1);
              if (lo < 0 || hi <= lo) { continue; }
              var mark = document.createElement('mark');
              mark.setAttribute('data-kmk-hl', id);
              mark.className = 'kmk-hl-' + color;
              var tail = value.slice(hi);
              c.nodes[i].nodeValue = value.slice(0, lo);
              var inner = document.createTextNode(value.slice(lo, hi));
              mark.appendChild(inner);
              c.nodes[i].parentNode.insertBefore(mark, c.nodes[i].nextSibling);
              if (tail) { c.nodes[i].parentNode.insertBefore(document.createTextNode(tail), mark.nextSibling); }
            }
          }
          function rawIndex(value, normOffset, dir) {
            var normPos = 0, lastNorm = -1;
            for (var i = 0; i < value.length; i++) {
              if (/\s/.test(value[i])) { continue; }
              if (normPos >= normOffset) { return dir >= 0 ? i : lastNorm; }
              lastNorm = i; normPos++;
            }
            return dir >= 0 ? -1 : value.length;
          }
          window.KmkHighlights = {
            apply: function(payload) {
              var list;
              try { list = JSON.parse(payload); } catch (e) { return 0; }
              if (!list || !list.length) { return 0; }
              var done = 0;
              // A fresh collect per annotation: wrapping mutates the node list, and reusing it
              // would highlight against stale offsets. Bounded by MAX_PER_CHAPTER on the host.
              for (var i = 0; i < list.length; i++) {
                var a = list[i];
                var c = collect(document.body);
                var withContext = a.prefix && a.suffix
                  ? new RegExp(spaceRe(a.prefix) + spaceRe(a.quote) + spaceRe(a.suffix))
                  : null;
                var span = null;
                if (withContext) { span = findSpan(c, withContext); }
                if (!span) { span = findSpan(c, new RegExp(spaceRe(a.quote))); }
                if (!span) { continue; }
                wrap(c, span, a.id, a.color);
                done++;
              }
              return done;
            },
            remove: function(id) {
              var marks = document.querySelectorAll('mark[data-kmk-hl]');
              var changed = false;
              for (var i = 0; i < marks.length; i++) {
                if (marks[i].getAttribute('data-kmk-hl') === id) {
                  var p = marks[i].parentNode;
                  while (marks[i].firstChild) { p.insertBefore(marks[i].firstChild, marks[i]); }
                  p.removeChild(marks[i]);
                  p.normalize();
                  changed = true;
                }
              }
              return changed;
            },
            watch: function() {
              var pending = null;
              document.addEventListener('selectionchange', function() {
                if (pending) { clearTimeout(pending); }
                pending = setTimeout(function() {
                  pending = null;
                  var sel = window.getSelection();
                  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { return; }
                  var text = norm(sel.toString());
                  if (!text || text.length > 1200) { return; }
                  var range = sel.getRangeAt(0);
                  var insideId = null;
                  var node = range.startContainer;
                  while (node && node !== document.body) {
                    if (node.nodeType === 1 && node.tagName === 'MARK' && node.hasAttribute('data-kmk-hl')) {
                      insideId = node.getAttribute('data-kmk-hl');
                      break;
                    }
                    node = node.parentNode;
                  }
                  var hostText = norm(document.body.innerText || '');
                  var at = hostText.indexOf(text);
                  var payload = JSON.stringify({
                    quote: text,
                    prefix: at > 0 ? hostText.slice(Math.max(0, at - 100), at) : '',
                    suffix: at >= 0 ? hostText.slice(at + text.length, at + text.length + 100) : '',
                    id: insideId
                  });
                  try { window.KmkHighlight.onSelection(payload); } catch (e) {}
                }, 350);
              });
            }
          };
          window.KmkHighlights.watch();
        })();
    """.trimIndent() + "\n"

    /** The `mark` styles, emitted into every document so a stored colour needs no re-decode. */
    fun highlightCss(): String = buildString {
        appendLine("mark[data-kmk-hl] { color: inherit !important; background: transparent !important; -webkit-text-fill-color: currentColor; }")
        COLORS.forEachIndexed { index, color ->
            appendLine("mark.kmk-hl-$index { background-color: $color !important; -webkit-text-fill-color: inherit; }")
        }
    }

    /** Calls [ENGINE_SCRIPT]'s apply with the chapter's annotations. */
    fun applyScript(annotationsJson: String): String =
        "window.KmkHighlights && window.KmkHighlights.apply(${NovelScrollBridge.quoteForJs(annotationsJson)});"

    /** Unwraps one highlight in the live document. */
    fun removeScript(id: String): String =
        "window.KmkHighlights && window.KmkHighlights.remove(${NovelScrollBridge.quoteForJs(id)});"

    // ------------------------------------------------------------ bridge

    /**
     * The `window.KmkHighlight` receiver. Carries the debounced selection report as JSON
     * (`{quote, prefix, suffix, id}`), one call per settled selection.
     */
    class Host(val onSelection: (raw: String) -> Unit) {
        @JavascriptInterface
        fun onSelection(raw: String?) {
            if (!raw.isNullOrEmpty()) onSelection(raw)
        }
    }

    /** One settled selection, as the page reported it. */
    data class Selection(
        val quote: String,
        val prefix: String,
        val suffix: String,
        val existingId: String?,
    ) {
        companion object {
            fun decode(raw: String): Selection? = runCatching {
                fun str(field: String): String? {
                    val m = Regex(""""$field":"((?:[^"\\]|\\.)*)"""").find(raw)
                    return m?.groupValues?.get(1)
                }
                val id = Regex(""""id":"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                Selection(
                    quote = str("quote")?.let { unescape(it) } ?: return@runCatching null,
                    prefix = str("prefix")?.let { unescape(it) } ?: "",
                    suffix = str("suffix")?.let { unescape(it) } ?: "",
                    existingId = id?.takeUnless { it.isEmpty() }?.let { unescape(it) },
                )
            }.getOrNull()?.takeIf { it.quote.isNotBlank() }
        }
    }
}
