// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * The book as discrete pages: a CSS multi-column document inside the WebView, turned like a real
 * book.
 *
 * ### Why columns and not a custom pager
 *
 * The document is still the publisher's XHTML laid out by the same engine that renders it in
 * continuous mode - drop caps, ruby, tables and the book's own stylesheet all keep working, because
 * pagination is applied *to the flow*, not re-implemented beside it. `column-count: N` with a fixed
 * height splits the flow into column boxes; the column a reader is on is just a scroll offset along
 * the inline axis, and turning a page is `scrollLeft += one screen of columns`.
 *
 * ### What "forward" means, and why the bridge decides it
 *
 * A Latin book lays columns left-to-right and scrolls toward `+scrollLeft`. A Japanese book in
 * vertical writing (縦書き) - and any `direction: rtl` book - lays columns right-to-left and scrolls
 * toward **negative** `scrollLeft`. Both are one `writing-mode`/`direction` read away, and the
 * reader cannot know it from outside; so the bridge measures it once at setup and exposes a
 * *normalised* position where forward is always `+1`, whatever sign the engine's scroll offset uses.
 * The host never has to know which way the book runs.
 *
 * ### What scrolls
 *
 * `overflow: hidden` on the body makes the body itself a scroll container: the reader cannot scroll
 * it with a gesture, but script can drive `scrollLeft`, which is exactly the division of labour a
 * paged book wants - turns are a reader action, never an accident of a stray swipe.
 *
 * ### Position across reflows
 *
 * Column indexes move with every typography change, so the bridge speaks in **fractions of the
 * document** (screen `p` of `n`), the same currency [NovelProgress.Position] stores. A font-size
 * change that turns 10 screens into 14 still restores to the same *place in the book*.
 *
 * Everything here is pure strings and pure parsers - no Android imports - so it is asserted
 * off-device like the rest of the folder's pure half.
 */
object NovelPageLayout {

    /** How the book is laid out on screen. Stored as the preference's ordinal. */
    enum class PageMode(val columns: Int, val isPaged: Boolean) {
        /** One continuous scroll, sections appended as the reader reaches them. */
        CONTINUOUS(1, false),

        /** One column per screen, turned like a book page. */
        PAGED(1, true),

        /** Two columns per screen - the classic open spread. */
        DOUBLE(2, true),
        ;

        companion object {
            fun fromPreference(value: Int): PageMode = entries.getOrElse(value) { CONTINUOUS }
        }
    }

    /** The gutter between the two columns of a spread, in CSS px (≈ dp in the WebView). */
    const val DOUBLE_COLUMN_GAP_PX = 24

    /**
     * The static half of the paged stylesheet, emitted after the reader's own typography so its
     * `!important` rules win by source order against the typography's `html, body` group.
     *
     * - `html` keeps the height and loses its margin: the page box must be exactly the viewport,
     *   and the typography's `html, body { margin }` would otherwise double the gutters (the body
     *   carries the margins alone - they become the page's side margins).
     * - `overflow: hidden` on both is what makes the *body* the scroll container the bridge
     *   drives: a reader's swipe cannot move it, script can.
     * - `column-fill: auto` fills columns in order instead of balancing them - a book page is a
     *   *sequence*, not a pair of equal columns.
     */
    const val PAGED_STATIC_CSS =
        """
html { margin: 0 !important; height: 100% !important; overflow: hidden !important; }
body { height: 100% !important; overflow: hidden !important; column-fill: auto !important; }
"""

    /** Wraps [css] for the document head, escaping `</` the way every emitted style must. */
    fun styleTag(css: String): String =
        if (css.isBlank()) "" else """<style id="kmk-paged">${css.replace("</", "<\\/")}</style>"""

