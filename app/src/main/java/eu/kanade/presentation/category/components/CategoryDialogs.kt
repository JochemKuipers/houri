package eu.kanade.presentation.category.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.preference.asToggleableState
import eu.kanade.presentation.category.CategoryTypeBadge
import eu.kanade.presentation.category.sortedByHierarchy
import eu.kanade.presentation.category.visualName
import eu.kanade.tachiyomi.ui.category.CategoryManagerSort
import eu.kanade.tachiyomi.ui.category.categorySortOrderOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategorySortOrder
import tachiyomi.domain.category.model.CategoryType
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

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
private fun SubcategorySourceRow(
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

@Composable
fun CategoryCreateDialog(
    // KMK --> type-aware subcategory creation passes [onCreateWithSubcategoryType] instead.
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit = {},
    categories: ImmutableList<String>,
    // SY -->
    title: String = stringResource(MR.strings.action_add_category),
    extraMessage: String? = null,
    alreadyExistsError: StringResource = MR.strings.error_category_exists,
    // SY <--
    // KMK --> When true, the dialog also picks a subcategory role (Author / Artist /
    // Illustrator / Designer / None) and hands it to the branch below instead of [onCreate].
    subcategoryType: Boolean = false,
    onCreateWithSubcategoryType: ((String, Int?) -> Unit)? = null,
    // KMK <--
) {
    var name by remember { mutableStateOf("") }
    // KMK --> Role of the created subcategory; defaults to unclassified whenever the picker
    // is active, matching the "no evidence, no role" behaviour of the dedicated dialog.
    var subcategoryTypeValue by remember { mutableStateOf<Int?>(null) }
    // KMK <--

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name) { categories.contains(name) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = name.isNotEmpty() && !nameAlreadyExists,
                onClick = {
                    // KMK --> Role-carrying branch when the caller asked for a picker.
                    val createWithType = onCreateWithSubcategoryType
                    if (subcategoryType && createWithType != null) {
                        createWithType(name, subcategoryTypeValue)
                    } else {
                        onCreate(name)
                    }
                    // KMK <--
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
        title = {
            // SY -->
            Text(text = title)
            // SY <--
        },
        text = {
            // SY -->
            // KMK --> Scrollable: with the subcategory role picker the form outgrows a
            // fixed-height AlertDialog.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // KMK <--
                extraMessage?.let { Text(it) }
                // SY <--

                OutlinedTextField(
                    modifier = Modifier
                        .focusRequester(focusRequester),
                    value = name,
                    onValueChange = { name = it },
                    label = {
                        Text(text = stringResource(MR.strings.name))
                    },
                    supportingText = {
                        val msgRes = if (name.isNotEmpty() && nameAlreadyExists) {
                            // SY -->
                            alreadyExistsError
                            // SY <--
                        } else {
                            MR.strings.information_required_plain
                        }
                        Text(text = stringResource(msgRes))
                    },
                    isError = name.isNotEmpty() && nameAlreadyExists,
                    singleLine = true,
                )
                // KMK --> Role choice only for subcategory creation; a top-level category keeps
                // the plain form.
                if (subcategoryType && onCreateWithSubcategoryType != null) {
                    SubcategoryTypePicker(type = subcategoryTypeValue, onType = { subcategoryTypeValue = it })
                }
                // KMK <--
                // SY -->
            }
            // SY <--
        },
    )

    LaunchedEffect(focusRequester) {
        // TODO: https://issuetracker.google.com/issues/204502668
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}

@Composable
fun CategoryRenameDialog(
    onDismissRequest: () -> Unit,
    onRename: (String) -> Unit,
    categories: ImmutableList<String>,
    category: String,
) {
    var name by remember { mutableStateOf(category) }
    var valueHasChanged by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val nameAlreadyExists = remember(name) { categories.contains(name) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = valueHasChanged && !nameAlreadyExists,
                onClick = {
                    onRename(name)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_rename_category))
        },
        text = {
            OutlinedTextField(
                modifier = Modifier.focusRequester(focusRequester),
                value = name,
                onValueChange = {
                    valueHasChanged = name != it
                    name = it
                },
                label = { Text(text = stringResource(MR.strings.name)) },
                supportingText = {
                    val msgRes = if (valueHasChanged && nameAlreadyExists) {
                        MR.strings.error_category_exists
                    } else {
                        MR.strings.information_required_plain
                    }
                    Text(text = stringResource(msgRes))
                },
                isError = valueHasChanged && nameAlreadyExists,
                singleLine = true,
            )
        },
    )

    LaunchedEffect(focusRequester) {
        // TODO: https://issuetracker.google.com/issues/204502668
        delay(0.1.seconds)
        focusRequester.requestFocus()
    }
}

