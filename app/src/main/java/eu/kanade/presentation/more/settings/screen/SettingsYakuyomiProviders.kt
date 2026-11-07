package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceDependency
import eu.kanade.tachiyomi.util.system.toast
import exh.yakuyomi.ModelCatalog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun getProviderGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
    val enabled by prefs.enabled().collectAsState()
    val provider by prefs.provider().collectAsState()
    val apiKey by prefs.apiKeyForProvider(provider).collectAsState()
    val baseUrl by prefs.customBaseUrl().collectAsState()
    val model by prefs.modelForProvider(provider).collectAsState()
    val geminiNanoEnabled by prefs.geminiNanoEnabled().collectAsState()
    val legacyApiKey by prefs.apiKey().collectAsState()
    val legacyModel by prefs.model().collectAsState()
    var migratedProviders by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(provider, legacyApiKey, legacyModel) {
        if (provider in migratedProviders) return@LaunchedEffect
        val perKeyBlank = prefs.apiKeyForProvider(provider).get().isBlank()
        val perModelBlank = prefs.modelForProvider(provider).get().isBlank()
        if (perKeyBlank && legacyApiKey.isNotBlank()) {
            prefs.apiKeyForProvider(provider).set(legacyApiKey)
        }
        if (perModelBlank && legacyModel.isNotBlank()) {
            prefs.modelForProvider(provider).set(legacyModel)
        }
        migratedProviders = migratedProviders + provider
    }

    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelFetchFailed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(provider, apiKey, baseUrl, refreshTick) {
        val currentProvider = provider
        val endpoint = ModelCatalog.modelsEndpoint(currentProvider, baseUrl)
        if (endpoint == null || currentProvider == "local") {
            fetchedModels = emptyList()
            modelFetchFailed = false
            fetchingModels = false
            return@LaunchedEffect
        }
        if (currentProvider.equals("gemini", ignoreCase = true) && apiKey.isBlank()) {
            fetchedModels = emptyList()
            modelFetchFailed = false
            fetchingModels = false
            return@LaunchedEffect
        }
        if (currentProvider == "custom_openai" && baseUrl.isBlank()) {
            fetchedModels = emptyList()
            modelFetchFailed = false
            fetchingModels = false
            return@LaunchedEffect
        }
        fetchingModels = true
        modelFetchFailed = false
        val models = withIOContext {
            runCatching {
                ModelCatalog.fetchModels(currentProvider, apiKey, baseUrl, globalAppGraph.networkHelper.client)
            }.getOrDefault(emptyList())
        }
        if (currentProvider != provider) return@LaunchedEffect
        if (models.isNotEmpty()) {
            fetchedModels = models
            modelFetchFailed = false
        } else {
            fetchedModels = emptyList()
            modelFetchFailed = true
        }
        fetchingModels = false
    }

    val entries = remember(fetchedModels, provider, model) {
        val base = ModelCatalog.fallbackModels[provider].orEmpty()
        val all = (fetchedModels + base + listOfNotNull(model.takeIf { it.isNotBlank() })).distinct().sorted()
        persistentMapOf(*all.map { it to it }.toTypedArray())
    }

    val apiKeySubtitle = when {
        apiKey.isBlank() -> stringResource(KMR.strings.pref_yakuyomi_api_key_missing)
        else -> apiKey.take(4) + "••••••••"
    }

    val isLocalProvider = provider == "local"
    val isMangatranslatorProvider = provider == "mangatranslator"
    // KMK --> no-MTL builds only ever translate via MangaTranslator (stub errors out
    // every other provider), so offering them would show options that can never work.
    val isNomtl = eu.kanade.tachiyomi.BuildConfig.IS_NOMTL
    // KMK <--

    // KMK --> auto-enable when the user *switches to* MangaTranslator (it is the only
    // provider that can translate without the MTL engine), but never on plain screen
    // entry — otherwise the toggle can never stay off.
    var lastProvider by remember { mutableStateOf(provider) }
    LaunchedEffect(provider) {
        if (isMangatranslatorProvider && lastProvider != "mangatranslator" && !prefs.enabled().get()) {
            prefs.enabled().set(true)
        }
        lastProvider = provider
    }

    return Preference.PreferenceGroup(
        title = stringResource(KMR.strings.pref_yakuyomi_provider),
        preferenceItems = buildList {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.geminiNanoEnabled(),
                    title = stringResource(KMR.strings.pref_yakuyomi_gemini_nano),
                    subtitle = stringResource(KMR.strings.pref_yakuyomi_gemini_nano_summary),
                    enabled = enabled,
                    // KMK --> on-device Nano needs the MTL engine; stub builds always report unavailable.
                    mtlOnly = true,
                    // KMK <--
                ),
            )
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = "Gemini Nano device status",
                    // KMK -->
                    mtlOnly = true,
                    // KMK <--
                    content = {
                        var statusText by remember { mutableStateOf("Checking…") }
                        var nanoRefreshTick by remember { mutableStateOf(0) }
                        LaunchedEffect(geminiNanoEnabled, nanoRefreshTick) {
                            statusText = withIOContext {
                                val controller = globalAppGraph.mtlController
                                controller.refreshProviderReadiness(SETTINGS_NANO_ID)
                                val readiness = controller.providerReadiness(SETTINGS_NANO_ID)
                                when {
                                    !geminiNanoEnabled -> "Disabled by toggle — cloud provider will be used"
                                    readiness is mtl.core.Readiness.Ready -> "Available — on-device translation active"
                                    else -> {
                                        val err = (readiness as? mtl.core.Readiness.Unavailable)?.reason
                                        if (!err.isNullOrBlank() && (
                                                err.contains("601") || err.contains("BINDING") ||
                                                    err.contains("606") || err.contains("FEATURE_NOT_FOUND")
                                                )
                                        ) {
                                            "AICore is still setting up (${err.substringAfter("error code ").substringBefore(":").trim()}) — " +
                                                "update Google AI Core / restart the device, then check again. Cloud provider will be used meanwhile."
                                        } else {
                                            "Not available on this device right now — cloud provider will be used. " +
                                                "If this device supports Gemini Nano (e.g. Pixel 8+/Samsung S24+), AICore may still be provisioning — " +
                                                "update Google AI Core and restart, then re-open this screen."
                                        }
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp)) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (geminiNanoEnabled) {
                                androidx.compose.material3.TextButton(
                                    onClick = { nanoRefreshTick = nanoRefreshTick + 1 },
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    Text("Recheck")
                                }
                            }
                        }
                    },
                ),
            )
            add(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.provider(),
                    // KMK --> nomtl: only MangaTranslator can ever translate (see above).
                    entries = if (isNomtl) {
                        persistentMapOf(
                            "mangatranslator" to "MangaTranslator (remote)",
                        )
                    } else {
                        persistentMapOf(
                            "openrouter" to "OpenRouter",
                            "gemini" to "Gemini",
                            "opencode_zen" to "OpenCode Zen",
                            "nvidia_nim" to "NVIDIA NIM",
                            "custom_openai" to "Custom OpenAI",
                            "local" to "Local (On-device LLM)",
                            "mangatranslator" to "MangaTranslator (remote)",
                        )
                    },
                    // KMK <--
                    title = stringResource(KMR.strings.pref_yakuyomi_provider),
                    enabled = enabled,
                ),
            )
            // KMK --> Cloud-only options are irrelevant while the local provider is selected, and entirely hidden for mangatranslator (separate system).
            if (!isLocalProvider && !isMangatranslatorProvider) {
                add(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.apiKeyForProvider(provider),
                        title = stringResource(KMR.strings.pref_yakuyomi_api_key),
                        subtitle = apiKeySubtitle,
                        enabled = enabled,
                        // KMK --> cloud LLM keys are unusable without the MTL engine.
                        mtlOnly = true,
                        // KMK <--
                    ),
                )
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.modelForProvider(provider),
                        entries = entries,
                        title = stringResource(KMR.strings.pref_yakuyomi_model),
                        subtitle = "%s",
                        subtitleProvider = { v, _ -> v },
                        enabled = enabled,
                        // KMK -->
                        mtlOnly = true,
                        // KMK <--
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_yakuyomi_model_refresh),
                        subtitle = when {
                            fetchingModels -> stringResource(KMR.strings.pref_yakuyomi_model_fetching)
                            modelFetchFailed -> stringResource(KMR.strings.pref_yakuyomi_model_fetch_failed)
                            fetchedModels.isNotEmpty() ->
                                stringResource(KMR.strings.pref_yakuyomi_model_fetch_done, fetchedModels.size)
                            else -> null
                        },
                        onClick = {
                            refreshTick = refreshTick + 1
                        },
                        enabled = enabled,
                        // KMK -->
                        mtlOnly = true,
                        // KMK <--
                    ),
                )
            }
            // KMK <--
            add(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.customBaseUrl(),
                    title = "Custom API Base URL",
                    subtitle = if (provider == "custom_openai" && baseUrl.isBlank()) {
                        stringResource(KMR.strings.pref_yakuyomi_base_url_required)
                    } else {
                        "Base URL for OpenAI-compatible endpoint, e.g. https://api.example.com/v1"
                    },
                    enabled = enabled && provider == "custom_openai",
                    // KMK --> only meaningful while the custom provider is selected.
                    dependsOn = PreferenceDependency(source = prefs.provider(), expected = "custom_openai"),
                    // KMK <--
                ),
            )
            add(
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.customHeaders(),
                    title = "Custom Headers",
                    subtitle = "Key: Value per line, e.g. X-API-Key: abc",
                    enabled = enabled && provider in setOf("custom_openai", "opencode_zen", "nvidia_nim"),
                ),
            )
        }.toPersistentList(),
    )
}


