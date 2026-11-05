package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import exh.log.xLogE
import java.util.Locale

/**
 * The read-aloud controller, Moon+ Reader's "listen to your book", on the system TTS engine.
 *
 * The queue is the document's own text, chunked by [NovelPlainText.chunkForSpeech]; when the last
 * chunk of a document finishes, [onDocumentEnd] fires and the *viewer* decides what "next" means
 * (the next section of the continuous window, the next document of a paged book) and hands the
 * following document's text back in. The controller holds no reader state — the reader does.
 *
 * Threading: `UtteranceProgressListener` arrives on a binder thread; everything it touches hops
 * to [main] first. `stop()` is the only method safe from any thread, because the engine demands
 * it be callable while a callback is in flight.
 */
class NovelTts(
    context: Context,
    private val main: (Runnable) -> Unit,
    private val onDocumentEnd: () -> Unit,
) {
    private var engine: TextToSpeech? = null

    /** Set once the engine reports ready; every public method is inert until then. */
    private var ready = false

    /** The queue: chunks, and the cursor into them. Both main-thread only. */
    private var chunks: List<String> = emptyList()
    private var cursor = 0

    /** True between [start] and [stop] — the "the reader asked for speech" flag, not engine state. */
    var active: Boolean = false
        private set

    private var rate: Float = 1.0f

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (!active) return
            main {
                if (!active) return@main
                cursor += 1
                if (cursor < chunks.size) {
                    speakCurrent()
                } else {
                    // The document is spoken out; the reader turns and hands the next text in.
                    onDocumentEnd()
                }
            }
        }

        @Deprecated("Kept for API parity; errors arrive via onError(String, int) on newer engines.")
        override fun onError(utteranceId: String?) {
            xLogE("TTS utterance failed")
            // A failed utterance must not hang the queue: skip it as if it had finished.
            onDone(utteranceId)
        }
    }

    init {
        runCatching {
            engine = TextToSpeech(context.applicationContext) { status ->
                main {
                    ready = status == TextToSpeech.SUCCESS
                    if (!ready) {
                        xLogE("TTS engine init failed (status $status)")
                        active = false
                    }
                }
            }.also { it.setOnUtteranceProgressListener(listener) }
        }.onFailure { xLogE("TTS engine unavailable", it) }
    }

    /** Begins speaking [text] from its first chunk, at the engine's [rate]. */
    fun start(text: String, rate: Float) {
        this.rate = rate
        if (!ready) return
        engine?.stop()
        chunks = NovelPlainText.chunkForSpeech(text)
        cursor = 0
        if (chunks.isEmpty()) {
            // Nothing speakable on this document: hand the turn to the reader immediately.
            onDocumentEnd()
            return
        }
        active = true
        speakCurrent()
    }

    /** Stops and clears the queue. Safe from any thread, and idempotent. */
    fun stop() {
        active = false
        chunks = emptyList()
        cursor = 0
        runCatching { engine?.stop() }
    }

    /** Releases the engine. The reader calls this when it is destroyed. */
    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    private fun speakCurrent() {
        val engine = engine ?: return
        val text = chunks.getOrNull(cursor) ?: return
        runCatching {
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "kmk-tts-$cursor")
        }.onFailure { xLogE("TTS speak failed", it) }
    }
}
