package eu.kanade.tachiyomi.network.interceptor

import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A Cloudflare challenge handed to the UI (issue #79).
 *
 * The interceptor can solve a challenge invisibly, but some sites hand out one that needs a real
 * user (a Turnstile checkbox, an "are you human" page). When the app is in the foreground the
 * interceptor parks the request here instead, the app shows the challenge page in a popup, and the
 * request resumes as soon as the challenge leaves its `cf_clearance` cookie behind.
 *
 * Challenges are grouped per host: Cloudflare challenges every in-flight request to a site at
 * once, and a reader opening several pages would otherwise replace the popup with itself in a
 * burst, failing every request but the last. Requests to the site already on screen join that
 * site's session and resume together; a challenge for a different site replaces the one on
 * screen, because only one page can be shown at a time.
 *
 * The app registers [isAppInForeground] and [show] at startup — core/common cannot reference an
 * Activity — and the challenge screen is the only caller of [finish]. Every path out of the screen
 * ends in [finish], so a dismissed popup cannot leave a request waiting forever.
 */
object CloudflareChallenge {

    data class Pending(
        val url: String,
        val host: String,
        val headers: Map<String, String>,
        val result: CompletableDeferred<Boolean>,
    )

    /** Set by the app: true while the user is actually looking at it. */
    @Volatile
    var isAppInForeground: () -> Boolean = { false }

    /** Set by the app: opens the challenge screen. Called once per session. */
    @Volatile
    var show: ((Pending) -> Unit)? = null

    /** The challenge the UI should display right now, if any. */
    @Volatile
    private var pending: Pending? = null

    private val lock = Any()

    /**
     * Registers a challenge for the UI, or returns null when it has to be solved invisibly
     * (app in the background, or no screen registered).
     */
    fun request(url: String, headers: Map<String, String>): CompletableDeferred<Boolean>? {
        if (!isAppInForeground()) return null
        val show = show ?: return null

        val session = Pending(
            url = url,
            host = url.toHttpUrl().host,
            headers = headers,
            result = CompletableDeferred(),
        )
        synchronized(lock) {
            pending?.let { current ->
                if (current.host == session.host) {
                    // Same site, one shared challenge: join the session already on screen.
                    return current.result
                }
                // A different site: the older request gets a clean failure instead of waiting for
                // a popup that is gone.
                current.result.complete(false)
            }
            pending = session
        }
        show(session)
        return session.result
    }

    /** The pending challenge, for the activity that renders it. */
    fun current(): Pending? = synchronized(lock) { pending }

    /**
     * Resolves the pending challenge. Safe to call more than once and after [request] failed to
     * register — an already-finished or absent session is a no-op.
     */
    fun finish(solved: Boolean) {
        val session = synchronized(lock) {
            pending.also { pending = null }
        } ?: return
        session.result.complete(solved)
    }
}