@Composable
internal fun getRemoteModelGroup(
    prefs: exh.yakuyomi.TranslationPreferences,
    modelManager: exh.yakuyomi.ModelManager,
): Preference.PreferenceGroup {
    val enabled by prefs.enabled().collectAsState()
    val context = LocalContext.current
    val customActive = remember { modelManager.customUrlsActive() }
    return Preference.PreferenceGroup(
        title = "Remote model URLs",
        // KMK --> custom on-device model URLs are meaningless without the MTL engine.
        mtlOnly = true,
        // KMK <--
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.InfoPreference(
                title = "Use https URLs to override the built-in model manifest or individual OCR/detector/inpainter files. Leave blank to use the default GitHub releases. Requires re-download to take effect.",
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.modelManifestUrl(),
                title = stringResource(KMR.strings.pref_yakuyomi_model_manifest_url),
                subtitle = stringResource(KMR.strings.pref_yakuyomi_model_manifest_url_summary) + ": %s",
                enabled = enabled,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.customOcrModelUrl(),
                title = stringResource(KMR.strings.pref_yakuyomi_model_ocr_url),
                subtitle = stringResource(KMR.strings.pref_yakuyomi_model_ocr_url_summary) + ": %s",
                enabled = enabled,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.customInpainterModelUrl(),
                title = stringResource(KMR.strings.pref_yakuyomi_model_inpainter_url),
                subtitle = stringResource(KMR.strings.pref_yakuyomi_model_inpainter_url_summary) + ": %s",
                enabled = enabled,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.customDetectorModelUrl(),
                title = stringResource(KMR.strings.pref_yakuyomi_model_detector_url),
                subtitle = stringResource(KMR.strings.pref_yakuyomi_model_detector_url_summary) + ": %s",
                enabled = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Re-download with custom URLs",
                subtitle = if (customActive) "Custom URLs are active — tap to fetch" else "Set a URL above, then tap to re-download",
                onClick = {
                    modelManager.startDownload(force = true)
                    context.toast("Downloading with custom URLs…")
                },
                enabled = enabled,
            ),
        ),
    )
}

