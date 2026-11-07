package android.content

import android.view.WindowManager

/** Offline stub for check.sh: only the surface the mtl.* fingerprint path touches. */
open class Context {
    companion object {
        const val ACTIVITY_SERVICE: String = "activity"
        const val WINDOW_SERVICE: String = "window"
    }

    fun getSystemService(name: String): Any? = null

    /** Enough of AssetManager for the upscaling bootstrap's manifest reader. */
    val assets: AssetManager = AssetManager()

    /** Enough of PackageManager for the upscaling device probe (Vulkan feature check). */
    fun getPackageManager(): PackageManager = PackageManager()

    val packageManager: PackageManager
        get() = PackageManager()
}

/** Offline stub for check.sh. */
class AssetManager {
    fun open(name: String): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
}

/** Offline stub for check.sh. */
class PackageManager {
    fun hasSystemFeature(name: String): Boolean = false
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

