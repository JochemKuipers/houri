package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun getLocalLlmGroup(): Preference.PreferenceGroup? {
    val prefs = remember { globalAppGraph.translationPreferences }
    val manager = remember { globalAppGraph.localLlmManager }
    val downloadManager = remember { globalAppGraph.localLlmDownloadManager }
    val context = LocalContext.current
    val enabled by prefs.enabled().collectAsState()
    val provider by prefs.provider().collectAsState()
    val localEnabled = enabled && provider == "local"
    // KMK --> The local group is irrelevant unless the local provider is selected.
    if (!localEnabled) return null
    // KMK <--
    val status by downloadManager.status.collectAsState()
    val running by manager.running.collectAsState()
    val loading by manager.loading.collectAsState()
    val localModelKey by prefs.localModel().collectAsState()
    val autoStart by prefs.localLlmAutoStart().collectAsState()
    val entryNavigator = LocalBackStack.current
    var importTick by remember { mutableStateOf(0) }
    val importing by manager.importing.collectAsState()
    val importedModels = remember(importTick, status) { manager.importedModels() }
    val model = remember(localModelKey, provider, importTick, status) { manager.resolveModel() }
    val best = remember { exh.yakuyomi.LocalLlmCatalog.bestForDevice(exh.yakuyomi.DeviceMemory.totalRamBytes(context)) }
    val runtimeBundled = manager.isRuntimeAvailable()
    val modelReady = remember(status, model) {
        model != null && downloadManager.isDownloaded(model)
    }

    val entries = remember(importTick, status) {
        val base = persistentMapOf<String, String>("" to "Auto — best for this device")
        val catalog = exh.yakuyomi.LocalLlmCatalog.allModels.associate { it.id to it.displayName }
        val imported = importedModels.associate { it.id to it.displayName }
        persistentMapOf(*((base + catalog + imported).toList()).toTypedArray())
    }

    // KMK --> Custom GGUF import: background copy (never freezes the UI for multi-GB files),
    // original filename preserved, re-imports detected and just re-selected.
    // KMK <--
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            manager.importGguf(uri) { result, error ->
                if (result != null) {
                    importTick = importTick + 1
                    if (result.duplicate) {
                        context.toast("Already imported — switched to ${result.model.displayName}")
                    } else {
                        context.toast("Imported ${result.model.displayName}")
                    }
                } else {
                    context.toast("Failed to import GGUF: ${error ?: "unknown error"}")
                }
            }
        }
    }

    val actionTitle = when (status.state) {
        exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING -> "Cancel download"
        exh.yakuyomi.LocalLlmDownloadManager.State.READY -> "Redownload"
        else -> "Download model"
    }

    val modelSubtitle = when (val m = model) {
        null -> "No model fits this device"
        else -> {
            val tier = when (m.qualityTier) {
                5 -> "Best"
                4 -> "High"
                3 -> "Good"
                else -> "Basic"
            }
            val vision = if (m.supportsVision) " · vision" else ""
            val finetune = if (m.isTranslationFinetune) " · TL finetune" else ""
            "$tier · ${m.paramsB}$vision$finetune · ${m.sizeBytes / (1024 * 1024)} MB"
        }
    }

    val engineState = when {
        running -> "Running — ${model?.displayName ?: "unknown model"}"
        loading -> "Starting… — ${model?.displayName ?: "selected model"}"
        modelReady -> "Stopped — ${model?.displayName ?: "auto"} ready"
        else -> "Stopped — no model downloaded yet"
    }

    return Preference.PreferenceGroup(
        title = "Local (On-device LLM)",
        // KMK --> downloading multi-GB LLM weights is pointless on low-RAM devices.
        ramGated = true,
        // KMK <--
        preferenceItems = buildList {
            add(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.localModel(),
                    entries = entries,
                    title = "Local model",
                    subtitleProvider = { v, _ ->
                        when {
                            v.isNullOrBlank() -> "Auto — ${best?.displayName ?: "none"}${if (!runtimeBundled) " · Runtime not bundled" else ""}"
                            else -> modelSubtitle
                        }
                    },
                    enabled = localEnabled,
                ),
            )
            if (model != null) {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_yakuyomi_llm_advanced),
                        subtitle = "Sampling, context and threads for ${model.displayName}",
                        onClick = { entryNavigator.add(SettingsYakuyomiLlmAdvancedRoute(model)) },
                        enabled = localEnabled,
                    ),
                )
            }
            add(
                Preference.PreferenceItem.TextPreference(
                    title = if (importing) "Importing GGUF…" else "Import GGUF from device",
                    subtitle = when {
                        importing -> "Copying…"
                        importedModels.isEmpty() -> "Pick .gguf file"
                        else -> importedModels.joinToString(", ") { it.displayName }
                    },
                    onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = localEnabled && !importing,
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = if (running) "Stop engine" else "Start engine",
                    subtitle = engineState,
                    onClick = { if (running) manager.stop() else manager.start() },
                    enabled = localEnabled && modelReady,
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.localLlmAutoStart(),
                    title = "Start engine on app launch",
                    subtitle = "Preload so first translation is instant",
                    enabled = localEnabled && modelReady,
                ),
            )
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = "Download",
                    content = {
                        Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)) {
                            when (status.state) {
                                exh.yakuyomi.LocalLlmDownloadManager.State.READY -> {
                                    Text(text = "Installed: ${status.downloadedBytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodyMedium)
                                }
                                exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING -> {
                                    val percent = (status.progress * 100).toInt()
                                    val mb = status.downloadedBytes / (1024 * 1024)
                                    val totalMb = (status.totalBytes / (1024 * 1024)).coerceAtLeast(mb)
                                    val speedMb = status.speedBytesPerSecond / (1024 * 1024)
                                    Text(
                                        text = "Downloading $percent% — $mb/$totalMb MB" + if (speedMb > 0) " · $speedMb MB/s" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (status.etaSeconds > 0) {
                                        Text(text = "About ${formatEta(status.etaSeconds)} left", style = MaterialTheme.typography.bodySmall)
                                    }
                                    LinearProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
                                }
                                exh.yakuyomi.LocalLlmDownloadManager.State.ERROR -> {
                                    Text(text = status.error ?: "Download failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                }
                                else -> Text(text = "Not installed", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    },
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = actionTitle,
                    onClick = {
                        when (status.state) {
                            exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING -> manager.cancelDownload()
                            else -> manager.startDownload()
                        }
                    },
                    enabled = localEnabled && manager.isRuntimeAvailable(),
                ),
            )
            if (model != null || importedModels.isNotEmpty() || status.state == exh.yakuyomi.LocalLlmDownloadManager.State.READY) {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = "Clear local model",
                        subtitle = if (model?.isCustom == true) "Delete imported GGUF" else "Remove downloaded files",
                        onClick = {
                            manager.clearModel()
                            importTick = importTick + 1
                            context.toast("Local model cleared")
                        },
                        enabled = localEnabled && status.state != exh.yakuyomi.LocalLlmDownloadManager.State.DOWNLOADING,
                    ),
                )
            }
        }.toPersistentList(),
    )
}

