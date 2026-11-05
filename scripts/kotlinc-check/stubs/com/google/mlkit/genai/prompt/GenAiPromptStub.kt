package com.google.mlkit.genai.prompt

import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Offline stub for check.sh: the ML Kit GenAI Prompt API surface the mtl.* Nano provider
 * touches. The real classes arrive from `com.google.mlkit:genai-prompt` at build time.
 */

/** Offline stub for check.sh. */
open class Part

/** Offline stub for check.sh. */
class ImagePart(val bitmap: android.graphics.Bitmap) : Part()

/** Offline stub for check.sh. */
class TextPart(val text: String) : Part()

/** Offline stub for check.sh. */
class GenerateContentRequest internal constructor()

/** Offline stub for check.sh. */
class GenerateContentConfig {
    var temperature: Float = 0f
    var maxOutputTokens: Int = 0
}

/** Offline stub for check.sh. */
fun generateContentRequest(vararg parts: Part, config: GenerateContentConfig.() -> Unit = {}): GenerateContentRequest =
    GenerateContentRequest()

/** Offline stub for check.sh. */
class GenerateContentResponse {
    class Candidate {
        val text: String? = null
    }

    val candidates: List<Candidate> = emptyList()
}

/** Offline stub for check.sh. */
class GenerativeModel {
    fun checkStatus(): Int = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE
    fun download(): Flow<DownloadStatus> = emptyFlow()
    suspend fun generateContent(request: GenerateContentRequest): GenerateContentResponse = GenerateContentResponse()
}

/** Offline stub for check.sh. */
object Generation {
    fun getClient(): GenerativeModel = GenerativeModel()
}
