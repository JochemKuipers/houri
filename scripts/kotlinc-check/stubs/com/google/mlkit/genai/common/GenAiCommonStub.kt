package com.google.mlkit.genai.common

/**
 * Offline stub for check.sh: the ML Kit GenAI common surface the mtl.* Nano provider touches.
 * The real classes arrive from `com.google.mlkit:genai-prompt` at build time.
 */

/** Offline stub for check.sh. */
object FeatureStatus {
    const val UNAVAILABLE: Int = 0
    const val AVAILABLE: Int = 1
    const val DOWNLOADABLE: Int = 2
    const val DOWNLOADING: Int = 3
}

/** Offline stub for check.sh. */
sealed class DownloadStatus {
    object DownloadCompleted : DownloadStatus()
    class DownloadFailed(val e: Exception) : DownloadStatus()
    object DownloadInProgress : DownloadStatus()
}

/** Offline stub for check.sh. */
class GenAiException(message: String?) : Exception(message)
