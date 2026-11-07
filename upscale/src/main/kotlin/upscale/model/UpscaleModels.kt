package upscale.model

import kotlinx.serialization.Serializable

/** A single backend-specific model file in the upscaler manifest. */
@Serializable
data class UpscaleArtifact(
    val format: String,
    val role: String,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val inputOrder: Int? = null,
    val outputOrder: Int? = null,
)

/** A logical upscaler model and the metadata needed to run it safely. */
@Serializable
data class UpscaleModelSpec(
    val id: String,
    val family: String,
    val label: String,
    val nativeScale: Int,
    val tileSize: Int = 128,
    val padding: Int = 16,
    val inputOrder: Int = 0,
    val outputOrder: Int = 1,
    val artifacts: List<UpscaleArtifact> = emptyList(),
    /** Set for user-imported models; they live outside the manifest and are deletable. */
    val imported: Boolean = false,
)

@Serializable
data class UpscaleManifest(
    val version: Int = 0,
    val models: List<UpscaleModelSpec> = emptyList(),
)

enum class UpscaleModelFormat {
    NCNN,
    ONNX,
}

data class ResolvedUpscaleModel(
    val spec: UpscaleModelSpec,
    val format: UpscaleModelFormat,
    val artifacts: List<UpscaleArtifact>,
) {
    val id: String get() = spec.id
    val family: String get() = spec.family
    val nativeScale: Int get() = spec.nativeScale
    val tileSize: Int get() = spec.tileSize
    val padding: Int get() = spec.padding

    val inputOrder: Int
        get() = artifacts.firstOrNull { it.inputOrder != null }?.inputOrder ?: spec.inputOrder

    val outputOrder: Int
        get() = artifacts.firstOrNull { it.outputOrder != null }?.outputOrder ?: spec.outputOrder

    fun artifact(role: String): UpscaleArtifact? = artifacts.firstOrNull { it.role == role }
}