@Composable
internal fun getMangaTranslatorGroup(prefs: exh.yakuyomi.TranslationPreferences): Preference.PreferenceGroup {
    val enabled by prefs.enabled().collectAsState()
    val backStack = LocalBackStack.current
    val email by prefs.mangaTranslatorEmail().collectAsState()
    val token by prefs.mangaTranslatorAccessToken().collectAsState()
    val authSubtitle = when {
        token.isNotBlank() && email.isNotBlank() -> "Logged in as $email — session active (like the extension)"
        token.isNotBlank() -> "Logged in — session active (tap to manage)"
        else -> "Not logged in — anonymous (rate-limited). Tap to log in via ichigo.moe"
    }
    return Preference.PreferenceGroup(
        title = "MangaTranslator service",
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.mangaTranslatorEnabled(),
                title = stringResource(KMR.strings.pref_mangatranslator_enabled),
                subtitle = stringResource(KMR.strings.pref_mangatranslator_enabled_summary),
                enabled = enabled,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = prefs.mangaTranslatorBaseUrl(),
                title = stringResource(KMR.strings.pref_mangatranslator_base_url),
                subtitle = "%s",
                enabled = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Account — MangaTranslator / ichigo.moe",
                subtitle = authSubtitle,
                onClick = { backStack.add(SettingsMangaTranslatorAuthRoute) },
                enabled = enabled,
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.mangaTranslatorCachePermanent(),
                title = stringResource(KMR.strings.pref_mangatranslator_cache_permanent),
                subtitle = stringResource(KMR.strings.pref_mangatranslator_cache_permanent_summary),
                enabled = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.pref_mangatranslator_manage_cache),
                subtitle = stringResource(KMR.strings.pref_mangatranslator_manage_cache_summary),
                onClick = { backStack.add(SettingsMangaTranslatorCacheRoute) },
                enabled = enabled,
            ),
        ),
    )
}


private fun providerDisplayName(provider: String): String = when (provider) {
    "openrouter" -> "OpenRouter"
    "gemini" -> "Gemini"
    "opencode_zen" -> "OpenCode Zen"
    "nvidia_nim" -> "NVIDIA NIM"
    "custom_openai" -> "Custom OpenAI"
    "local" -> "Local"
    "mangatranslator" -> "MangaTranslator"
    else -> provider.ifBlank { "openrouter" }
}

