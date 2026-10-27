package eu.kanade.presentation.reader.settings

import android.content.Context
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.NovelTypographyPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelBuiltinFonts
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelFonts
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelFonts.ImportResult
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelPageLayout
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The text reader's own settings, as a tab beside the image reader's.
 *
 * ### Why these rows are hand-built
 *
 * The modular settings framework (`settings/framework/AGENTS.md`) covers boolean, text, int-slider,
 * multi-select and enum-list keys. Two thirds of this reader's settings are none of those: they are
 * floats the stylesheet reads as fractions of the page, and tri-states stored as the string
 * `"follow"`. Projecting a float margin into a whole percent to fit `intSliderSetting` would quantise
 * the value on every drag, and no representation of "follow the publisher" exists in a switch. So the
 * rows are built from `presentation-core`'s settings components — the same ones `GeneralPage` uses —
 * against the store that is already the only writer, [NovelTypographyPreferences].
 *
 * ### Why a tab rather than rows inside the general page
 *
 * A book has none of the image reader's controls: no page transition, no scale type, no crop, no
 * spread. Putting these beside them would leave the general page half rows that do nothing for the
 * chapter in front of the reader. A tab also makes its visibility honest — it is offered exactly
 * when a text loader is showing, which is the same signal that chose the renderer.
 *
 * ### One value, one source
 *
 * Every row reads from [NovelTypographyPreferences.values] and writes through the same store, so the
 * page cannot show a stale number while the open document re-lays out behind it. Reading the individual
 * preferences instead would mean two paths to the same state, and a slider that disagreed with the
 * page it is supposed to be adjusting.
 *
 * Order is legibility first, because that is the order in which a page stops being readable: size and
 * colours, then what changes how lines break, then measurements.
 */
