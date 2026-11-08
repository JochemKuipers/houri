package upscale.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Downloads and verifies the model artifacts consumed by the native/ORT upscalers.
 *
 * Ported from `UpscaleModelManager` with the on-disk contract intact (`filesDir/upscale_models`,
 * same artifact names, same verification). The Android `Context` is injected as its two reads —
 * the manifest asset and the models directory — so the class is constructible off-device.
 *
 * Additions over the old manager:
 * - per-model readiness ([modelStates]) for the settings UI's per-model rows,
 * - user-imported ONNX models ([importOnnx]), validated by probing a real ORT session load,
 * - auto-download ([maybeAutoDownload]) so the first native enable fetches what it needs.
 */
class UpscaleModelManager(
    private val manifestAssetReader: (String) -> ByteArray?,
    private val modelsDir: File,
    private val client: OkHttpClient,
    /** Opens an ORT session for the import probe; non-null + closable means loadable. */
    private val ortProbe: (path: String) -> AutoCloseable?,
) {
    enum class State { NOT_INSTALLED, DOWNLOADING, READY, ERROR }

    data class Status(
        val state: State,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val currentFile: String? = null,
        val error: String? = null,
        val readyModelIds: Set<String> = emptySet(),
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** Per-model readiness for the settings UI: which formats are installed, what's missing. */
    data class ModelReadiness(
        val modelId: String,
        val label: String,
        val family: String,
        val nativeScale: Int,
        val readyFormats: List<UpscaleModelFormat>,
        val totalBytes: Long,
        val installedBytes: Long,
        val imported: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    @Volatile
    private var lastVerifyAtMs = 0L

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var models: List<UpscaleModelSpec> = emptyList()
    private var manifestVersionValue: Int = 0
    private var manifestError: String? = null

    private val importedDir = File(modelsDir, "imported").apply { mkdirs() }
    private val _status = MutableStateFlow(Status(State.NOT_INSTALLED))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        models = loadManifest()
        refresh()
        verifyInstalledThrottled()
    }

    fun catalog(): List<UpscaleModelSpec> = models

    fun manifestVersion(): Int = manifestVersionValue

    fun totalBytes(): Long = models.asSequence().flatMap { it.artifacts.asSequence() }.sumOf { it.size }

    fun isReady(): Boolean = readyModels().isNotEmpty()

    fun isModelReady(modelId: String, format: UpscaleModelFormat? = null): Boolean {
        val spec = findSpec(modelId) ?: return false
        val artifacts = artifactsFor(spec, format)
        return artifacts.isNotEmpty() && artifacts.all { hasValidSize(it) }
    }

    fun isFamilyReady(family: String): Boolean {
        val normalized = family.trim().uppercase()
        return allSpecs().asSequence()
            .filter { it.family.uppercase() == normalized }
            .any { spec -> formatsFor(spec).any { format -> isModelReady(spec.id, format) } }
    }

    fun installedBytes(): Long = allSpecs().asSequence()
        .flatMap { it.artifacts.asSequence() }
        .filter { hasValidSize(it) }
        .sumOf { file(it).length() }

    /** Ready-state summary per model, catalog + imports merged, stable order. */
    fun modelStates(): List<ModelReadiness> = allSpecs().map { spec ->
        ModelReadiness(
            modelId = spec.id,
            label = spec.label,
            family = spec.family,
            nativeScale = spec.nativeScale,
            readyFormats = formatsFor(spec).filter { isModelReady(spec.id, it) },
            totalBytes = spec.artifacts.sumOf { it.size },
            installedBytes = spec.artifacts.filter { hasValidSize(it) }.sumOf { file(it).length() },
            imported = spec.imported,
        )
    }

    fun resolvedModel(
        family: String,
        targetScale: Float,
        format: UpscaleModelFormat,
    ): ResolvedUpscaleModel? {
        val normalizedFamily = family.trim().uppercase()
        val wantedScale = targetScale.takeIf { it.isFinite() }?.coerceIn(1f, 4f) ?: 2f
        return allSpecs().asSequence()
            .filter { it.family.uppercase() == normalizedFamily }
            .filter { formatsFor(it).contains(format) }
            .sortedWith(
                compareBy<UpscaleModelSpec> { abs(it.nativeScale - wantedScale) }
                    .thenBy { it.nativeScale },
            )
            .mapNotNull { spec -> resolve(spec, format)?.takeIf { isResolvedReady(it) } }
            .firstOrNull()
    }

    /** Exact-id resolution for a policy-chosen model. */
    fun resolvedModelById(modelId: String, format: UpscaleModelFormat): ResolvedUpscaleModel? {
        val spec = findSpec(modelId) ?: return null
        return resolve(spec, format)?.takeIf { isResolvedReady(it) }
    }

    /**
     * Installable (ready) candidates for a family/format, nearest [wantedScale] first, lower
     * scale breaking ties — the ordering the old `resolvedModel` owned, exposed for the policy.
     */
    fun candidateModels(
        family: String,
        format: UpscaleModelFormat,
        wantedScale: Float,
    ): List<Pair<String, Int>> = allSpecs().asSequence()
        .filter { it.family.equals(family, ignoreCase = true) && format in formatsFor(it) }
        .sortedWith(compareBy({ abs(it.nativeScale - wantedScale) }, { it.nativeScale }))
        .map { it.id to it.nativeScale }
        .toList()

    fun resolvedModels(family: String, format: UpscaleModelFormat): List<ResolvedUpscaleModel> {
        val normalizedFamily = family.trim().uppercase()
        return allSpecs().asSequence()
            .filter { it.family.uppercase() == normalizedFamily }
            .mapNotNull { resolve(it, format)?.takeIf { m -> isResolvedReady(m) } }
            .toList()
    }

    fun fileFor(artifact: UpscaleArtifact): File = file(artifact)

    fun refresh() {
        val ready = readyModels()
        val installed = installedBytes()
        _status.value = when {
            manifestError != null -> Status(State.ERROR, error = manifestError)
            ready.isNotEmpty() -> Status(
                state = State.READY,
                downloadedBytes = installed,
                totalBytes = installed,
                readyModelIds = ready.mapTo(linkedSetOf()) { it.id },
            )
            else -> Status(State.NOT_INSTALLED, downloadedBytes = installed)
        }
    }

    fun startDownload(
        force: Boolean = false,
        modelId: String? = null,
        format: UpscaleModelFormat? = null,
    ) {
        if (downloadJob?.isActive == true) return
        val selected = selectSpecs(modelId, format)
        if (selected.isEmpty()) {
            _status.value = Status(State.ERROR, error = "No upscaler model matches the selected backend")
            return
        }
        downloadJob = scope.launch {
            if (force) clearModelFiles(selected)
            val toFetch = selected.flatMap { spec -> artifactsFor(spec, format) }
                .filterNot { hasValidSize(it) }
            if (toFetch.isEmpty()) {
                verifyInstalledThrottled()
                return@launch
            }

            val total = toFetch.sumOf { it.size }
            var completed = 0L
            _status.value = Status(State.DOWNLOADING, downloadedBytes = 0L, totalBytes = total)
            try {
                for (artifact in toFetch) {
                    if (!isActive) return@launch
                    _status.value = _status.value.copy(currentFile = artifact.name)
                    downloadSingle(artifact) { delta ->
                        completed += delta
                        _status.value = _status.value.copy(
                            downloadedBytes = completed,
                            totalBytes = total,
                        )
                    }
                }
                verifyInstalled()
            } catch (e: CancellationException) {
                refresh()
            } catch (e: Exception) {
                _status.value = Status(
                    State.ERROR,
                    downloadedBytes = completed,
                    totalBytes = total,
                    error = e.message ?: "Unknown error",
                    readyModelIds = readyModels().mapTo(linkedSetOf()) { it.id },
                )
            }
        }
    }

    /**
     * Fetches the models a native enable needs, exactly once per trigger. Called when the mode
     * or family moves to something that requires weights; a no-op when everything the family can
     * use is already installed or a download is running. An unknown family downloads everything.
     */
    fun maybeAutoDownload(family: String? = null) {
        val targets = selectSpecs(modelId = null, format = null, family = family)
        val missing = targets.flatMap { spec -> artifactsFor(spec, null) }.filterNot { hasValidSize(it) }
        if (missing.isEmpty()) return
        if (downloadJob?.isActive == true) return
        startDownload(modelId = null, format = null)
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        runCatching { activeCall?.cancel() }
        downloadJob = null
        activeCall = null
        refresh()
    }

    fun clearModels(modelId: String? = null, format: UpscaleModelFormat? = null) {
        downloadJob?.cancel()
        runCatching { activeCall?.cancel() }
        downloadJob = null
        activeCall = null
        clearModelFiles(selectSpecs(modelId, format))
        refresh()
    }

    // ---- user-imported ONNX ----

    /** Outcome of [importOnnx]. */
    sealed interface ImportResult {
        data class Ok(val modelId: String) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    /**
     * Imports a user-supplied ONNX model. Validation is a real probe: the file is written under a
     * temp name, opened by an actual ORT session, and only promoted to `imported-<name>` when the
     * session loads — a file ORT cannot read is never offered to the policy.
     */
    fun importOnnx(name: String, bytes: ByteArray): ImportResult {
        val safeName = name.trim().removeSuffix(".onnx")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        if (safeName.isBlank()) return ImportResult.Rejected("Invalid model name")
        if (bytes.size !in MIN_ARTIFACT_BYTES..MAX_IMPORT_BYTES) {
            return ImportResult.Rejected("Model must be between 256 B and ${MAX_IMPORT_BYTES / (1024 * 1024)} MB")
        }
        val tmp = File(importedDir, "$safeName.importing")
        val target = File(importedDir, "imported-$safeName.onnx")
        return try {
            tmp.writeBytes(bytes)
            // The probe: if ORT cannot open it, the import never happens.
            val probe = ortProbe(tmp.absolutePath)
            if (probe == null) {
                tmp.delete()
                return ImportResult.Rejected("ONNX Runtime could not load this model")
            }
            runCatching { probe.close() }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            logcat(LogPriority.INFO) { "Upscaler model imported: ${target.name} (${bytes.size} B)" }
            importedCache = null
            refresh()
            ImportResult.Ok(target.name.removeSuffix(".onnx"))
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            ImportResult.Rejected(e.message ?: "Import failed")
        }
    }

    fun deleteImported(modelId: String) {
        val spec = findSpec(modelId) ?: return
        if (!spec.imported) return
        spec.artifacts.forEach { runCatching { file(it).delete() } }
        importedCache = null
        refresh()
    }

    @Volatile
    private var importedCache: List<UpscaleModelSpec>? = null

    private fun importedSpecs(): List<UpscaleModelSpec> {
        importedCache?.let { return it }
        val specs = importedDir.listFiles()
            ?.filter { it.isFile && it.extension == "onnx" }
            ?.map { file ->
                UpscaleModelSpec(
                    id = file.name.removeSuffix(".onnx"),
                    family = FAMILY_IMPORTED,
                    label = file.name.removeSuffix(".onnx").removePrefix("imported-"),
                    nativeScale = 2, // unknown; the policy's scale target rescales afterwards
                    outputOrder = 0,
                    artifacts = listOf(
                        UpscaleArtifact(
                            format = "ONNX",
                            role = "model",
                            // Relative to modelsDir so hasValidSize/file() resolve like manifest artifacts.
                            name = "imported/${file.name}",
                            url = "",
                            size = file.length(),
                            sha256 = "",
                        ),
                    ),
                    imported = true,
                )
            }
            .orEmpty()
        importedCache = specs
        return specs
    }

    private fun allSpecs(): List<UpscaleModelSpec> = models + importedSpecs()

    private fun findSpec(modelId: String): UpscaleModelSpec? =
        allSpecs().firstOrNull { it.id == modelId }

    private fun loadManifest(): List<UpscaleModelSpec> {
        val raw = runCatching {
            manifestAssetReader(MANIFEST_ASSET)?.let { String(it) }
        }.getOrNull()
        if (raw == null) {
            manifestError = "Unable to read $MANIFEST_ASSET"
            return emptyList()
        }
        return runCatching {
            val manifest = json.decodeFromString<UpscaleManifest>(raw)
            manifestVersionValue = manifest.version
            require(manifest.models.size in 1..MAX_MANIFEST_MODELS)
            val valid = manifest.models.filter(::validSpec)
            require(valid.size == manifest.models.size)
            manifestError = null
            valid
        }.getOrElse {
            manifestError = "Invalid upscaler model manifest: ${it.message ?: "unknown error"}"
            emptyList()
        }
    }

    private fun validSpec(spec: UpscaleModelSpec): Boolean {
        if (spec.id.isBlank() || spec.id.length > 96 || spec.family.isBlank() || spec.label.isBlank()) return false
        if (spec.nativeScale !in 1..4 || spec.tileSize !in 32..512 || spec.padding !in 0..64) return false
        if (spec.artifacts.isEmpty() || spec.artifacts.size > 8) return false
        if (spec.artifacts.any { !validArtifact(it) }) return false
        return formatsFor(spec).isNotEmpty()
    }

    private fun validArtifact(artifact: UpscaleArtifact): Boolean {
        if (artifact.format !in setOf("NCNN", "ONNX")) return false
        if (artifact.role.isBlank() || artifact.name.isBlank() || artifact.name.length > 160) return false
        if (artifact.name.contains("..") || artifact.name.contains('/') || artifact.name.contains('\\')) return false
        if (artifact.size !in MIN_ARTIFACT_BYTES..MAX_ARTIFACT_BYTES) return false
        if (!artifact.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) return false
        return isHttpsUrl(artifact.url)
    }

    private fun isHttpsUrl(raw: String): Boolean = runCatching {
        val uri = java.net.URI(raw)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && raw.length <= 2048
    }.getOrDefault(false)

    private fun formatsFor(spec: UpscaleModelSpec): List<UpscaleModelFormat> = buildList {
        if (spec.artifacts.any { it.format == "NCNN" }) add(UpscaleModelFormat.NCNN)
        if (spec.artifacts.any { it.format == "ONNX" }) add(UpscaleModelFormat.ONNX)
    }

    private fun artifactsFor(
        spec: UpscaleModelSpec,
        format: UpscaleModelFormat?,
    ): List<UpscaleArtifact> = spec.artifacts.filter { artifact ->
        format == null || artifact.format == format.name
    }

    private fun resolve(spec: UpscaleModelSpec, format: UpscaleModelFormat): ResolvedUpscaleModel? {
        if (format !in formatsFor(spec)) return null
        return ResolvedUpscaleModel(spec, format, artifactsFor(spec, format))
    }

    private fun selectSpecs(
        modelId: String?,
        format: UpscaleModelFormat?,
        family: String? = null,
    ): List<UpscaleModelSpec> = models.filter { spec ->
        (modelId == null || spec.id == modelId) &&
            (format == null || format in formatsFor(spec)) &&
            (family == null || spec.family.equals(family, ignoreCase = true))
    }

    private fun readyModels(): List<ResolvedUpscaleModel> = models.flatMap { spec ->
        formatsFor(spec).mapNotNull { format -> resolve(spec, format)?.takeIf { isResolvedReady(it) } }
    }

    private fun isResolvedReady(model: ResolvedUpscaleModel): Boolean =
        model.artifacts.isNotEmpty() && model.artifacts.all { hasValidSize(it) }

    private fun file(artifact: UpscaleArtifact): File = File(modelsDir, artifact.name)

    private fun hasValidSize(artifact: UpscaleArtifact): Boolean = file(artifact).let {
        it.isFile && it.length() == artifact.size
    }

    /**
     * Size-checks every artifact and hashes the installed ones, deleting any that fail.
     *
     * Size is authoritative for readiness ([hasValidSize]) because a truncated download fails it,
     * so the hash pass only has to catch a *size-correct* but corrupt file - silent bit rot, or a
     * replacement that was never verified. That is rare enough that re-hashing up to 512 MB on
     * every app start is not worth it, which is what [verifyInstalledThrottled] exists to avoid.
     */
    private fun verifyInstalled() {
        scope.launch {
            val corrupt = mutableListOf<String>()
            for (artifact in models.flatMap { it.artifacts }) {
                if (!hasValidSize(artifact)) continue
                if (!verifyHash(artifact)) {
                    corrupt += artifact.name
                    runCatching { file(artifact).delete() }
                }
            }
            if (corrupt.isNotEmpty()) {
                logcat(LogPriority.WARN) { "Upscaler model files missing/corrupt: $corrupt" }
            }
            refresh()
        }
    }

    /**
     * Re-verifies hashes at most once per [VERIFY_INTERVAL_MS] per process, but always after a
     * completed download so a corrupt transfer is caught before it is used. The init-time pass is
     * the expensive one - up to 512 MB of SHA-256 across all installed artifacts - and it is what
     * would otherwise run on every cold start.
     */
    private fun verifyInstalledThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastVerifyAtMs < VERIFY_INTERVAL_MS) return
        lastVerifyAtMs = now
        verifyInstalled()
    }

    private fun verifyHash(artifact: UpscaleArtifact): Boolean = runCatching {
        sha256(file(artifact)).equals(artifact.sha256, ignoreCase = true)
    }.getOrDefault(false)

    private fun clearModelFiles(specs: List<UpscaleModelSpec>) {
        specs.flatMap { it.artifacts }.forEach { artifact ->
            runCatching { file(artifact).delete() }
            runCatching { File(modelsDir, "${artifact.name}.tmp").delete() }
        }
    }

    private fun downloadSingle(artifact: UpscaleArtifact, onBytes: (Long) -> Unit) {
        if (!isHttpsUrl(artifact.url)) throw IllegalStateException("Invalid model URL")
        if (artifact.name.contains("..") || artifact.name.contains('/')) throw IllegalStateException("Invalid model name")
        val downloadClient = client.newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val call = downloadClient.newCall(Request.Builder().url(artifact.url).get().build())
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for ${artifact.name}")
                val body = response.body
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                if (contentLength != null && contentLength != artifact.size) {
                    throw IllegalStateException("Content-Length mismatch for ${artifact.name}")
                }
                val temp = File(modelsDir, "${artifact.name}.tmp")
                var totalRead = 0L
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalRead += read
                                if (totalRead > artifact.size) {
                                    throw IllegalStateException("Download exceeded expected size for ${artifact.name}")
                                }
                                output.write(buffer, 0, read)
                                onBytes(read.toLong())
                            }
                            output.fd.sync()
                        }
                    }
                    if (temp.length() != artifact.size) {
                        throw IllegalStateException("Size mismatch for ${artifact.name}")
                    }
                    if (!sha256(temp).equals(artifact.sha256, ignoreCase = true)) {
                        throw IllegalStateException("sha256 mismatch for ${artifact.name}")
                    }
                    val target = file(artifact)
                    if (target.exists() && !target.delete()) throw IllegalStateException("Cannot replace ${artifact.name}")
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        if (target.length() != artifact.size) throw IllegalStateException("Copy failed for ${artifact.name}")
                    }
                } finally {
                    if (temp.exists()) runCatching { temp.delete() }
                }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FAMILY_IMPORTED = "IMPORTED"
        private const val MANIFEST_ASSET = "upscale_models.json"
        private const val MAX_MANIFEST_MODELS = 32
        private const val MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L
        private const val MIN_ARTIFACT_BYTES = 256L
        private const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L
        private const val VERIFY_INTERVAL_MS = 6L * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