@Composable
fun CategoryDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    // SY -->
    category: String = "",
    title: String = stringResource(MR.strings.delete_category),
    text: String = stringResource(MR.strings.delete_category_confirmation, category),
    // SY <--
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        title = {
            // SY -->
            Text(text = title)
            // SY <--
        },
        text = {
            // SY -->
            Text(text = text)
            // SY <--
        },
    )
}

@Composable
fun ChangeCategoryDialog(
    initialSelection: ImmutableList<CheckboxState<Category>>,
    onDismissRequest: () -> Unit,
    onEditCategories: () -> Unit,
    onConfirm: (List<Long>, List<Long>) -> Unit,
) {
    if (initialSelection.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                tachiyomi.presentation.core.components.material.TextButton(
                    onClick = {
                        onDismissRequest()
                        onEditCategories()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_edit_categories))
                }
            },
            title = {
                Text(text = stringResource(MR.strings.action_move_category))
            },
            text = {
                Text(text = stringResource(MR.strings.information_empty_category_dialog))
            },
        )
        return
    }
    var selection by remember { mutableStateOf(initialSelection) }
    // KMK --> The Search / sort / collapse trio from the Edit Categories screen, requested for
    // this dialog in issue #57. The state is dialog-scoped on purpose: it closes over the
    // snapshot the dialog was opened with, and the category set can change under it through
    // other screens, so persisting it would only describe a stale list.
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(CategoryManagerSort.MANUAL) }
    var collapsedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val rows = remember(selection, sortMode, searchQuery, collapsedIds) {
        buildSetCategoryRows(
            selection = selection,
            sortOrder = categorySortOrderOf(sortMode),
            query = searchQuery.trim(),
            collapsedIds = collapsedIds,
        )
    }
    // Whether any parent carries subcategories at all - without hierarchy the collapse
    // pair has nothing to act on and the controls only add noise.
    val hasHierarchy = remember(selection) {
        val ids = selection.mapTo(mutableSetOf()) { it.value.id }
        selection.any { it.value.parentId != 0L && it.value.parentId in ids }
    }
    // KMK <--
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Row {
                tachiyomi.presentation.core.components.material.TextButton(onClick = {
                    onDismissRequest()
                    onEditCategories()
                }) {
                    Text(text = stringResource(MR.strings.action_edit))
                }
                Spacer(modifier = Modifier.weight(1f))
                tachiyomi.presentation.core.components.material.TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                tachiyomi.presentation.core.components.material.TextButton(
                    onClick = {
                        onDismissRequest()
                        val parentIdById = selection.associate { it.value.id to it.value.parentId }
                        val included = selection
                            .filter { it is CheckboxState.State.Checked || it is CheckboxState.TriState.Include }
                            .flatMap { it.value.id.withAncestorChain(parentIdById) }
                            .toSet()
                        val excluded = (
                            selection
                                .filter { it is CheckboxState.State.None || it is CheckboxState.TriState.None }
                                .mapTo(mutableSetOf()) { it.value.id }
                            ) - included
                        onConfirm(included.toList(), excluded.toList())
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_move_category))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // KMK -->
                CategorySearchField(
                    query = searchQuery,
                    onQuery = { searchQuery = it },
                    placeholder = KMR.strings.category_manager_search,
                )
                // KMK --> The dropdown owns the full width and the collapse pair sits on its own
                // end-aligned line, mirroring the Edit Categories layout: sharing one row would
                // squeeze a translated label to nothing on a 360dp phone.
                Column(modifier = Modifier.fillMaxWidth()) {
                    CategorySortModeDropdown(
                        sortMode = sortMode,
                        onSortMode = { sortMode = it },
                        modes = SET_CATEGORY_SORT_MODES,
                        sortTitle = KMR.strings.set_categories_sort,
                    )
                    if (hasHierarchy) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { collapsedIds = emptySet() }) {
                                Text(stringResource(KMR.strings.category_manager_expand_all))
                            }
                            TextButton(onClick = {
                                val ids = selection.mapTo(mutableSetOf()) { it.value.id }
                                collapsedIds = selection
                                    .map { it.value }
                                    .filter { it.parentId != 0L && it.parentId in ids }
                                    .mapTo(mutableSetOf()) { it.parentId }
                            }) {
                                Text(stringResource(KMR.strings.category_manager_collapse_all))
                            }
                        }
                    }
                }
                // KMK <--
                rows.forEach { row ->
                    val checkbox = row.state
                    val onChange: (CheckboxState<Category>) -> Unit = {
                        val index = selection.indexOf(it)
                        if (index != -1) {
                            val mutableList = selection.toMutableList()
                            mutableList[index] = it.next()
                            selection = mutableList.toList().toImmutableList()
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // KMK --> indent stays on subcategories only
                            .padding(
                                start = if (checkbox.value.parentId != 0L) MaterialTheme.padding.medium else 0.dp,
                            )
                            // KMK <--
                            .clickable { onChange(checkbox) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (checkbox) {
                            is CheckboxState.TriState -> {
                                TriStateCheckbox(
                                    state = checkbox.asToggleableState(),
                                    onClick = { onChange(checkbox) },
                                )
                            }
                            is CheckboxState.State -> {
                                Checkbox(
                                    checked = checkbox.isChecked,
                                    onCheckedChange = { onChange(checkbox) },
                                )
                            }
                        }

                        Text(
                            text = checkbox.value.visualName,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = MaterialTheme.padding.medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // KMK --> Role badge on subcategories, so two same-named rows
                        // (author vs. artist) are tellable apart while assigning.
                        if (checkbox.value.parentId != 0L) {
                            CategoryTypeBadge(type = checkbox.value.type)
                        }
                        // KMK <--
                        // KMK --> Collapse/expand a parent's subcategories without leaving the dialog.
                        if (row.isTopLevel && row.subCount > 0) {
                            IconButton(onClick = {
                                collapsedIds = if (row.collapsed) {
                                    collapsedIds - checkbox.value.id
                                } else {
                                    collapsedIds + checkbox.value.id
                                }
                            }) {
                                Icon(
                                    imageVector = if (row.collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                    contentDescription = stringResource(
                                        if (row.collapsed) KMR.strings.category_set_expand else KMR.strings.category_set_collapse,
                                    ),
                                )
                            }
                        }
                        // KMK <--
                    }
                }
            }
        },
    )
}

