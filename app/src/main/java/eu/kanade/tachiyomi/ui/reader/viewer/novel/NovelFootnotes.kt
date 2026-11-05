// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.webkit.JavascriptInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Footnote popups, Moon+ Reader style (`show_footnote` / decompiled `getFootnoteHtml`): a tap on
 * a note reference shows the note beside the text instead of turning to the notes document.
 *
 * The two halves live here because they answer to each other:
 *
 * - [CLICK_SCRIPT] runs inside every document (injected beside the scroll bridge, same escaping
 *   rules). It is the only half that can see the tap *before* navigation: capture-phase click
 *   listener, reference-shaped anchors only, and it decides note-vs-link from the anchor's
 *   `epub:type` and the target element's own vocabulary — the two spellings EPUB 3 actually
 *   ships. When the target sits in the same document, its `outerHTML` rides up to the host; when
 *   it names another document, the raw `href` does, and the host reads that document out of the
 *   book.
 * - [Host] is the `addJavascriptInterface` receiver the script calls. Its single method runs on a
 *   JS-bridge thread, parses the payload there, and hands the result to [onNote] — which the
 *   viewer points at the main thread before installing it.
 *
 * Deliberately NOT a heuristic reimplementation of Moon+'s: their scan tolerates producers that
 * spell notes with no vocabulary at all, and pays for it with a 200-character attribute window
 * and triple fallback matching. Ours says: a reference the book *labels* as a note pops up, a
 * link it does not navigates. A book whose notes are unlabelled keeps the ordinary behaviour,
 * which works and never lies.
 */
object NovelFootnotes {

    /** What the injected script sends up for one tap. */
    @Serializable
    data class NotePayload(
        /** The note element's markup, when the target lived in the same document. */
        val html: String? = null,
        /**
         * The note reference's href, when the target lives in another document (or the host must
         * resolve it). The fragment is percent-decoded by the script before this is sent.
         */
        val href: String? = null,
    )

    /**
     * The `addJavascriptInterface` receiver. One method, one argument, no state: the surface a
     * hostile book can reach is exactly [receive], and what it can do with it is produce a string
     * the host then *renders as the note it claimed to be* — inside the same sandboxed popup
     * every other document renders in.
     */
    class Host(private val onNote: (NotePayload) -> Unit) {

        @JavascriptInterface
        fun receive(raw: String?) {
            if (raw.isNullOrBlank() || raw.length > MAX_PAYLOAD_CHARS) return
            val payload = runCatching { json.decodeFromString<NotePayload>(raw) }.getOrNull() ?: return
            onNote(payload)
        }

        private companion object {
            val json = Json { ignoreUnknownKeys = true }
        }
    }

    /**
     * The document-side half: installs once per document, capture phase, reference-shaped anchors
     * only.
     *
     * `epub:type` is read with the plain spelling — an XHTML document parsed as HTML keeps the
     * prefixed attribute name as-is, and `getAttributeNS` is the spelling that returns null for
     * it. The id/class fallback uses EPUB's own conventions (`fn`, `footnote`, `endnote`) with a
     * token boundary, so `id="function-plot"` does not read as a note.
     */
    const val CLICK_SCRIPT: String = """
        (function () {
          if (window.__kmkFootnote) return;
          window.__kmkFootnote = true;
          if (!window.KmkFootnote) return;
          function types(el) {
            return (el.getAttribute('epub:type') || '').split(/\s+/);
          }
          function isNoteType(list) {
            for (var i = 0; i < list.length; i++) {
              if (/^(footnote|endnote|note|noteref|rearnote)${'$'}/.test(list[i])) return true;
            }
            return false;
          }
          function tokenMatched(text) {
            return /(^|[^a-z])(footnote|endnote|noteref|fn)([^a-z]|${'$'})/i.test(text || '');
          }
          function refShaped(el) {
            return isNoteType(types(el)) ||
              tokenMatched(el.id) ||
              tokenMatched(el.getAttribute('class'));
          }
          function fragmentOf(href) {
            var at = href.indexOf('#');
            return at < 0 ? null : decodeURIComponent(href.slice(at + 1));
          }
          function send(payload) {
            try { window.KmkFootnote.receive(JSON.stringify(payload)); } catch (err) { }
          }
          document.addEventListener('click', function (e) {
            var target = e.target;
            var a = target && target.closest ? target.closest('a[href]') : null;
            if (!a) return;
            var href = a.getAttribute('href');
            if (!href || href.indexOf('#') < 0) return;
            var id = fragmentOf(href);
            if (!id) return;
            var isRef = refShaped(a);
            var el = document.getElementById(id) ||
              (document.getElementsByName(id).length ? document.getElementsByName(id)[0] : null);
            if (el) {
              // A target in this document: pop the note up when either side of the reference
              // names it as one, and otherwise let the ordinary anchor scroll happen.
              if (isRef || refShaped(el)) {
                e.preventDefault();
                e.stopPropagation();
                var html = el.outerHTML || '';
                if (html.length) send({ html: html.slice(0, 60000) });
              }
              return;
            }
            // No target here: either another document's note, or a broken reference. Only the
            // labelled ones leave the document; everything else keeps the navigation the book
            // asked for, which is also the fallback when the host cannot resolve the note.
            if (isRef) {
              e.preventDefault();
              e.stopPropagation();
              send({ href: href });
            }
          }, true);
        })();
    """

    /** Anything larger is not a note; it is a payload attack. */
    const val MAX_PAYLOAD_CHARS = 128_000

    /** The cap the script itself applies to in-document note markup. */
    const val MAX_NOTE_HTML_CHARS = 60_000
}
// KMK <--