// KMK -->
@Composable
internal fun NovelTypographyPage(
    novelPrefs: NovelTypographyPreferences = globalAppGraph.novelTypographyPreferences,
) {
    // `values()`, not `changes()`: the page has to show what is stored, and `changes()` deliberately
    // drops its first emission so the reader is not told about a value it already has. Without the
    // leading value this screen opens on the defaults and only corrects itself once a preference is
    // touched - which reads as "my settings did not save" rather than as a display bug.
    val typography by novelPrefs.values().collectAsState(NovelTypography.DEFAULT)
    // KMK --> The page mode is not typography (it survives every slider drag unchanged), so it is
    // collected on its own - from its own preference, through the same `collectAsState` that hands
    // the page a value the moment it opens.
    val pageMode by novelPrefs.pageModePref.collectAsState()
    // KMK <--

    HeadingItem(stringResource(KMR.strings.novel_reader_typography))

    // ---------------------------------------------------------------- how the book moves

    // First, because it is the most consequential choice on the page: it decides what a tap does.
    // A change re-renders the open document straight away - the reader sees the book turn into
    // pages behind the settings sheet, which is the only honest preview a mode can have.
    SettingsChipRow(KMR.strings.novel_page_mode) {
        NovelPageLayout.PageMode.entries.forEach { mode ->
            FilterChip(
                selected = pageMode == mode.ordinal,
                onClick = { novelPrefs.setPageMode(mode) },
                label = { Text(stringResource(pageModeLabel(mode))) },
            )
        }
    }

    // ---------------------------------------------------------------- legibility

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_font_size),
        value = typography.fontSizePt,
        valueRange = NovelTypography.MIN_FONT_SIZE_PT..NovelTypography.MAX_FONT_SIZE_PT,
        valueString = "${typography.fontSizePt}pt",
        onChange = { novelPrefs.fontSize.set(it) },
    )

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_font_scale),
        value = percent(typography.fontScale),
        valueRange = percent(NovelTypography.MIN_FONT_SCALE)..percent(NovelTypography.MAX_FONT_SCALE),
        valueString = "${percent(typography.fontScale)}%",
        onChange = { novelPrefs.fontScale.set(it / 100f) },
    )

    // KMK --> The font row opens the picker rather than naming a value in place: a font is chosen
    // with the eyes, and every family previews in its own face inside [NovelFontPickerDialog].
    val context = LocalContext.current
    var showFontPicker by remember { mutableStateOf(false) }
    var fontVersion by remember { mutableIntStateOf(0) }
    FontRow(family = typography.fontFamily, onClick = { showFontPicker = true })

    if (showFontPicker) {
        NovelFontPickerDialog(
            context = context,
            version = fontVersion,
            selected = typography.fontFamily,
            onSelect = { value ->
                novelPrefs.fontFamily.set(value)
                showFontPicker = false
            },
            onDelete = { value ->
                if (NovelFonts.delete(context, value)) {
                    // The deleted family would still be the page's own `font-family` request;
                    // naming a font that no longer exists leaves the engine falling back to its
                    // default for reasons the settings screen would show no trace of. Saying so
                    // here is one write against a page opening on the system serif unexplained.
                    if (typography.fontFamily == value) {
                        novelPrefs.fontFamily.set(NovelTypography.DEFAULT_FONT_FAMILY)
                    }
                    context.toast(KMR.strings.novel_font_removed)
                }
                fontVersion++
            },
            onChanged = { fontVersion++ },
            onDismiss = { showFontPicker = false },
        )
    }
    // KMK <--

    // KMK --> Colours are the named palette plus one free-form entry. The chips cover the colours a
    // reader reaches in one tap; the custom entry exists because "only what we named" is the kind of
    // limit that reads as a bug the first time it gets in the way. Both paths store the same
    // canonical `#rrggbb` — the hex field is normalised on confirm, not saved as typed — so the
    // contrast rule can always reason about what is stored, and a picked colour can no more render
    // black-on-black than a chip can. See NovelTypography.effectiveFontColor.
    var editingTextColor by remember { mutableStateOf(false) }
    var editingBackgroundColor by remember { mutableStateOf(false) }

    SettingsChipRow(KMR.strings.pref_novel_font_color) {
        ColorChips(
            choices = FONT_COLORS,
            selected = typography.fontColor.orEmpty(),
            onCustom = { editingTextColor = true },
            onSelect = { novelPrefs.fontColor.set(it) },
        )
    }

    SettingsChipRow(KMR.strings.pref_novel_background_color) {
        ColorChips(
            choices = BACKGROUND_COLORS,
            selected = typography.backgroundColor.orEmpty(),
            onCustom = { editingBackgroundColor = true },
            onSelect = { novelPrefs.backgroundColor.set(it) },
        )
    }

    if (editingTextColor) {
        HexColorDialog(
            initial = customColorOrEmpty(typography.fontColor, FONT_COLORS),
            onConfirm = { value ->
                novelPrefs.fontColor.set(value)
                editingTextColor = false
            },
            onDismiss = { editingTextColor = false },
        )
    }

    if (editingBackgroundColor) {
        HexColorDialog(
            initial = customColorOrEmpty(typography.backgroundColor, BACKGROUND_COLORS),
            onConfirm = { value ->
                novelPrefs.backgroundColor.set(value)
                editingBackgroundColor = false
            },
            onDismiss = { editingBackgroundColor = false },
        )
    }
    // KMK <--

    CheckboxItem(
        label = stringResource(KMR.strings.novel_show_images),
        pref = novelPrefs.showImages,
    )

    // The cover as a first page: applies to the next chapter opened - the spine of the book on
    // screen was already built - which is also when every other source-level preference applies.
    CheckboxItem(
        label = stringResource(KMR.strings.novel_cover_first),
        pref = novelPrefs.coverFirst,
    )

    // ---------------------------------------------------------------- shape

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_line_spacing),
        value = percent(typography.lineSpacing),
        valueRange = percent(NovelTypography.MIN_LINE_SPACING)..percent(NovelTypography.MAX_LINE_SPACING),
        valueString = decimal(typography.lineSpacing),
        onChange = { novelPrefs.lineSpacing.set(it / 100f) },
    )

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_paragraph_spacing),
        value = percent(typography.paragraphSpacing),
        valueRange = 0..percent(NovelTypography.MAX_PARAGRAPH_SPACING),
        valueString = decimal(typography.paragraphSpacing),
        onChange = { novelPrefs.paragraphSpacing.set(it / 100f) },
    )

    SliderItem(
        label = stringResource(KMR.strings.pref_novel_font_spacing),
        value = percent(typography.fontSpacing),
        valueRange = percent(-0.2f)..percent(1f),
        valueString = decimal(typography.fontSpacing),
        onChange = { novelPrefs.fontSpacing.set(it / 100f) },
    )

    // ---------------------------------------------------------------- margins

    HeadingItem(stringResource(KMR.strings.pref_novel_margins))

    MarginSlider(stringResource(KMR.strings.pref_novel_margin_left), typography.marginLeft) {
        novelPrefs.marginLeft.set(it)
    }
    MarginSlider(stringResource(KMR.strings.pref_novel_margin_right), typography.marginRight) {
        novelPrefs.marginRight.set(it)
    }
    MarginSlider(stringResource(KMR.strings.pref_novel_margin_top), typography.marginTop) {
        novelPrefs.marginTop.set(it)
    }
    MarginSlider(stringResource(KMR.strings.pref_novel_margin_bottom), typography.marginBottom) {
        novelPrefs.marginBottom.set(it)
    }

    // ---------------------------------------------------------------- tri-state
    //
    // These are the ones that must be able to say "leave the book alone", so they are not checkboxes
    // with an off state: an off state would mean "force this off", which is the setting these exist
    // to avoid. Bold text and force dark join the original three for the same reason. See the
    // tri-state rules in ui/reader/AGENTS.md.

    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_justified),
        state = typography.justified.toTriState(),
        onClick = { novelPrefs.justified = typography.justified.next() },
    )

    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_hyphenation),
        state = typography.hyphenation.toTriState(),
        onClick = { novelPrefs.hyphenation = typography.hyphenation.next() },
    )

    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_vertical_writing),
        state = typography.verticalWriting.toTriState(),
        onClick = { novelPrefs.verticalWriting = typography.verticalWriting.next() },
    )

    // The one whose default is not "follow the book": it used to indent unconditionally, so "on" is
    // what every existing reader is reading. See NovelTypographySettingKeys.PARAGRAPH_INDENT for why
    // defaulting it the other way would have restyled everyone's library on upgrade.
    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_paragraph_indent),
        state = typography.paragraphIndent.toTriState(),
        onClick = { novelPrefs.paragraphIndent = typography.paragraphIndent.next() },
    )

    // Bold body text. On, the sheet emits a real `font-weight: 700 !important` — which is why the
    // bundle ships *variable* fonts: every serif here carries its own bold rather than letting the
    // engine synthesise one. Off forces the publisher's own weights, which is also not a no-op.
    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_bold_text),
        state = typography.boldText.toTriState(),
        onClick = { novelPrefs.boldText = typography.boldText.next() },
    )

    // The reader's own dark palette, over the book's. Lives with the tri-states rather than beside
    // the colour chips because its whole point is overriding a document that the palette cannot
    // reach: a colour chosen on a class the chips never see.
    TriStateItem(
        label = stringResource(KMR.strings.pref_novel_force_dark),
        state = typography.forceDark.toTriState(),
        onClick = { novelPrefs.forceDark = typography.forceDark.next() },
    )

    // ---------------------------------------------------------------- custom injection
    //
    // Reader-supplied code, in the FontRow's visual grammar: a checkbox row carrying a supporting
    // line — the JS row's is the security note the feature owes the reader — and an edit row
    // beneath each toggle, shown only while the toggle is on, so a control that edits a value the
    // toggle silences never reads as broken. CSS rides the live stylesheet swap, so an edit
    // applies to the open book the moment OK is pressed; JS is a build-time injection, so a
    // change lands on the next page turned — the editors' hints say which is which.

    HeadingItem(stringResource(KMR.strings.novel_custom_code))

    val customCssEnabled by novelPrefs.customCssEnabled.collectAsState()
    val customJsEnabled by novelPrefs.customJsEnabled.collectAsState()
    var editingCustomCss by remember { mutableStateOf(false) }
    var editingCustomJs by remember { mutableStateOf(false) }

    ToggleRow(
        label = stringResource(KMR.strings.pref_novel_custom_css),
        support = stringResource(KMR.strings.pref_novel_custom_css_support),
        checked = customCssEnabled,
        onChange = { novelPrefs.customCssEnabled.set(it) },
    )
    if (customCssEnabled) {
        EditCodeRow(
            label = stringResource(KMR.strings.novel_custom_css_edit),
            onClick = { editingCustomCss = true },
        )
    }

    ToggleRow(
        label = stringResource(KMR.strings.pref_novel_custom_js),
        support = stringResource(KMR.strings.pref_novel_custom_js_support),
        checked = customJsEnabled,
        onChange = { novelPrefs.customJsEnabled.set(it) },
    )
    if (customJsEnabled) {
        EditCodeRow(
            label = stringResource(KMR.strings.novel_custom_js_edit),
            onClick = { editingCustomJs = true },
        )
    }

    if (editingCustomCss) {
        CustomCodeDialog(
            title = stringResource(KMR.strings.novel_custom_css_edit),
            hint = stringResource(KMR.strings.novel_custom_css_hint),
            initial = novelPrefs.customCss.get(),
            onConfirm = { value ->
                novelPrefs.customCss.set(value)
                editingCustomCss = false
            },
            onDismiss = { editingCustomCss = false },
        )
    }

    if (editingCustomJs) {
        CustomCodeDialog(
            title = stringResource(KMR.strings.novel_custom_js_edit),
            hint = stringResource(KMR.strings.novel_custom_js_hint),
            initial = novelPrefs.customJs.get(),
            onConfirm = { value ->
                novelPrefs.customJs.set(value)
                editingCustomJs = false
            },
            onDismiss = { editingCustomJs = false },
        )
    }
}

