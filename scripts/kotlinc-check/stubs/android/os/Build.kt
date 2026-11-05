package android.os

/** Offline stub for check.sh: only the surface the mtl.* fingerprint path touches. */
class Build private constructor() {
    companion object {
        var HARDWARE: String = ""
        var MANUFACTURER: String = ""
        var MODEL: String = ""
        var BOARD: String = ""
        var BRAND: String = ""
        var DEVICE: String = ""
        var DISPLAY: String = ""
        var FINGERPRINT: String = ""
        var SDK_INT: Int = 0
    }

    /** Offline stub for check.sh. */
    class VERSION private constructor() {
        companion object {
            var SDK_INT: Int = 0
        }
    }
}