    /**
     * The paged bridge, installed only in paged documents.
     *
     * Idempotent, like the scroll bridge it sits beside: a book's own scripts cannot rely on it
     * and a document that never calls [setupScript] simply keeps `ready === false` and every
     * query answers `null`, which the host reads as "not a paged document".
     *
     * `setup` measures the body's *computed* margins - the reader's chosen margins, whatever the
     * publisher's stylesheet did to them - and sizes the column box to what remains of the
     * viewport. It re-runs on resize (rotation) and once the book's own fonts have loaded (a late
     * font changes every column count), re-landing on the same fraction of the document both
     * times so the reader never sees a different page than the one they were on.
     */
    const val PAGED_BRIDGE_SCRIPT: String =
        """
        (function () {
          if (window.KmkPaged) return;
          var self = {
            ready: false,
            columns: 1,
            gap: 0,
            colW: 0,
            step: 0,
            screens: 1,
            forwardSign: 1,
            scroller: null,
            body: null
          };
          function scrollerFor(b) {
            // overflow:hidden makes the body a scroll container; a book whose publisher CSS
            // defeats that (body not the box that clipped) falls back to the document element.
            if (b.scrollWidth > b.clientWidth + 1) return b;
            var de = document.documentElement;
            return (de.scrollWidth > de.clientWidth + 1) ? de : b;
          }
          function rawPos() {
            if (!self.ready) return 0;
            var s = self.scroller;
            return self.forwardSign > 0 ? s.scrollLeft : -s.scrollLeft;
          }
          function screenIndex(raw) {
            if (self.step <= 0) return 0;
            var i = Math.round(raw / self.step);
            return Math.max(0, Math.min(self.screens - 1, i));
          }
          function scrollToScreen(i) {
            if (!self.ready) return false;
            var s = self.scroller;
            s.style.scrollBehavior = 'auto';
            s.scrollLeft = self.forwardSign * i * self.step;
            s.style.scrollBehavior = '';
            return true;
          }
          function measure() {
            var b = document.body, de = document.documentElement;
            if (!b || !de) return false;
            self.body = b;
            var cs = window.getComputedStyle(b);
            var dcs = window.getComputedStyle(de);
            var winW = window.innerWidth, winH = window.innerHeight;
            if (winW <= 0 || winH <= 0) return false;
            function px(v) { var f = parseFloat(v); return isNaN(f) ? 0 : f; }
            var ml = px(cs.marginLeft), mr = px(cs.marginRight);
            var mt = px(cs.marginTop), mb = px(cs.marginBottom);
            var avail = Math.max(1, winW - ml - mr);
            var gap = self.columns > 1 ? $DOUBLE_COLUMN_GAP_PX : 0;
            var colW = Math.floor((avail - gap * (self.columns - 1)) / self.columns);
            if (colW < 1) return false;
            // setProperty with 'important', not plain assignment: the reader's stylesheets force
            // `html, body` geometry with !important (typography margins, the paged box), and a
            // plain inline rule loses that fight. An important inline rule outranks an important
            // author rule, which is exactly the precedence the reader's own layout needs over its
            // own defaults.
            b.style.setProperty('width', avail + 'px', 'important');
            b.style.setProperty('height', Math.max(1, winH - mt - mb) + 'px', 'important');
            b.style.columnCount = '' + self.columns;
            b.style.columnGap = gap + 'px';
            b.style.columnFill = 'auto';
            self.gap = gap;
            self.colW = colW;
            self.step = self.columns * (colW + gap);
            self.scroller = scrollerFor(b);
            var wm = cs.writingMode || dcs.writingMode || '';
            var vertical = wm.indexOf('vertical') === 0;
            var rtl = cs.direction === 'rtl' || dcs.direction === 'rtl';
            self.forwardSign = (vertical || rtl) ? -1 : 1;
            // Total columns across the whole flow; scrollWidth already counts the trailing gap's
            // space once, so +gap / (colW+gap) lands on the whole count.
            var total = Math.max(self.columns, Math.round((self.scroller.scrollWidth + gap) / (colW + gap)));
            self.screens = Math.max(1, Math.ceil(total / self.columns));
            return true;
          }
          self.setup = function (columns) {
            self.columns = (columns === 2) ? 2 : 1;
            if (!measure()) return null;
            if (!self.ready) {
              self.ready = true;
              var resettle = function () {
                if (!self.ready) return;
                var f = self.fraction();
                if (measure()) {
                  var i = Math.round(f * (self.screens - 1));
                  scrollToScreen(self.screens > 1 ? i : 0);
                }
              };
              window.addEventListener('resize', resettle);
              if (document.fonts && document.fonts.ready) {
                document.fonts.ready.then(function () { resettle(); }, function () {});
              }
            } else {
              // A re-setup asked for by the host (a typography swap): keep the fraction.
              var f = self.fraction();
              if (measure()) {
                var i = Math.round(f * (self.screens - 1));
                scrollToScreen(self.screens > 1 ? i : 0);
              }
            }
            return {
              n: self.screens,
              l: self.forwardSign < 0 ? 1 : 0,
              w: self.body ? (window.getComputedStyle(self.body).writingMode || '').indexOf('vertical') === 0 ? 1 : 0
            };
          };
          self.resettle = function () {
            if (!self.ready) return null;
            var f = self.fraction();
            if (measure()) {
              var i = Math.round(f * (self.screens - 1));
              scrollToScreen(self.screens > 1 ? i : 0);
            }
            return self.screens;
          };
          self.turn = function (dir) {
            if (!self.ready) return null;
            var before = rawPos();
            self.scroller.scrollLeft += self.forwardSign * (dir > 0 ? 1 : -1) * self.step;
            var after = rawPos();
            var moved = Math.abs(after - before) >= 1;
            var at = screenIndex(after);
            return {
              m: moved ? 1 : 0,
              p: at,
              n: self.screens,
              s: at >= self.screens - 1 ? 1 : 0,
              b: at <= 0 ? 1 : 0
            };
          };
          self.fraction = function () {
            if (!self.ready || self.screens < 2) return 0;
            return screenIndex(rawPos()) / (self.screens - 1);
          };
          self.setFraction = function (f) {
            if (!self.ready) return false;
            var t = isFinite(f) ? Math.min(1, Math.max(0, f)) : 0;
            var i = Math.round(t * (self.screens - 1));
            scrollToScreen(i);
            return true;
          };
          // Reading column of an element, counted from the book's start (column 0) - visual order
          // for a right-to-left flow is the reverse of reading order.
          self.columnOf = function (el) {
            if (!self.ready || !el) return 0;
            var keep = self.scroller.scrollLeft;
            self.scroller.style.scrollBehavior = 'auto';
            self.scroller.scrollLeft = 0;
            var r = el.getBoundingClientRect();
            var br = self.body.getBoundingClientRect();
            var x = r.left - br.left;
            self.scroller.scrollLeft = keep;
            self.scroller.style.scrollBehavior = '';
            var visual = Math.max(0, Math.floor(x / (self.colW + self.gap)));
            var total = Math.max(1, Math.round((self.scroller.scrollWidth + self.gap) / (self.colW + self.gap)));
            return self.forwardSign > 0 ? visual : (total - 1 - visual);
          };
          self.goToElement = function (el) {
            if (!self.ready || !el) return false;
            var col = self.columnOf(el);
            return scrollToScreen(Math.floor(col / self.columns));
          };
          window.KmkPaged = self;
        })();
        """

