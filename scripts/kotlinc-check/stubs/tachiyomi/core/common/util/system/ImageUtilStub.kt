package tachiyomi.core.common.util.system

/**
 * Offline stub for check.sh: the two animated-detection overloads the upscaling spine calls.
 * Never compiled into the app.
 */
object ImageUtil {
    fun isAnimatedAndSupported(bytes: ByteArray): Boolean = false
}
