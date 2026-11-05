package android.util

/** Offline stub for check.sh: only the surface the mtl.* providers touch. */
object Base64 {
    const val DEFAULT: Int = 0
    const val NO_WRAP: Int = 2

    fun encodeToString(input: ByteArray, flags: Int): String = ""
}
