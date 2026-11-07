package eu.kanade.presentation.category.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryType
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
@Composable
fun CategoryMoveDialog(
    parents: ImmutableList<Category>,
    excludeParentId: Long? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var selected by remember { mutableStateOf(parents.firstOrNull { it.id != excludeParentId }?.id) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    selected?.let(onConfirm)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(KMR.strings.category_manager_move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(KMR.strings.category_manager_move_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                parents.forEach { parent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = parent.id != excludeParentId) { selected = parent.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == parent.id,
                            enabled = parent.id != excludeParentId,
                            onClick = { selected = parent.id },
                        )
                        Text(
                            text = parent.name,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun CategoryMergeDialog(
    candidates: ImmutableList<Category>,
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var selected by remember { mutableStateOf(candidates.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = selected != null && candidates.size >= 2,
                onClick = {
                    selected?.let(onConfirm)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(KMR.strings.category_manager_merge))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(KMR.strings.category_manager_merge_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = candidate.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == candidate.id,
                            onClick = { selected = candidate.id },
                        )
                        Text(
                            text = candidate.name,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }
            }
        },
    )
}
// KMK <--

// KMK -->
private enum class SubcategoryNameSource {
    ARTIST,
    AUTHOR,
    MANUAL,
}

@Composable
fun CreateSubcategoryDialog(
    onDismissRequest: () -> Unit,
    // KMK --> Second arg is the chosen [CategoryType] ordinal, or null for "unclassified".
    onCreate: (String, Int?) -> Unit,
    // KMK <--
    artist: String?,
    author: String?,
) {
    val initial = when {
        !artist.isNullOrBlank() -> SubcategoryNameSource.ARTIST to artist.orEmpty()
        !author.isNullOrBlank() -> SubcategoryNameSource.AUTHOR to author.orEmpty()
        else -> SubcategoryNameSource.MANUAL to ""
    }
    var source by remember { mutableStateOf(initial.first) }
    var name by remember { mutableStateOf(initial.second) }
    // KMK --> Role of this subcategory: Author / Artist / Illustrator / Designer / None.
    // Defaults to None, because a manual or auto-filled name gives no evidence of a role.
    var type by remember { mutableStateOf<Int?>(null) }
    // KMK <--

    val hasDetection = !artist.isNullOrBlank() || !author.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(name, type)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = { Text(text = stringResource(KMR.strings.create_subcategory_title)) },
        text = {
            // KMK --> Scrollable: the name field plus the role picker's five rows overflow a
            // fixed-height AlertDialog on a small screen and pushed the buttons off.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // KMK <--
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(MR.strings.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasDetection) {
                    Text(
                        text = stringResource(KMR.strings.create_subcategory_from),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    SubcategorySourceRow(
                        label = stringResource(
                            KMR.strings.create_subcategory_from_artist,
                            artist.orEmpty(),
                        ),
                        selected = source == SubcategoryNameSource.ARTIST,
                        enabled = !artist.isNullOrBlank(),
                        onSelect = {
                            source = SubcategoryNameSource.ARTIST
                            name = artist.orEmpty()
                        },
                    )
                    SubcategorySourceRow(
                        label = stringResource(
                            KMR.strings.create_subcategory_from_author,
                            author.orEmpty(),
                        ),
                        selected = source == SubcategoryNameSource.AUTHOR,
                        enabled = !author.isNullOrBlank(),
                        onSelect = {
                            source = SubcategoryNameSource.AUTHOR
                            name = author.orEmpty()
                        },
                    )
                    SubcategorySourceRow(
                        label = stringResource(KMR.strings.create_subcategory_from_manual),
                        selected = source == SubcategoryNameSource.MANUAL,
                        enabled = true,
                        onSelect = { source = SubcategoryNameSource.MANUAL },
                    )
                }
                // KMK --> Role picker, so two same-named subcategories can be told apart
                // later in the lists (issue #57).
                SubcategoryTypePicker(type = type, onType = { type = it })
                // KMK <--
            }
        },
    )
}

// KMK -->
/**
 * Role choice for a new subcategory: unclassified (default), Author, Artist, Illustrator
 * or Designer. Author and Artist reuse shared MR strings - the same words everywhere;
 * Illustrator and Designer have no upstream equivalent and are KMR.
 */
@Composable
internal fun SubcategoryTypePicker(
    type: Int?,
    onType: (Int?) -> Unit,
) {
    Text(
        text = stringResource(KMR.strings.subcategory_type),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    listOf(
        null to KMR.strings.subcategory_type_none,
        CategoryType.AUTHOR.ordinal to MR.strings.author,
        CategoryType.ARTIST.ordinal to MR.strings.artist,
        CategoryType.ILLUSTRATOR.ordinal to KMR.strings.subcategory_type_illustrator,
        CategoryType.DESIGNER.ordinal to KMR.strings.subcategory_type_designer,
    ).forEach { (ordinal, res) ->
        SubcategorySourceRow(
            label = stringResource(res),
            selected = type == ordinal,
            enabled = true,
            onSelect = { onType(ordinal) },
        )
    }
}
// KMK <--

@Composable
internal fun SubcategorySourceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            // KMK --> Space after the radio: without it the label started at the radio's edge.
            modifier = Modifier.padding(start = MaterialTheme.padding.small),
            // KMK <--
        )
    }
}
// KMK <--