// KMK -->
/** Modes the Set Categories dialog can offer: all of [CategoryManagerSort] except the two
 * count-based ones, because a bare selection list has no counts to rank the rows by. */
private val SET_CATEGORY_SORT_MODES = listOf(
    CategoryManagerSort.MANUAL,
    CategoryManagerSort.AZ,
    CategoryManagerSort.ZA,
    CategoryManagerSort.DATE_ADDED_NEWEST,
    CategoryManagerSort.DATE_ADDED_OLDEST,
    CategoryManagerSort.DATE_MODIFIED_NEWEST,
    CategoryManagerSort.DATE_MODIFIED_OLDEST,
)

// KMK --> The full set, for callers that also have per-category counts (Edit Categories).
internal val ALL_CATEGORY_SORT_MODES = listOf(
    CategoryManagerSort.MANUAL,
    CategoryManagerSort.AZ,
    CategoryManagerSort.ZA,
    CategoryManagerSort.MOST_ITEMS,
    CategoryManagerSort.LEAST_ITEMS,
    CategoryManagerSort.DATE_ADDED_NEWEST,
    CategoryManagerSort.DATE_ADDED_OLDEST,
    CategoryManagerSort.DATE_MODIFIED_NEWEST,
    CategoryManagerSort.DATE_MODIFIED_OLDEST,
)
// KMK <--

/** One rendered row of the Set Categories dialog: either a top-level category (optionally
 * carrying its subcategories) or a subcategory row. */
internal data class SetCategoryRow(
    val state: CheckboxState<Category>,
    val isTopLevel: Boolean,
    val subCount: Int = 0,
    val collapsed: Boolean = false,
)

/**
 * Flattens [selection] into dialog rows: every root followed by its subcategories, both
 * ordered by [sortOrder] (via [sortedByHierarchy]). A collapsed root hides its subs until
 * expanded (or until a search is active, which ignores the collapse state like Edit
 * Categories does). Subcategories whose root disappeared from the selection are appended
 * at the end so they stay reachable.
 */
