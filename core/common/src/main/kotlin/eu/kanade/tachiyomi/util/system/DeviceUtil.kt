package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object DeviceUtil {

    val isMiui: Boolean by lazy {
        getSystemProperty("ro.miui.ui.version.name")?.isNotEmpty() ?: false
    }

    /**
     * Extracts the MIUI major version code from a string like "V12.5.3.0.QFGMIXM".
     *
     * @return MIUI major version code (e.g., 13) or null if can't be parsed.
     */
    val miuiMajorVersion: Int? by lazy {
        if (!isMiui) return@lazy null

        Build.VERSION.INCREMENTAL
            .substringBefore('.')
            .trimStart('V')
            .toIntOrNull()
    }

    @SuppressLint("PrivateApi")
    fun isMiuiOptimizationDisabled(): Boolean {
        val sysProp = getSystemProperty("persist.sys.miui_optimization")
        if (sysProp == "0" || sysProp == "false") {
            return true
        }

        return try {
            Class.forName("android.miui.AppOpsUtils")
                .getDeclaredMethod("isXOptMode")
                .invoke(null) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    val isSamsung: Boolean by lazy {
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    val oneUiVersion: Double? by lazy {
        try {
            val semPlatformIntField = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT")
            val version = semPlatformIntField.getInt(null) - 90000
            if (version < 0) {
                1.0
            } else {
                ((version / 10000).toString() + "." + version % 10000 / 100).toDouble()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A list of package names that may be incorrectly resolved as usable browsers by
     * the system.
     *
     * If these are resolved for [android.content.Intent.ACTION_VIEW], it prevents the
     * system from opening a proper browser or any usable app .
     *
     * Some of them may only be present on certain manufacturer's devices.
     */
    val invalidDefaultBrowsers = listOf(
        "android",
        // Honor
        "com.hihonor.android.internal.app",
        // Huawei
        "com.huawei.android.internal.app",
        // Lenovo
        "com.zui.resolver",
        // Infinix
        "com.transsion.resolver",
        // Xiaomi Redmi
        "com.android.intentresolver",
    )

    // KMK --> Both of these used to be this file's own answer, computed from a 3GB threshold. They
    // now delegate to `AppMemoryMonitor`, which is the one place the device's memory class and the
    // RAM-gate bypass are defined - so the reader, Coil, the MTL/WebGPU gates and these two helpers
    // cannot disagree about what the device is.
    fun isRamGateDisabled(context: Context): Boolean =
        tachiyomi.core.common.util.system.AppMemoryMonitor.isRamGateDisabled(context)

    /**
     * Whether this device is "low memory" in the sense every subsystem now shares: a tier below
     * MEDIUM.
     *
     * The old body was `totalMem < 3GB`, which is the same boundary the tier fold uses but arrived
     * at without the vendor low-RAM mark or the 32-bit ABI cap - so a 4GB phone the OEM marked
     * low-RAM answered "not low memory" here and was demoted by the reader. Asking the tier fixes
     * that in one place.
     */
    fun isLowRamDevice(context: Context): Boolean {
        if (isRamGateDisabled(context)) return false
        return tachiyomi.core.common.util.system.AppMemoryMonitor.deviceTier(context) <
            tachiyomi.core.common.util.system.DeviceTier.MEDIUM
    }
    // KMK <--

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String?): String? {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .invoke(null, key) as String
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unable to use SystemProperties.get()" }
            null
        }
    }
}
