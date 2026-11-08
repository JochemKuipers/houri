package eu.kanade.tachiyomi.ui.manga.scanlator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import tachiyomi.domain.chapter.service.ScanlatorRangeRule
import tachiyomi.domain.chapter.service.parseScanlatorRangeRule
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun RangesTab(
    state: ScanlatorPreferenceViewModel.State,
    model: ScanlatorPreferenceViewModel,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MaterialTheme.padding.small),
    ) {
        item(key = "add_range") {
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(KMR.strings.scanlator_range_add))
            }
        }

        if (state.rangeRules.isEmpty()) {
            item(key = "ranges_empty") {
                Text(
                    text = stringResource(KMR.strings.scanlator_ranges_empty),
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                )
            }
        }

        items(state.rangeRules, key = { it }) { raw ->
            val rule = remember(raw) { parseScanlatorRangeRule(raw) }
            if (rule != null) {
                RangeRuleItem(
                    rule = rule,
                    onRemove = { model.removeRangeRule(raw) },
                )
            }
        }
    }

    if (showAddDialog) {
        RangeRuleDialog(
            scanlators = state.orderedScanlators.map { it.name },
            onConfirm = { from, to, scanlator ->
                model.addRangeRule(from, to, scanlator)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
internal fun RangeRuleItem(
    rule: ScanlatorRangeRule,
    onRemove: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.extraSmall),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${rule.from.asChapterLabel()} – ${rule.to.asChapterLabel()}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = rule.scanlator,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_remove),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RangeRuleDialog(
    scanlators: List<String>,
    onConfirm: (Double, Double, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var selectedScanlator by remember { mutableStateOf<String?>(null) }

    val from = fromText.toDoubleOrNull()
    val to = toText.toDoubleOrNull()
    val valid = from != null && to != null && from <= to && !selectedScanlator.isNullOrEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(KMR.strings.scanlator_range_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text(text = stringResource(KMR.strings.scanlator_range_from)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text(text = stringResource(KMR.strings.scanlator_range_to)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                Text(
                    text = stringResource(KMR.strings.scanlator_range_scanlator),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    scanlators.forEach { name ->
                        FilterChip(
                            selected = name == selectedScanlator,
                            onClick = { selectedScanlator = name },
                            label = { Text(text = name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            val scanlator = selectedScanlator
            TextButton(
                enabled = valid,
                onClick = {
                    if (from != null && to != null && scanlator != null) {
                        onConfirm(from, to, scanlator)
                    }
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

internal fun Double.asChapterLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()
