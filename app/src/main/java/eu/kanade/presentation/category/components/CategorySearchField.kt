package eu.kanade.presentation.category.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The category manager's search field, shared by Edit Categories and the Set Categories dialog.
 *
 * A flat pill rather than [androidx.compose.material3.OutlinedTextField]: the outlined field's
 * default 16dp vertical content padding makes it the tallest control on the screen, and the
 * dialog and the list it mirrors agree on one implementation so the two read as the same search.
 */
@Composable
fun CategorySearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: StringResource,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