@Composable
internal fun getModelGroup(modelManager: exh.yakuyomi.ModelManager): Preference.PreferenceGroup {
    val status by modelManager.status.collectAsState()
    val context = LocalContext.current
    // Collected, not read: the About easter egg flips the RAM gate at runtime and this
    // drives whether the master switch is usable.
    val ramGateDisabled by eu.kanade.presentation.more.settings.RamGateState.isDisabled.collectAsState()
    val lowRam = !ramGateDisabled && !exh.yakuyomi.DeviceMemory.hasSufficientRam(context, exh.yakuyomi.DeviceMemory.MTL_MIN_RAM_BYTES)
    val modelsClearedText = stringResource(KMR.strings.mtl_models_cleared)
    val actionTitle = when (status.state) {
        exh.yakuyomi.ModelManager.State.DOWNLOADING -> stringResource(KMR.strings.mtl_models_cancel)
        exh.yakuyomi.ModelManager.State.READY -> stringResource(KMR.strings.mtl_models_redownload)
        else -> stringResource(KMR.strings.mtl_models_download)
    }
    // KMK --> Multilingual OCR add-on (issue #54): older installs only downloaded the bundled
    // int8 OCR, which reads CJK scripts but not English. Surface the optional PP-OCRv5 model
    // whenever the engine is READY without it — startDownload() fetches only the files that
    // fail verification, so the row pulls just this ~16 MB add-on, never the whole pack.
    val missingOcrAddon = status.state == exh.yakuyomi.ModelManager.State.READY &&
        !modelManager.isMultilingualOcrInstalled()
    // KMK <--
    return Preference.PreferenceGroup(
        title = stringResource(KMR.strings.mtl_models_title),
        // KMK --> on-device model weights need both the MTL engine and enough RAM.
        mtlOnly = true,
        ramGated = true,
        // KMK <--
        // buildList (same idiom as the local-model group): the multilingual OCR add-on row is
        // only appended when the engine is READY without it — startDownload() fetches just the
        // files that fail verification, so the row downloads only the ~16 MB add-on.
        preferenceItems = buildList {
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(KMR.strings.mtl_models_title),
                    content = {
                        Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp)) {
                            when (status.state) {
                                exh.yakuyomi.ModelManager.State.READY -> {
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_ready, status.downloadedBytes / (1024 * 1024)),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                exh.yakuyomi.ModelManager.State.DOWNLOADING -> {
                                    val percent = (status.progress * 100).toInt()
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_downloading, percent),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                    // Padding on both sides so the bar doesn't touch the screen edges.
                                    LinearProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                    if (!status.currentFile.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.padding(vertical = 4.dp))
                                        Text(
                                            text = "File: ${status.currentFile}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                exh.yakuyomi.ModelManager.State.ERROR -> {
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_error, status.error ?: "unknown"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                else -> {
                                    Text(
                                        text = stringResource(KMR.strings.mtl_models_missing),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    },
                ),
            )
            add(
                Preference.PreferenceItem.TextPreference(
                    title = actionTitle,
                    onClick = {
                        when (status.state) {
                            exh.yakuyomi.ModelManager.State.DOWNLOADING -> modelManager.cancelDownload()
                            exh.yakuyomi.ModelManager.State.READY -> modelManager.startDownload(force = true)
                            else -> modelManager.startDownload()
                        }
                    },
                    enabled = !lowRam,
                ),
            )
            // KMK --> #54: multilingual OCR add-on (PP-OCRv5) — older installs only have the
            // bundled int8 model, which reads CJK but not English, and there was no way to
            // discover the optional upgrade without re-downloading everything.
            if (missingOcrAddon) {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.mtl_models_ocr_addon),
                        subtitle = stringResource(KMR.strings.mtl_models_ocr_addon_summary),
                        onClick = { modelManager.startDownload() },
                        enabled = !lowRam && status.state != exh.yakuyomi.ModelManager.State.DOWNLOADING,
                    ),
                )
            }
            // KMK <--
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.mtl_models_clear),
                    subtitle = stringResource(KMR.strings.mtl_models_clear_summary),
                    onClick = {
                        modelManager.clearModels()
                        context.toast(modelsClearedText)
                    },
                    enabled = !lowRam && status.state != exh.yakuyomi.ModelManager.State.DOWNLOADING,
                ),
            )
        }.toPersistentList(),
    )
}
