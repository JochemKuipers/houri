// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import exh.log.xLogE

/**
 * Cross-session record of WebGPU sessions whose device died without coming back.
 *
 * The failure shape this exists for (field reports, 2026-11): the renderer's device is lost
 * (`vkQueueSubmit failed with VK_ERROR_OUT_OF_DEVICE_MEMORY` - 32-bit processes and small GPU
 * heaps are the recurring profile), every recovery attempt gives up, and the reader stays
 * black until the process is killed - after which the next session does exactly the same
 * thing. No recovery ladder can fix a GPU that has no memory left, so the honest fallback is
 * the legacy viewer; this is what turns repeated dead sessions into that fallback instead of
 * a black screen the user must discover all over again every launch.
 *
 * One strike is recorded when a reader session closes with the renderer still dead, or when a
 * single session lost the device [SESSION_LOSS_STRIKE_THRESHOLD] times. [DISABLE_STRIKES]
 * strikes stop the WebGPU viewer from being offered until the user clears them - the About
 * screen easter egg (the same one that disables the RAM gate) resets the count.
 *
 * Struck sessions are deliberately cheap to forgive: one flaky day under memory pressure
 * records one strike and changes nothing; only a device that dies like this on consecutive
 * reader sessions disables the renderer.
 */
object WebGpuHealth {
    private const val PREFS = "webgpu_health"
    private const val KEY_STRIKES = "dead_device_strikes"

    /**
     * Device losses inside one reader session beyond which the session itself counts as
     * broken, recovered or not: a healthy device loses the GPU at most once per session
     * (backgrounding), and a device that loses it three times is thrashing the recovery
     * ladder - the user saw every one of those as a stall.
     */
    const val SESSION_LOSS_STRIKE_THRESHOLD = 3

    /** Strikes before [ViewerRegistry] stops offering the WebGPU viewer. */
    const val DISABLE_STRIKES = 2

    fun strikeCount(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STRIKES, 0)
    }.getOrDefault(0)

    fun isDisabled(context: Context): Boolean = strikeCount(context) >= DISABLE_STRIKES

    fun recordStrike(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = (prefs.getInt(KEY_STRIKES, 0) + 1).coerceAtMost(DISABLE_STRIKES)
            prefs.edit().putInt(KEY_STRIKES, next).apply()
            // KMK --> The app's logcat lines never reach a user who only has the phone; the
            // in-app log file is what a field report actually arrives with. Say what the
            // strike means for their next launches, and call out the disable explicitly -
            // "the reader stopped opening on WebGPU" with no record of why reads as a
            // mystery regression. xLogE is an Any-extension; the receiver names the tag.
            // The session aggregate rides along: the strike says *that* a session went wrong, this
            // says what it did. Neither half is much use to a field report without the other.
            val diagnostics = runCatching { WebGpuDiagnostics.render() }.getOrNull()
            xLogE(
                "WebGPU health: degraded session recorded (strike $next/$DISABLE_STRIKES)" +
                    if (next >= DISABLE_STRIKES) {
                        " - WebGPU viewer disabled; the standard reader will be used until " +
                            "the About-screen easter egg resets it"
                    } else {
                        ""
                    } + (diagnostics?.let { "\n$it" } ?: ""),
            )
            // KMK <--
        }
    }

    /** Clears every strike. True when the count actually moved, so the reset UI can say so. */
    fun resetStrikes(context: Context): Boolean {
        val had = strikeCount(context) > 0
        if (had) {
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_STRIKES, 0)
                    .apply()
            }
        }
        return had
    }
}
// KMK <--