/**
 * A margin as a whole percent of the page.
 *
 * Percent because that is the unit the reader thinks in, and an integer because a slider with
 * hundredth-of-a-percent resolution is not a control. The division happens on the way out only, so
 * nothing is quantised in the store and the stylesheet still reads the fraction the reader dragged to.
 *
 * The ceiling is the same one the stylesheet enforces, [NovelTypography.MAX_MARGIN] reached through
 * the value's own axis — a horizontal margin is a fraction of the page *width* and a vertical one of
 * its *height*. Reading the limit off the stored value would make the slider's own range depend on
 * what it is currently set to, so the cap is the axis-independent constant instead.
 */
@Composable
private fun MarginSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    SliderItem(
        label = label,
        value = percent(value),
        valueRange = 0..percent(NovelTypography.MAX_MARGIN),
        valueString = "${percent(value)}%",
        onChange = { onChange(it / 100f) },
    )
}

/**
 * The font row, showing what is currently in use, opening [NovelFontPickerDialog] on tap.
 *
 * Hand-built rather than an `IconItem` because the reader chooses a font twice — once as a name and
 * once with the eyes — and the name alone is what the currently-in-use half has to carry: a setting
 * that hides which font is in force behind a label would make every mismatch between what the picker
 * shows and what the page renders undiagnosable.
 */
