package eu.kanade.tachiyomi.crash

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.WebViewUtil
import mihon.app.di.globalAppGraph
import java.time.Instant

/**
 * Cheap, cached provider of the diagnostic header that goes into every crash report and manual
 * dump: device/build info, extension health, and a pointer to previous crashes.
 *
 * This replaces the old `GlobalExceptionHandler` reflection hack (`getDeclaredMethod("getExtensionsInfo")`)
 * and the per-crash `CrashLogUtil(applicationContext)` construction that happened on the crash
 * path. Everything here is safe to call on a crashing thread: it touches only in-memory caches
 * and simple framework APIs, and every block is defensive.
 */
object CrashDiagnostics {

    @Volatile
    private var cachedDebugInfo: String? = null

    @Volatile
    private var cachedExtensionsInfo: String? = null

    /**
     * Full debug-info header. Cached after the first successful computation; invalidated by
     * [invalidate] (e.g. on a version change).
     */
    fun debugInfo(context: Context): String {
        cachedDebugInfo?.let { return it }
        return synchronized(this) {
            cachedDebugInfo ?: computeDebugInfo(context).also { cachedDebugInfo = it }
        }
    }

    /** Extension health section, or null when everything is fine. */
    fun extensionsInfo(): String? {
        cachedExtensionsInfo?.let { return it }
        return synchronized(this) {
            cachedExtensionsInfo ?: computeExtensionsInfo().also { cachedExtensionsInfo = it }
        }
    }

    /**
     * A short pointer to any previous crash report, so a crash loop is obvious at a glance.
     * Returns null when there is no previous report.
     */
    fun previousCrashPointer(context: Context, excludeTimestampMs: Long): String? {
        return try {
            CrashLogWriter.previousReport(context, excludeTimestampMs)
        } catch (_: Exception) {
            null
        }
    }

    /** Clear caches (e.g. after the app version changes or extensions are reloaded). */
    fun invalidate() {
        synchronized(this) {
            cachedDebugInfo = null
            cachedExtensionsInfo = null
        }
    }

    private fun computeDebugInfo(context: Context): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = activityManager?.let {
            val mi = android.app.ActivityManager.MemoryInfo()
            it.getMemoryInfo(mi)
            "RAM: ${mi.availMem / 1024 / 1024}MB avail / ${mi.totalMem / 1024 / 1024}MB total (low=${mi.lowMemory}, threshold=${mi.threshold / 1024 / 1024}MB)"
        } ?: "RAM: unknown"
        val heapInfo = try {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            val max = rt.maxMemory() / 1024 / 1024
            "Heap: ${used}MB used / ${max}MB max (free=${rt.freeMemory() / 1024 / 1024}MB)"
        } catch (_: Exception) {
            "Heap: unknown"
        }
        val storageInfo = try {
            val free = context.cacheDir.freeSpace / 1024 / 1024
            val total = context.cacheDir.totalSpace / 1024 / 1024
            "Storage: ${free}MB free / ${total}MB total (cacheDir)"
        } catch (_: Exception) {
            "Storage: unknown"
        }
        val batteryInfo = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level >= 0) "Battery: $level%" else "Battery: unknown"
        } catch (_: Exception) {
            "Battery: unknown"
        }
        val localeInfo = try {
            "Locale: ${java.util.Locale.getDefault()}"
        } catch (_: Exception) {
            "Locale: unknown"
        }
        val orientationInfo = try {
            "Orientation: ${context.resources.configuration.orientation}"
        } catch (_: Exception) {
            "Orientation: unknown"
        }
        return """
            App ID: ${eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID}
            App version: ${eu.kanade.tachiyomi.BuildConfig.VERSION_NAME} (${eu.kanade.tachiyomi.BuildConfig.COMMIT_SHA}, ${eu.kanade.tachiyomi.BuildConfig.VERSION_CODE}, ${eu.kanade.tachiyomi.BuildConfig.BUILD_TIME})
            Build version: ${eu.kanade.tachiyomi.BuildConfig.COMMIT_COUNT}
            Android version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}; build ${android.os.Build.DISPLAY})
            Device brand: ${android.os.Build.BRAND}
            Device manufacturer: ${android.os.Build.MANUFACTURER}
            Device name: ${android.os.Build.DEVICE} (${android.os.Build.PRODUCT})
            Device model: ${android.os.Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${Instant.now()}
            $memInfo
            $heapInfo
            $storageInfo
            $batteryInfo
            $localeInfo
            $orientationInfo
            Process: ${android.os.Process.myPid()} / Thread: ${Thread.currentThread().name}
            ${eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuDiagnostics.render()}
        """.trimIndent()
    }

    private fun computeExtensionsInfo(): String? {
        return try {
            val extensionManager = globalAppGraph.extensionManager as ExtensionManager
            val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

            val extensionInfoList = extensionManager.installedExtensionsFlow.value
                .sortedBy { it.name }
                .mapNotNull {
                    val availableExtension = availableExtensions[it.pkgName]
                    val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                    if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                    """
                        - ${it.name}
                          Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                          Orphaned: ${it.isObsolete}
                    """.trimIndent()
                }

            if (extensionInfoList.isNotEmpty()) {
                (listOf("Problematic extensions:") + extensionInfoList)
                    .joinToString("\n")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
