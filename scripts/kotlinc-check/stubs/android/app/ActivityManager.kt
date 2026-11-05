package android.app

/** Offline stub for check.sh: only the surface the mtl.* fingerprint path touches. */
class ActivityManager {
    class MemoryInfo {
        var totalMem: Long = 0
    }

    fun getMemoryInfo(outInfo: MemoryInfo) { }
}
