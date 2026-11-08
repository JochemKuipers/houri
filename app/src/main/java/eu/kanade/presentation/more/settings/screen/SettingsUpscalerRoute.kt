package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.collectAsState as flowCollectAsState

/**
 * The unified upscaling screen: modes, accelerator, catalog, downloads, imports, per-category
 * defaults, prefetch, live status. Backed entirely by the spine — every row reads config or the
 * status hub, never the old engine's private surface.
 */
@Serializable
data object SettingsUpscalerRoute : NavKey, SearchableRoute {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_upscale_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.upscalePreferences }
        val controller = remember { globalAppGraph.upscaleController }
        val store = remember { globalAppGraph.upscaleMangaStore }
        val models = remember { controller.models }

        val enabled by prefs.enabled().collectAsState()
        val mode by prefs.mode().collectAsState()
        val simpleAlgo by prefs.simpleAlgo().collectAsState()
        val preset by prefs.preset().collectAsState()
        val backend by prefs.backend().collectAsState()
        val model by prefs.model().collectAsState()
        val factor by prefs.upscaleFactor().collectAsState()
        val cacheEnabled by prefs.cacheEnabled().collectAsState()
        val prefetchDepth by prefs.prefetchDepth().collectAsState()
        val tileRefinement by prefs.tileRefinement().collectAsState()
        val defaultForNew by prefs.defaultForNewEntries().collectAsState()

        val modelStatus by models.status.flowCollectAsState()
        val hubStatus by controller.status.totals.flowCollectAsState()
        val backendStatuses = remember(controller) {
            controller.backends.associate { it.id to it.readiness }
        }
        // Recompose when any backend's readiness flips.
        val readinessTick = backendStatuses.values
            .map { it.collectAsState().value }
            .joinToString { it::class.simpleName.orEmpty() }

        var cacheRefreshTick by remember { mutableIntStateOf(0) }
        val cacheBytes = remember(cacheEnabled, enabled, cacheRefreshTick, hubStatus.served) {
            runCatching { controller.cacheSizeBytes() }.getOrDefault(0L)
        }
        val cacheCount = remember(cacheEnabled, enabled, cacheRefreshTick, hubStatus.served) {
            runCatching { controller.cacheFileCount() }.getOrDefault(0)
        }
        val cacheSummary = remember(cacheBytes, cacheCount) {
            formatCacheSummary(cacheBytes, cacheCount)
        }

        val categoriesState = remember { mutableStateOf<List<Category>>(emptyList()) }
        LaunchedEffect(Unit) {
            categoriesState.value = runCatching { globalAppGraph.getCategories.await() }.getOrDefault(emptyList())
        }
        val categories = categoriesState.value
        val categoryVersion by store.categoryOverridesVersion.flowCollectAsState()
        val categoryStates = remember(categories, categoryVersion) {
            categories.map { it.id to store.categoryOverride(it.id) }
        }

        val scope = rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        var importError by remember { mutableStateOf<String?>(null) }
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        stream.copyTo(out, 64 * 1024)
                        out.toByteArray()
                    }
                    val name = uri.lastPathSegment ?: "model.onnx"
                    if (bytes == null) {
                        importError = "Could not read the selected file"
                    } else {
                        when (val result = models.importOnnx(name, bytes)) {
                            is upscale.model.UpscaleModelManager.ImportResult.Ok -> importError = null
                            is upscale.model.UpscaleModelManager.ImportResult.Rejected -> importError = result.reason
                        }
                    }
                } catch (e: Exception) {
                    importError = e.message ?: "Import failed"
                }
            }
        }

        val items = buildList {
            // ---- master ----
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.enabled(),
                    title = stringResource(KMR.strings.pref_upscale_enabled),
                    subtitle = if (enabled) {
                        stringResource(KMR.strings.pref_upscale_enabled_summary)
                    } else {
                        stringResource(KMR.strings.pref_upscale_disabled_summary)
                    },
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.defaultForNewEntries(),
                    title = stringResource(KMR.strings.pref_upscale_default_for_new),
                    subtitle = stringResource(KMR.strings.pref_upscale_default_for_new_summary),
                    enabled = enabled,
                ),
            )

            // ---- mode ----
            add(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.mode(),
                    entries = persistentMapOf(
                        "AUTO" to "Auto — native inference, falls back to Simple",
                        "NATIVE" to "Native — Real-CUGAN / Real-ESRGAN / Waifu2x",
                        "SIMPLE" to "Simple — resample only, no models",
                    ),
                    title = stringResource(KMR.strings.pref_upscale_mode),
                    subtitle = when (mode) {
                        "SIMPLE" -> "Simple — $simpleAlgo"
                        "NATIVE" -> "Native — $model • $backend"
                        else -> "Auto — native first, Simple as the last resort"
                    },
                    enabled = enabled,
                ),
            )
            if (mode == "SIMPLE") {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.simpleAlgo(),
                        entries = persistentMapOf(
                            "BICUBIC" to "Bicubic (smooth)",
                            "BILINEAR" to "Bilinear (balanced)",
                            "NEAREST" to "Nearest Neighbor (sharp/pixelated)",
                        ),
                        title = "Simple algorithm",
                        subtitle = simpleAlgo,
                        enabled = enabled,
                    ),
                )
            }

            // ---- native quality ----
            if (mode != "SIMPLE") {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.preset(),
                        entries = persistentMapOf(
                            "FAST" to stringResource(KMR.strings.pref_upscale_preset_fast) + " — 1.5x fixed, lowest memory",
                            "BALANCED" to stringResource(KMR.strings.pref_upscale_preset_balanced) + " — uses factor as-is",
                            "HIGH" to stringResource(KMR.strings.pref_upscale_preset_high) + " — factor x1.2 (max 4x)",
                        ),
                        title = stringResource(KMR.strings.pref_upscale_preset),
                        subtitle = preset,
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.backend(),
                        entries = persistentMapOf(
                            "AUTO" to "Auto (Vulkan → NPU → CPU)",
                            "VULKAN" to "Vulkan (GPU)",
                            "NPU" to "NPU (NNAPI via ONNX Runtime)",
                            "CPU" to "CPU (fallback)",
                        ),
                        title = stringResource(KMR.strings.pref_upscale_backend),
                        subtitle = backend + readinessSuffix(backendStatuses, backend),
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.model(),
                        entries = persistentMapOf(
                            "REAL_CUGAN" to "Real-CUGAN (anime/manga, denoise)",
                            "REAL_ESRGAN" to "Real-ESRGAN (general, sharper)",
                            "WAIFU2X" to "Waifu2x (legacy, light)",
                            "IMPORTED" to "Imported (your own ONNX models)",
                        ),
                        title = stringResource(KMR.strings.pref_upscale_model),
                        subtitle = model,
                        enabled = enabled,
                    ),
                )
            }

            // ---- factor ----
            add(
                Preference.PreferenceItem.SliderPreference(
                    value = (factor * 10).toInt().coerceIn(10, 40),
                    title = stringResource(KMR.strings.pref_upscale_factor),
                    subtitle = if (factor < 1.02f) "${factor}x — near-identity, upscaling skipped" else "${factor}x",
                    valueString = String.format("%.1fx", factor),
                    valueRange = 10..40,
                    steps = 29,
                    enabled = enabled,
                    onValueChanged = { v -> prefs.upscaleFactor().set(v / 10f) },
                ),
            )

            // ---- prefetch + webgpu tile refinement ----
            add(
                Preference.PreferenceItem.SliderPreference(
                    value = prefetchDepth,
                    title = stringResource(KMR.strings.pref_upscale_prefetch),
                    subtitle = if (prefetchDepth == 0) {
                        stringResource(KMR.strings.pref_upscale_prefetch_off)
                    } else {
                        "Warms the cache for the next $prefetchDepth page(s)"
                    },
                    valueString = if (prefetchDepth == 0) "Off" else "$prefetchDepth",
                    valueRange = 0..5,
                    steps = 4,
                    enabled = enabled,
                    onValueChanged = { v -> prefs.prefetchDepth().set(v) },
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.tileRefinement(),
                    title = stringResource(KMR.strings.pref_upscale_tile_refinement),
                    subtitle = stringResource(KMR.strings.pref_upscale_tile_refinement_summary),
                    enabled = enabled,
                ),
            )

            // ---- cache ----
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.cacheEnabled(),
                    title = stringResource(KMR.strings.pref_upscale_cache),
                    subtitle = if (cacheEnabled) {
                        if (cacheBytes == 0L) stringResource(KMR.strings.pref_upscale_cache_summary) else cacheSummary
                    } else {
                        stringResource(KMR.strings.pref_upscale_cache_summary) + " (disabled)"
                    },
                    enabled = enabled,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_upscale_clear_cache),
                    subtitle = if (cacheBytes > 0) cacheSummary else stringResource(KMR.strings.pref_upscale_clear_cache_summary),
                    onClick = {
                        controller.clearCache()
                        cacheRefreshTick++
                    },
                    enabled = enabled && cacheEnabled,
                ),
            )

            // ---- live status ----
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(KMR.strings.pref_upscale_live_status),
                    content = {
                        Column(
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                        ) {
                            val totalsText = buildString {
                                append("Served ${hubStatus.served}")
                                if (hubStatus.servedFromCache > 0) append(" (${hubStatus.servedFromCache} from cache)")
                                if (hubStatus.fellBack > 0) append(" · fell back ${hubStatus.fellBack}")
                                if (hubStatus.failed > 0) append(" · failed ${hubStatus.failed}")
                                if (hubStatus.prefetched > 0) append(" · prefetched ${hubStatus.prefetched}")
                            }
                            Text(text = totalsText, style = MaterialTheme.typography.bodyMedium)
                            if (hubStatus.served > 0 && hubStatus.totalInferenceMs > 0) {
                                Text(
                                    text = "Average inference: ${hubStatus.totalInferenceMs / hubStatus.served} ms",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (readinessTick.isNotEmpty()) {
                                controller.backends.forEach { backend0 ->
                                    Text(
                                        text = "${backend0.displayName}: " + when (val r = backend0.readiness.value) {
                                            is upscale.core.Readiness.Ready -> "ready"
                                            is upscale.core.Readiness.Unavailable -> "unavailable — ${r.reason}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    },
                ),
            )

            // ---- model catalog / downloads / imports ----
            if (mode != "SIMPLE") {
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(KMR.strings.pref_upscale_models_group),
                        content = {
                            Column(
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                            ) {
                                when (modelStatus.state) {
                                    upscale.model.UpscaleModelManager.State.READY -> {
                                        Text(
                                            text = "Models ready — ${modelStatus.downloadedBytes / (1024 * 1024)} MB",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    upscale.model.UpscaleModelManager.State.DOWNLOADING -> {
                                        val percent = (modelStatus.progress * 100).toInt()
                                        Text(
                                            text = "Downloading $percent% — " +
                                                "${modelStatus.downloadedBytes / (1024 * 1024)}/${modelStatus.totalBytes / (1024 * 1024)} MB",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (!modelStatus.currentFile.isNullOrBlank()) {
                                            Text(text = "File: ${modelStatus.currentFile}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        LinearProgressIndicator(
                                            progress = { modelStatus.progress },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        )
                                    }
                                    upscale.model.UpscaleModelManager.State.ERROR -> {
                                        Text(
                                            text = modelStatus.error ?: "Download failed",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "Models not installed — download to enable native inference",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = "Manifest total: ~${models.totalBytes() / (1024L * 1024L)} MB",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                importError?.let {
                                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                models.modelStates().forEach { readiness0 ->
                                    Text(
                                        text = buildString {
                                            append(readiness0.label)
                                            append(" — ")
                                            append(if (readiness0.readyFormats.isEmpty()) "not installed" else readiness0.readyFormats.joinToString("/") { it.name })
                                            append(" (${readiness0.installedBytes / (1024 * 1024)}/${readiness0.totalBytes / (1024 * 1024)} MB)")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                    ),
                )
                val modelActionTitle = when (modelStatus.state) {
                    upscale.model.UpscaleModelManager.State.DOWNLOADING -> "Cancel download"
                    upscale.model.UpscaleModelManager.State.READY -> "Redownload models"
                    else -> "Download models"
                }
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = modelActionTitle,
                        subtitle = when (modelStatus.state) {
                            upscale.model.UpscaleModelManager.State.DOWNLOADING -> "Downloading ${modelStatus.currentFile ?: ""}"
                            upscale.model.UpscaleModelManager.State.ERROR -> modelStatus.error ?: "Tap to retry"
                            else -> null
                        },
                        onClick = {
                            when (modelStatus.state) {
                                upscale.model.UpscaleModelManager.State.DOWNLOADING -> models.cancelDownload()
                                upscale.model.UpscaleModelManager.State.READY -> models.startDownload(force = true)
                                else -> models.startDownload()
                            }
                        },
                        enabled = enabled,
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_upscale_clear_models),
                        subtitle = stringResource(KMR.strings.pref_upscale_clear_models_summary),
                        onClick = { models.clearModels() },
                        enabled = enabled &&
                            modelStatus.state != upscale.model.UpscaleModelManager.State.DOWNLOADING,
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_upscale_import_onnx),
                        subtitle = stringResource(KMR.strings.pref_upscale_import_onnx_summary),
                        onClick = { importLauncher.launch("*/*") },
                        enabled = enabled,
                    ),
                )
            }

            // ---- per-category defaults ----
            if (categories.isNotEmpty()) {
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(KMR.strings.pref_upscale_category_defaults),
                        content = {
                            Column(
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
                            ) {
                                Text(
                                    text = "Per-manga toggles override these; entries with no stored " +
                                        "toggle inherit their category's choice",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                categoryStates.forEach { (categoryId, state) ->
                                    Text(
                                        text = buildString {
                                            append(categories.firstOrNull { it.id == categoryId }?.name ?: "Category $categoryId")
                                            append(": ")
                                            append(
                                                when (state) {
                                                    true -> "on"
                                                    false -> "off"
                                                    null -> "no override"
                                                },
                                            )
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        },
                    ),
                )
                categories.forEach { category ->
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = category.name,
                            subtitle = when (categoryStates.firstOrNull { it.first == category.id }?.second) {
                                true -> "Upscale everything in this category"
                                false -> "Never upscale in this category"
                                null -> "Tap to cycle: no override → on → off"
                            },
                            onClick = {
                                val current = store.categoryOverride(category.id)
                                store.setCategoryOverride(
                                    category.id,
                                    when (current) {
                                        null -> true
                                        true -> false
                                        false -> null
                                    },
                                )
                            },
                            enabled = enabled,
                        ),
                    )
                }
            }

            add(
                Preference.PreferenceItem.InfoPreference(
                    title = if (enabled) {
                        stringResource(KMR.strings.pref_upscale_info_per_manga)
                    } else {
                        stringResource(KMR.strings.pref_upscale_info_disabled)
                    },
                ),
            )
        }
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_upscale_title),
                preferenceItems = items.toImmutableList(),
            ),
        )
    }

    private fun readinessSuffix(
        statuses: Map<String, kotlinx.coroutines.flow.StateFlow<upscale.core.Readiness>>,
        backend: String,
    ): String = when (backend) {
        "AUTO" -> ""
        else -> statuses[
            when (backend) {
                "VULKAN" -> "ncnn"
                "NPU" -> "ort"
                else -> "simple"
            },
        ]?.value?.let { readiness0 ->
            when (readiness0) {
                is upscale.core.Readiness.Ready -> " (ready)"
                is upscale.core.Readiness.Unavailable -> " (unavailable)"
            }
        } ?: ""
    }

    private fun formatCacheSummary(bytes: Long, count: Int): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        bytes > 0 -> "${bytes / 1024} KB"
        else -> "empty"
    } + " · $count page(s)"
}