    /** Boots pagination for [columns] columns; the reply carries the shape the host needs. */
    fun setupScript(columns: Int): String =
        "(window.KmkPaged ? window.KmkPaged.setup($columns) : null)"

    /**
     * One page turn. [dir] is `+1` for forward; the bridge applies the book's own direction, so
     * the host's idea of "forward" never depends on the writing mode.
     */
    fun turnScript(dir: Int): String =
        "(window.KmkPaged ? window.KmkPaged.turn(${if (dir > 0) 1 else -1}) : null)"

    /** Current position as a fraction of the document's screens. */
    fun fractionScript(): String = "(window.KmkPaged ? window.KmkPaged.fraction() : null)"

    /** Seeks to [fraction] of the document. */
    fun setFractionScript(fraction: Float): String =
        "(window.KmkPaged ? window.KmkPaged.setFraction(${fraction.coerceIn(0f, 1f)}) : null)"

    /** Re-runs the measurement, keeping the current fraction - the live-typography companion. */
    fun resettleScript(): String = "(window.KmkPaged ? window.KmkPaged.resettle() : null)"

    /**
     * Scrolls to the element a contents entry names, by the screen its column lands on.
     *
     * Same contract as [NovelScrollBridge.SCROLL_TO_ANCHOR]: both anchor spellings (`id`, `name`),
     * applied after layout, `null` when the document cannot answer.
     */
    fun goToAnchorScript(fragment: String): String =
        "(function(id){" +
            "if(!window.KmkPaged)return null;" +
            "var el=null;try{el=document.getElementById(id);}catch(e){el=null;}" +
            "if(!el){var as=document.getElementsByTagName('a');" +
            "for(var i=0;i<as.length;i++){if(as[i].getAttribute('name')===id){el=as[i];break;}}}" +
            "if(!el)return false;" +
            "return window.KmkPaged.goToElement(el);}" +
            ")(${NovelScrollBridge.quoteForJs(fragment)})"

