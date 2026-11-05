package android.content

import android.view.WindowManager

/** Offline stub for check.sh: only the surface the mtl.* fingerprint path touches. */
open class Context {
    companion object {
        const val ACTIVITY_SERVICE: String = "activity"
        const val WINDOW_SERVICE: String = "window"
    }

    fun getSystemService(name: String): Any? = null
}

/** Offline stub for check.sh. */
class WindowManager {
    @Deprecated("Deprecated in Java")
    val defaultDisplay: Display? = null
}

/** Offline stub for check.sh. */
class Display {
    fun getMetrics(outMetrics: android.util.DisplayMetrics) { }
}

