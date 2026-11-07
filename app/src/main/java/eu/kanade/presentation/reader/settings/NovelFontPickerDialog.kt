package eu.kanade.presentation.reader.settings

import android.content.Context
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelBuiltinFonts
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelFonts
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelFonts.ImportResult
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The font row, showing what is currently in use, opening [NovelFontPickerDialog] on tap.
 *
 * Hand-built rather than an `IconItem` because the reader chooses a font twice — once as a name and
 * once with the eyes — and the name alone is what the currently-in-use half has to carry: a setting
 * that hides which font is in force behind a label would make every mismatch between what the picker
 * shows and what the page renders undiagnosable.
 */
@Composable
internal fun FontRow(family: String, onClick: () -> Unit) {
Row(
    modifier = Modifier
        .clickable(onClick = onClick)
        .fillMaxWidth()
        .padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = stringResource(KMR.strings.pref_novel_font_family),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = familyLabel(family),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // The dropdown affordance: the row opens a list, and without it the row reads as a label.
    Icon(
        imageVector = Icons.Outlined.ArrowDropDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
}

/**
 * What the font row and the picker display for a family value.
 *
 * Both prefixes are conventional — the value is a CSS family name the stylesheet quotes whole — and
 * neither half of a prefixed value is meant for the reader's eyes. Stripping whichever one is present
 * leaves the display name, or the CSS generic keyword for the four device spines, which were the only
 * labels the old dropdown offered anyway.
 */
internal fun familyLabel(family: String): String =
family.removePrefix(NovelBuiltinFonts.PREFIX).removePrefix(NovelFonts.PREFIX)

/**
 * One family in the picker: what is stored to select it, what is displayed, and what face the label
 * is drawn in.
 *
 * [preview] is a `FontFamily`, not a `Typeface`, because Compose resolves the sample text with it as
 * it would resolve any text, identical for every row but the face — which is what makes the list a
 * specimen sheet rather than names to be taken on trust.
 */
internal data class FontChoice(
val value: String,
val label: String,
val preview: FontFamily,
val deletable: Boolean = false,
)

internal sealed interface FontRow {
data class Group(val label: StringResource) : FontRow
data class Choice(val choice: FontChoice) : FontRow
}

/**
 * The font picker: device generics, the bundled families, the reader's own imports, all previewed in
 * their own faces, and import offered at the top rather than buried.
 *
 * ### Why previews instead of names
 *
 * A font is typography, and "Playfair Display" set in the dialog's own typeface tells the reader
 * nothing about the face behind it. Drawing each name in its own face makes the picker a specimen
 * sheet: the reader stops choosing fonts by reputation and starts choosing by look, which is the
 * whole job. The preview is built straight off the same source the page will load — asset for
 * built-ins, file for imports — so *what the preview showed is what the reading will use* is a
 * property of the implementation, and a face that fails to load falls back to its group's generic
 * rather than showing a face the page could not reproduce.
 *
 * ### Why the face for a preview may fail
 *
 * [Typeface.createFromAsset] and [Typeface.createFromFile] are the two calls here that can fail on a
 * real device — a truncated import, a corrupt asset — and neither may throw inside composition. The
 * fallbacks are the group's own generic: wrong if the reader wanted that exact face, right in the
 * way that keeps the list selectable.
 *
 * The rows are read once per [version] bump, which also invalidates them after an import or a
 * deletion — a deletion is confirmed through [onDelete] in a second dialog, because a delete is one
 * tap away from a reading session with no fonts at all. The list is a LazyColumn because the bundle
 * plus imports is long enough to need one.
 */
@Composable
internal fun NovelFontPickerDialog(
context: Context,
version: Int,
selected: String,
onSelect: (String) -> Unit,
onDelete: (String) -> Unit,
onChanged: () -> Unit,
onDismiss: () -> Unit,
) {
val rows = remember(version) { pickerRows(context) }
var pendingDelete by remember { mutableStateOf<String?>(null) }

val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
) { uri ->
    if (uri != null) {
        context.toast(
            when (NovelFonts.import(context, uri)) {
                ImportResult.Success -> KMR.strings.novel_font_added
                ImportResult.NotAFont -> KMR.strings.novel_font_invalid
                ImportResult.TooLarge -> KMR.strings.novel_font_too_large
                ImportResult.Unreadable -> KMR.strings.novel_font_add_failed
            },
        )
        onChanged()
    }
}

AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(KMR.strings.pref_novel_font_family)) },
    text = {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            // Import first: a reader who has their own file in hand should not scroll past
            // three hundred families to offer it.
            item {
                Row(
                    modifier = Modifier
                        .clickable { importLauncher.launch(FONT_PICK_MIME_TYPES) }
                        .fillMaxWidth()
                        .padding(
                            horizontal = SettingsItemsPaddings.Horizontal,
                            vertical = SettingsItemsPaddings.Vertical,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.UploadFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(KMR.strings.novel_font_add),
                        modifier = Modifier
                            .padding(start = MaterialTheme.padding.large)
                            .weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            rows.forEach { row ->
                when (row) {
                    is FontRow.Group -> item { FontDialogHeader(stringResource(row.label)) }
                    is FontRow.Choice -> item {
                        FontChoiceRow(
                            choice = row.choice,
                            selected = selected,
                            onSelect = onSelect,
                            onRemove = { pendingDelete = row.choice.value },
                        )
                    }
                }
            }
        }
    },
    confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_ok)) }
    },
)