    // ---------------------------------------------------------------- host-side parsing

    /** What a [setupScript] reply told the host about the freshly paginated document. */
    data class PagedLayout(
        /** How many screens the document paginated into. */
        val screens: Int,
        /** True when forward is a leftward scroll (right-to-left or vertical writing). */
        val forwardIsLeft: Boolean,
        /** True when the book is in vertical writing mode. */
        val verticalWriting: Boolean,
    )

    /**
     * Parses the [setupScript] reply.
     *
     * `evaluateJavascript` hands back the JSON encoding of the return value, so a successful setup
     * arrives as `{"n":12,"l":1,"w":1}` and a document that cannot answer as the four characters
     * `null` - the "bridge absent" case rather than an error, exactly as the scroll bridge's
     * probes treat it.
     */
    fun parseLayout(raw: String?): PagedLayout? {
        if (raw.isNullOrBlank() || raw.trim() == "null") return null
        val screens = SCREENS.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return PagedLayout(
            screens = screens.coerceAtLeast(1),
            forwardIsLeft = FORWARD_IS_LEFT.find(raw)?.groupValues?.get(1) == "1",
            verticalWriting = VERTICAL.find(raw)?.groupValues?.get(1) == "1",
        )
    }

    /** What a [turnScript] reply told the host about the turn. */
    data class PagedTurn(
        /** Whether the scroll moved - false means the document has run out of pages this way. */
        val moved: Boolean,
        /** The screen the reader is on now. */
        val screen: Int,
        /** Total screens in the document. */
        val screens: Int,
    ) {
        /** Position as a fraction of the document, the value the slider and the store speak. */
        val fraction: Float get() = if (screens < 2) 0f else (screen.toFloat() / (screens - 1)).coerceIn(0f, 1f)
    }

    /**
     * Parses the [turnScript] reply.
     *
     * A turn can land without moving (`m:0`) when the reader was already on the last or first
     * screen - that is the host's signal to turn the *document*, mirroring the continuous viewer's
     * "no movement means the edge" verdict.
     */
    fun parseTurn(raw: String?): PagedTurn? {
        if (raw.isNullOrBlank() || raw.trim() == "null") return null
        val moved = MOVED.find(raw)?.groupValues?.get(1) == "1"
        val screen = SCREEN.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val screens = SCREENS.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return PagedTurn(
            moved = moved,
            screen = screen.coerceIn(0, (screens - 1).coerceAtLeast(0)),
            screens = screens.coerceAtLeast(1),
        )
    }

    /** Reads a fraction out of a [fractionScript] reply; NaN arrives JSON-encoded as `null`. */
    fun parseFraction(raw: String?): Float? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        return trimmed.toFloatOrNull()?.coerceIn(0f, 1f)
    }

    private val SCREENS = Regex(""""n":(\d+)""")
    private val SCREEN = Regex(""""p":(\d+)""")
    private val MOVED = Regex(""""m":(\d+)""")
    private val FORWARD_IS_LEFT = Regex(""""l":(\d+)""")
    private val VERTICAL = Regex(""""w":(\d+)""")
}
// KMK <--
