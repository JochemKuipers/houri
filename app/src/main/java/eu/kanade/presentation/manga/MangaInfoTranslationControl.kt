package eu.kanade.presentation.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.MangaInfoErrorKind
import eu.kanade.tachiyomi.ui.manga.MangaInfoUiState
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun TranslateMangaToggle(manga: tachiyomi.domain.manga.model.Manga) {
    val manager = androidx.compose.runtime.remember { mihon.app.di.globalAppGraph.mtlController }
    val store = androidx.compose.runtime.remember(manga.id) { mihon.app.di.globalAppGraph.translateMangaStore }
    val prefs = androidx.compose.runtime.remember { mihon.app.di.globalAppGraph.translationPreferences }
    val preferenceStore = androidx.compose.runtime.remember { mihon.app.di.globalAppGraph.preferenceStore }
    val globalEnabled by prefs.enabled().collectAsState()
    val perMangaEnabled by store.getPreference(manga.id).collectAsState()
    // Gate UI when incognito/censor is on — still show row but disabled with hint
    val incognito by preferenceStore.getBoolean(tachiyomi.core.common.preference.Preference.appStateKey("incognito_mode"), false).collectAsState()
    val censor by preferenceStore.getBoolean("pref_censor_lewd_manga", false).collectAsState()
    val isGated = manager.isGated() && (incognito || censor)
    // When global MTL is off, hide the per-manga toggle entirely.
    if (!globalEnabled) return
    val subtitle = if (isGated) {
        stringResource(tachiyomi.i18n.kmk.KMR.strings.pref_yakuyomi_enabled_summary)
    } else {
        stringResource(tachiyomi.i18n.kmk.KMR.strings.pref_translate_manga_summary)
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = stringResource(tachiyomi.i18n.kmk.KMR.strings.pref_translate_manga),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            androidx.compose.material3.Text(
                text = subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(
            checked = perMangaEnabled,
            enabled = globalEnabled && !isGated,
            onCheckedChange = {
                manager.setPerMangaEnabled(manga.id, it)
            },
        )
    }
}

// KMK --> Integrated manga-details metadata translation (spec 2026-09-23).
// Display-value selectors: the translated title/description replace the normal displayed
// values only while Translated + showTranslated; every other state keeps the originals.

internal fun mangaInfoDisplayTitle(uiState: MangaInfoUiState): String? {
    val translated = uiState as? MangaInfoUiState.Translated ?: return null
    if (!translated.showTranslated) return null
    return translated.title.takeIf { it.isNotBlank() }
}

internal fun mangaInfoDisplayDescription(manga: Manga, uiState: MangaInfoUiState): String? {
    val translated = uiState as? MangaInfoUiState.Translated ?: return manga.description
    if (!translated.showTranslated) return manga.description
    return translated.description?.takeIf { it.isNotBlank() } ?: manga.description
}

@Composable
fun MangaInfoTranslationControl(
    uiState: MangaInfoUiState,
    onEnabledChange: (Boolean) -> Unit,
    onShowTranslatedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        MangaInfoUiState.Unresolved -> Unit
        MangaInfoUiState.Hidden -> Unit
        MangaInfoUiState.Disabled -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(KMR.strings.pref_translate_manga_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(KMR.strings.pref_translate_manga_info_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = false,
                    onCheckedChange = { if (it) onEnabledChange(true) },
                )
            }
        }
        MangaInfoUiState.Translating -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(KMR.strings.pref_translate_manga_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(KMR.strings.mtl_info_translating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = true,
                    onCheckedChange = { if (!it) onEnabledChange(false) },
                )
            }
        }
        is MangaInfoUiState.Translated -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(KMR.strings.pref_translate_manga_info),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = true,
                        onCheckedChange = { if (!it) onEnabledChange(false) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = uiState.showTranslated,
                        onClick = { onShowTranslatedChange(false) },
                    ) {
                        Text(stringResource(KMR.strings.mtl_info_show_original))
                    }
                    TextButton(
                        enabled = !uiState.showTranslated,
                        onClick = { onShowTranslatedChange(true) },
                    ) {
                        Text(stringResource(KMR.strings.mtl_info_show_translated))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(KMR.strings.mtl_info_refresh),
                        )
                    }
                }
            }
        }
        is MangaInfoUiState.Error -> {
            val message = when (uiState.kind) {
                MangaInfoErrorKind.ProviderNotReady -> stringResource(KMR.strings.mtl_info_error_provider_not_ready)
                MangaInfoErrorKind.MangaTranslatorUnsupported -> stringResource(KMR.strings.mtl_info_error_unsupported)
                MangaInfoErrorKind.Failed -> stringResource(KMR.strings.mtl_info_failed)
            }
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(KMR.strings.pref_translate_manga_info),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = true,
                        onCheckedChange = { if (!it) onEnabledChange(false) },
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(KMR.strings.mtl_retry))
                    }
                    TextButton(onClick = onReset) {
                        Text(stringResource(KMR.strings.mtl_info_reset))
                    }
                }
            }
        }
    }
}
// KMK <--
