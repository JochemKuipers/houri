// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.novel

/**
 * Continuous-mode auto-scroll — Moon+ Reader's "scrolling" mode (`pref_scrolling_speed`, five
 * movement modes), reduced to the one mode a WebView document renders honestly: a smooth,
 * constant-pace scroll driven by `requestAnimationFrame`.
 *
 * ### Why page-side, and why it works
 *
 * Driving the scroll from the host (a coroutine issuing `scrollBy` per frame) would flood the
 * bridge with a round trip every frame and drift with main-thread jank. Inside the page, the
 * engine's own rAF callback paces the scroll and the document's own scroll events light up the
 * *existing* pipeline: the probe reports the section under the viewport, the window appends ahead
 * as the reader nears its end, and a push past the book's last edge turns the chapter — exactly
 * what a finger drag does, with no reader change at all.
 *
 * ### Pausing
 *
 * `touchstart` stops the engine (a reader who touches the page wants to act, not to fight the
 * scroll), as does the document's edge in the direction of travel. The host re-issues [START]
 * when the reader turns it back on; the engine keeps its speed across documents because each
 * document re-installs it and the host re-applies the pref, which is the same value.
 *
 * Pure strings — no Android imports — so the command grammar is asserted off-device.
 */
object NovelAutoScroll {

    /** The window global the engine installs itself under. */
    const val BRIDGE_NAME = "KmkAutoScroll"

    /**
     * The engine, installed in every continuous document.
     *
     * Idempotent like the other bridges. `speed` is px/second of *document* travel; the host
     * converts from dp using the display density when it sends [START].
     */
    const val ENGINE_SCRIPT: String = """
        (function () {
          if (window.$BRIDGE_NAME) return;
          var self = { speed: 0, running: false, last: 0, raf: 0 };
          function atEnd() {
            var doc = document.documentElement;
            return window.scrollY + window.innerHeight >= (doc ? doc.scrollHeight : 0) - 1;
          }
          function step(now) {
            if (!self.running) return;
            if (self.last) {
              var dt = Math.min(100, now - self.last) / 1000;
              window.scrollBy(0, self.speed * dt);
              // The document's last screen is where the engine's job ends: it stops itself rather
              // than burn a frame of no-op scrollBy every tick, and the reader turns the page the
              // way they always did. (The probe's own near-bottom appends have usually already
              // grown the window past this point; this is the book's true end.)
              if (atEnd()) {
                self.running = false;
                self.raf = 0;
                return;
              }
            }
            self.last = now;
            self.raf = requestAnimationFrame(step);
          }
          self.start = function (pxPerSecond) {
            var v = parseFloat(pxPerSecond);
            if (isFinite(v) && v > 0) self.speed = v;
            if (self.running) return 'run';
            if (!(self.speed > 0)) return 'idle';
            if (atEnd()) return 'end';
            self.running = true;
            self.last = 0;
            self.raf = requestAnimationFrame(step);
            return 'run';
          };
          self.stop = function () {
            self.running = false;
            if (self.raf) cancelAnimationFrame(self.raf);
            self.raf = 0;
            return 'stop';
          };
          self.setSpeed = function (pxPerSecond) {
            var v = parseFloat(pxPerSecond);
            if (isFinite(v) && v > 0) self.speed = v;
            return self.speed;
          };
          document.addEventListener('touchstart', function () { self.stop(); }, { passive: true });
          window.$BRIDGE_NAME = self;
        })();
    """

    /** Boots the engine at [pxPerSecond] and starts it. */
    fun startScript(pxPerSecond: Float): String =
        "(function(){var s=window.$BRIDGE_NAME;if(!s)return null;" +
            "s.setSpeed($pxPerSecond);return s.start($pxPerSecond);})()"

    /** Stops the engine; safe when the document never installed it. */
    const val STOP_SCRIPT: String = "(function(){var s=window.$BRIDGE_NAME;return s?s.stop():null;})()"

    /** Live speed change while running; the reply is the speed the engine now holds. */
    fun setSpeedScript(pxPerSecond: Float): String =
        "(function(){var s=window.$BRIDGE_NAME;return s?s.setSpeed($pxPerSecond):null;})()"

    /** Reads whether a document has the engine installed. */
    const val PRESENT_SCRIPT: String = "!!window.$BRIDGE_NAME"
}
// KMK <--