internal fun buildSetCategoryRows(
    selection: List<CheckboxState<Category>>,
    sortOrder: CategorySortOrder,
    query: String,
    collapsedIds: Set<Long>,
): List<SetCategoryRow> {
    if (selection.isEmpty()) return emptyList()
    val ordered = selection.map { it.value }.sortedByHierarchy(sortOrder)
    val byId = selection.associateBy { it.value.id }
    val ids = ordered.mapTo(mutableSetOf()) { it.id }
    val subsByParent = mutableMapOf<Long, MutableList<Category>>()
    val orphans = mutableListOf<Category>()
    ordered.filter { it.parentId != 0L }.forEach { sub ->
        if (sub.parentId in ids) {
            subsByParent.getOrPut(sub.parentId) { mutableListOf() }.add(sub)
        } else {
            orphans.add(sub)
        }
    }
    return buildList {
        ordered.filter { it.parentId == 0L }.forEach { parent ->
            val subs = subsByParent[parent.id].orEmpty()
            if (query.isEmpty()) {
                val collapsed = parent.id in collapsedIds && subs.isNotEmpty()
                add(SetCategoryRow(byId.getValue(parent.id), isTopLevel = true, subCount = subs.size, collapsed = collapsed))
                if (!collapsed) {
                    subs.forEach { add(SetCategoryRow(byId.getValue(it.id), isTopLevel = false)) }
                }
            } else {
                // A root name match keeps the whole subtree visible for context; otherwise
                // only the subcategories that matched, under their parent header.
                val parentMatches = parent.name.contains(query, ignoreCase = true)
                val matching = subs.filter { it.name.contains(query, ignoreCase = true) }
                if (parentMatches || matching.isNotEmpty()) {
                    add(SetCategoryRow(byId.getValue(parent.id), isTopLevel = true, subCount = subs.size))
                    (if (parentMatches) subs else matching).forEach {
                        add(SetCategoryRow(byId.getValue(it.id), isTopLevel = false))
                    }
                }
            }
        }
        orphans.forEach { sub ->
            if (query.isEmpty() || sub.name.contains(query, ignoreCase = true)) {
                add(SetCategoryRow(byId.getValue(sub.id), isTopLevel = false))
            }
        }
    }
}
// KMK <--

/**
 * The sort dropdown shared by the Edit Categories screen and the Set Categories dialog.
 * [modes] selects the entries it offers; the count-based modes are only meaningful where
 * per-category counts exist.
 */
@Composable
internal fun CategorySortModeDropdown(
    sortMode: Int,
    onSortMode: (Int) -> Unit,
    modes: List<Int>,
    // KMK --> The manager sorts subcategories (and theirs); the Set Categories dialog sorts
    // whole categories, so the prompt wording is the caller's choice.
    sortTitle: StringResource = KMR.strings.category_manager_sort,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val labels = remember {
        mapOf(
            CategoryManagerSort.MANUAL to KMR.strings.category_manager_sort_manual,
            CategoryManagerSort.AZ to KMR.strings.category_manager_sort_az,
            CategoryManagerSort.ZA to KMR.strings.category_manager_sort_za,
            CategoryManagerSort.MOST_ITEMS to KMR.strings.category_manager_sort_most,
            CategoryManagerSort.LEAST_ITEMS to KMR.strings.category_manager_sort_least,
            CategoryManagerSort.DATE_ADDED_NEWEST to KMR.strings.category_manager_sort_date_added_newest,
            CategoryManagerSort.DATE_ADDED_OLDEST to KMR.strings.category_manager_sort_date_added_oldest,
            CategoryManagerSort.DATE_MODIFIED_NEWEST to KMR.strings.category_manager_sort_date_modified_newest,
            CategoryManagerSort.DATE_MODIFIED_OLDEST to KMR.strings.category_manager_sort_date_modified_oldest,
        )
    }
    Box(modifier = modifier) {
        TextButton(onClick = { menuOpen = true }) {
            Text("${stringResource(sortTitle)}: ${stringResource(labels[sortMode] ?: KMR.strings.category_manager_sort_manual)}")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            modes.forEach { mode ->
                labels[mode]?.let { labelRes ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            menuOpen = false
                            onSortMode(mode)
                        },
                    )
                }
            }
        }
    }
}
// KMK <--

/**
 * Returns [id] together with every ancestor category id up to the root, so marking a
 * subcategory always marks its parents as well.
 */
internal fun Long.withAncestorChain(parentIdById: Map<Long, Long>): Set<Long> {
    val chain = mutableSetOf(this)
    var cursor = parentIdById[this] ?: 0L
    while (cursor != 0L && chain.add(cursor)) {
        cursor = parentIdById[cursor] ?: 0L
    }
    return chain
}

internal fun Long.withAncestorChain(categories: List<Category>): Set<Long> =
    withAncestorChain(categories.associate { it.id to it.parentId })