val deleteTarget = pendingDelete
if (deleteTarget != null) {
    AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(KMR.strings.novel_font_remove)) },
        text = { Text(stringResource(KMR.strings.novel_font_remove_confirm)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete(deleteTarget)
                    pendingDelete = null
                },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { pendingDelete = null }) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
}

/**
 * A group label inside the dialog — the same grammar a section heading has, sized down to fit one.
 */
@Composable
internal fun FontDialogHeader(text: String) {
Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(
        start = SettingsItemsPaddings.Horizontal,
        end = SettingsItemsPaddings.Horizontal,
        top = MaterialTheme.padding.small,
    ),
)
}

/**
 * One family row: the name set in its own face, a bin for the reader's own fonts, and the check
 * against what is in use.
 *
 * The bin only appears on deletable rows — the device generics and the bundle belong to the app, and
 * an uninstall affordance on anything the app carries would be a promise `NovelFonts.delete` cannot
 * keep, which is why it answers `false` for them rather than pretending.
 */
@Composable
internal fun FontChoiceRow(
choice: FontChoice,
selected: String,
onSelect: (String) -> Unit,
onRemove: () -> Unit,
) {
val isChosen = choice.value == selected
Row(
    modifier = Modifier
        .clickable { onSelect(choice.value) }
        .fillMaxWidth()
        .padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text = choice.label,
        modifier = Modifier.weight(1f, fill = false),
        style = MaterialTheme.typography.bodyLarge
            .copy(fontFamily = choice.preview, fontSize = 20.sp),
        color = if (isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (choice.deletable) {
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(KMR.strings.novel_font_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (isChosen) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
}

/**
 * The picker's rows: groups in the order a reader decides in, choices under each.
 *
 * The four device generics go first because they are the escape hatches — the previously-everything
 * answers that keep working when every named face proves wrong for a book. The bundle follows,
 * grouped by [NovelBuiltinFonts.Category], by the same headings its categories name, so a reader
 * looking for a serif opens the serif section rather than recognising names. Imports are last and
 * only when there are any: an empty "Your fonts" header describing nothing is a UI bug.
 */
internal fun pickerRows(context: Context): List<FontRow> = buildList {
add(FontRow.Group(KMR.strings.novel_font_group_generic))
GENERIC_FAMILIES.forEach { add(FontRow.Choice(it)) }

add(FontRow.Group(KMR.strings.novel_font_built_in))
NovelBuiltinFonts.Category.entries.forEach { category ->
    add(FontRow.Group(categoryLabel(category)))
    NovelBuiltinFonts.all
        .filter { it.category == category }
        .forEach { family -> add(FontRow.Choice(builtinChoice(context, family))) }
}

val user = NovelFonts.list(context)
if (user.isNotEmpty()) {
    add(FontRow.Group(KMR.strings.novel_font_user))
    user.forEach { value -> add(FontRow.Choice(userChoice(context, value))) }
}
}

/**
 * The four CSS spines, labelled by their own keywords.
 *
 * The keywords are labels rather than names of things — `serif` is an instruction to the engine, not
 * a typeface — and spelling them instead of renaming them keeps the row that renders and the value
 * that gets stored one and the same string, exactly as the old dropdown did.
 */
private val GENERIC_FAMILIES = listOf(
FontChoice("serif", "serif", FontFamily.Serif),
FontChoice("sans-serif", "sans-serif", FontFamily.SansSerif),
FontChoice("monospace", "monospace", FontFamily.Monospace),
FontChoice("cursive", "cursive", FontFamily.Cursive),
)

internal fun categoryLabel(category: NovelBuiltinFonts.Category): StringResource = when (category) {
NovelBuiltinFonts.Category.Serif -> KMR.strings.novel_font_group_serif
NovelBuiltinFonts.Category.Sans -> KMR.strings.novel_font_group_sans
NovelBuiltinFonts.Category.Mono -> KMR.strings.novel_font_group_mono
NovelBuiltinFonts.Category.Handwriting -> KMR.strings.novel_font_group_handwriting
}

/**
 * A bundled family's row, previewed by the asset the reader will actually be handed.
 *
 * Preview failures fall to the category's own generic (see [NovelFontPickerDialog]) — the row stays
 * selectable, which matters more than it being typographically correct.
 */
internal fun builtinChoice(context: Context, family: NovelBuiltinFonts.Family): FontChoice {
val preview = try {
    FontFamily(Typeface.createFromAsset(context.assets, family.asset))
} catch (_: Exception) {
    previewFallback(family.category)
}
return FontChoice(
    value = NovelBuiltinFonts.PREFIX + family.name,
    label = family.name,
    preview = preview,
)
}

/**
 * An imported family's row, previewed from the same file lookup the reader uses to load it.
 *
 * `NovelFonts.userFile` (not a hand-built path) is what makes the preview honest: the lookup that
 * shows the preview and the lookup the page loads through must be one and the same, or the picker
 * would happily offer a font the surface then cannot set.
 */
internal fun userChoice(context: Context, value: String): FontChoice {
val preview = NovelFonts.userFile(context, value)?.let { file ->
    try {
        FontFamily(Typeface.createFromFile(file))
    } catch (_: Exception) {
        null
    }
} ?: previewFallback(NovelBuiltinFonts.Category.Sans)
return FontChoice(
    value = value,
    label = familyLabel(value),
    preview = preview,
    deletable = true,
)
}

internal fun previewFallback(category: NovelBuiltinFonts.Category): FontFamily = when (category) {
NovelBuiltinFonts.Category.Serif -> FontFamily.Serif
NovelBuiltinFonts.Category.Sans -> FontFamily.SansSerif
NovelBuiltinFonts.Category.Mono -> FontFamily.Monospace
NovelBuiltinFonts.Category.Handwriting -> FontFamily.Cursive
}

/**
 * The custom hex entry: one field, validated against the same grammar the stylesheet reads, with the
 * OK button answering only a colour.
 *
 * Disabling OK rather than showing an error toast is the cheaper interface for a two-character
 * grammar — the field is *right there*, and the reader fixes it before ever leaving the dialog. The
 * hint states the expected shape so the disabled state is not a riddle.
 *
 * [initial] is prefilled so re-entering the dialog shows the colour already in use: a reader who
 * picked a colour and wants to nudge it must not be handed an empty field and the question of what
 * it was they had picked.
 */