@Composable
private fun FontRow(family: String, onClick: () -> Unit) {
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
private fun familyLabel(family: String): String =
    family.removePrefix(NovelBuiltinFonts.PREFIX).removePrefix(NovelFonts.PREFIX)

/**
 * One family in the picker: what is stored to select it, what is displayed, and what face the label
 * is drawn in.
 *
 * [preview] is a `FontFamily`, not a `Typeface`, because Compose resolves the sample text with it as
 * it would resolve any text, identical for every row but the face — which is what makes the list a
 * specimen sheet rather than names to be taken on trust.
 */
private data class FontChoice(
    val value: String,
    val label: String,
    val preview: FontFamily,
    val deletable: Boolean = false,
)

private sealed interface FontRow {
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
private fun NovelFontPickerDialog(
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
private fun FontDialogHeader(text: String) {
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
private fun FontChoiceRow(
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
private fun pickerRows(context: Context): List<FontRow> = buildList {
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

private fun categoryLabel(category: NovelBuiltinFonts.Category): StringResource = when (category) {
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
private fun builtinChoice(context: Context, family: NovelBuiltinFonts.Family): FontChoice {
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
private fun userChoice(context: Context, value: String): FontChoice {
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

private fun previewFallback(category: NovelBuiltinFonts.Category): FontFamily = when (category) {
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
@Composable
private fun HexColorDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.novel_color_custom)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(KMR.strings.novel_color_hex_label)) },
                supportingText = { Text(stringResource(KMR.strings.novel_color_hex_hint)) },
                isError = text.isNotBlank() && NovelTypography.normalizedColor(text) == null,
            )
        },
        confirmButton = {
            TextButton(
                enabled = NovelTypography.normalizedColor(text) != null,
                onClick = { NovelTypography.normalizedColor(text)?.let(onConfirm) },
            ) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/**
 * A checkbox row carrying a supporting line under the label.
 *
 * Hand-built beside [CheckboxItem] for one reason: the custom-JavaScript toggle has to carry its
 * security note where the reader cannot miss it, and `CheckboxItem` has no second line. The
 * whole row toggles, like every switch row in the framework; the checkbox itself answers nothing
 * because the row already did.
 */
@Composable
private fun ToggleRow(label: String, support: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clickable { onChange(!checked) }
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
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = support,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/**
 * The row that opens one of the code editors, in [FontRow]'s grammar: a weight-one label and an
 * affordance at the end, so the row reads as a button rather than as a label.
 */
@Composable
private fun EditCodeRow(label: String, onClick: () -> Unit) {
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
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The editor for reader-supplied CSS or JavaScript: one field, no validation, the OK button always
 * armed.
 *
 * Code cannot be validated — half the point of the feature is that the reader knows something the
 * reader does not — so unlike [HexColorDialog] there is no grammar to gate OK on. What the field
 * *can* do is be honest about what it holds: monospaced, multiline, and wide, because it is code.
 * [hint] is where the semantics live: when the code runs, and against what.
 *
 * The value is stored verbatim, including empty — clearing the field and confirming is how a
 * reader removes their code, and it is a better answer than a disabled OK refusing to let them.
 */
@Composable
private fun CustomCodeDialog(
    title: String,
    hint: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = { Text(hint) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/**
 * The value the hex field opens with: the current colour when it is a custom one, empty otherwise.
 *
 * The fixed palette's own values would round-trip through the field as editable text, and a reader
 * editing "An off-white" that turns out to be the string `#eff2f5` learns nothing from it but the
 * spelling of a chip they could tap. Only genuinely custom values are in scope for editing.
 */
private fun customColorOrEmpty(color: String?, choices: List<Pair<String, StringResource>>): String =
    color?.takeUnless { value -> choices.any { it.first == value } }.orEmpty()

/**
 * One row of colour choices, in the order they are offered, plus the custom entry.
 *
 * Selection is read from [selected] rather than remembered per chip, so exactly one chip is ever
 * highlighted and the row follows a change made anywhere else — including the derived one the reader
 * has not made yet, which is the whole point of the background/foreground pairing.
 *
 * The empty value is "follow the book" and is listed first, because it is what a reader wants to get
 * back to after experimenting. "Custom…" trails every palette: a picker that puts its free-form
 * entry in the middle of named choices makes the named ones look like decorations.
 */
@Composable
private fun FlowRowScope.ColorChips(
    choices: List<Pair<String, StringResource>>,
    selected: String,
    onCustom: () -> Unit,
    onSelect: (String) -> Unit,
) {
    choices.forEach { (value, label) ->
        FilterChip(
            selected = value == selected,
            onClick = { onSelect(value) },
            label = { Text(stringResource(label)) },
        )
    }
    FilterChip(
        // A custom colour must be *some* colour, so the unselected state of this chip is the same
        // as the palette's: a blank string, which "follow the book" already owns.
        selected = selected.isNotBlank() && choices.none { it.first == selected },
        onClick = onCustom,
        label = { Text(stringResource(KMR.strings.novel_color_custom)) },
    )
}

/** `null` — follow the publisher — is the row a reader starts from, so it is the unselected state. */
private fun Boolean?.toTriState(): TriState = when (this) {
    true -> TriState.ENABLED_IS
    false -> TriState.ENABLED_NOT
    null -> TriState.DISABLED
}

// KMK -->
/** The chip label for one page mode. */
private fun pageModeLabel(mode: NovelPageLayout.PageMode): StringResource = when (mode) {
    NovelPageLayout.PageMode.CONTINUOUS -> KMR.strings.novel_page_mode_continuous
    NovelPageLayout.PageMode.PAGED -> KMR.strings.novel_page_mode_paged
    NovelPageLayout.PageMode.DOUBLE -> KMR.strings.novel_page_mode_double
}
// KMK <--

/** The next value round the three-state cycle, ending back at "follow the book". */
private fun Boolean?.next(): Boolean? = when (this) {
    null -> true
    true -> false
    false -> null
}

/** A fraction the store holds as a float to the whole percent a slider can express. */
private fun percent(fraction: Float): Int = (fraction * 100).roundToInt()

/**
 * Locale-independent, because the stylesheet is.
 *
 * `String.format` with the default locale puts a comma where the dot belongs in much of the world, and
 * "1,60" is not a number a reader recognises as a line height — nor one `fmt` in the stylesheet would
 * parse. Same reason `NovelTypographyCss.fmt` pins `Locale.ROOT`.
 */
private fun decimal(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

/** What the OpenDocument picker first offers: the `font` MIME prefix first, then the types providers mislabel. */
private val FONT_PICK_MIME_TYPES = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/font-woff",
    "application/octet-stream",
)

/**
 * (stored value, label). The empty value is "follow the book" and leads every list.
 *
 * Text and background are deliberately different lists rather than one palette for both. They are not
 * independent choices — a background implies a foreground — so the background offers a wider range of
 * papers and the text offers only extremes, which is all a reader needs when the colour is being
 * derived for them anyway.
 */
private val FONT_COLORS: List<Pair<String, StringResource>> = listOf(
    "" to KMR.strings.novel_follow_publisher,
    "#ffffff" to KMR.strings.novel_color_white,
    "#e8e6e3" to KMR.strings.novel_color_light_grey,
    "#202124" to KMR.strings.novel_color_dark_grey,
    "#000000" to KMR.strings.novel_color_black,
)

private val BACKGROUND_COLORS: List<Pair<String, StringResource>> = listOf(
    "" to KMR.strings.novel_follow_publisher,
    "#ffffff" to KMR.strings.novel_color_white,
    "#f5f2e8" to KMR.strings.novel_color_cream,
    "#e8e8e8" to KMR.strings.novel_color_light_grey,
    "#d9c7a7" to KMR.strings.novel_color_paper,
    "#1a1614" to KMR.strings.novel_color_sepia,
    "#202124" to KMR.strings.novel_color_dark_grey,
    "#121212" to KMR.strings.novel_color_black,
)
// KMK <--
