// KMK -->
package eu.kanade.presentation.reader

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelHighlights
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelTypography
import eu.kanade.tachiyomi.ui.reader.viewer.novel.toColorIntOrNull
import mihon.core.archive.EpubArchiveUrls
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.io.ByteArrayInputStream

/**
 * The footnote popup, Moon+ Reader style: the note's own markup in a small window over the book,
 * instead of a turn to the notes document.
 *
 * The note renders in a **WebView**, not as spans — a footnote is book markup and may carry ruby,
 * tables or an illustration, and converting that to spans is the "re-implement a browser" trap the
 * novel reader exists to avoid. It is built exactly like a document: the note markup wrapped in a
 * shell whose stylesheet is the open document's own ([typographyCss]), loaded at the notes
 * document's base URL so the note's relative references resolve — an illustration beside the note
 * streams out of the archive through [resources], the same lease the page itself reads through.
 *
 * JavaScript is off in this window, deliberately: the note is static content, the reader already
 * trusts the *document* to run the book's scripts because reading demands it, and a popup has no
 * such demand — so a book that put a script in a footnote gets less surface here than on the page.
 */
@Composable
fun NovelFootnoteDialog(
    noteHtml: String,
    baseUrl: String,
    typography: NovelTypography,
    typographyCss: String,
    resources: TextResourceSource?,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                WebViewNote(
                    noteHtml = noteHtml,
                    baseUrl = baseUrl,
                    typography = typography,
                    typographyCss = typographyCss,
                    resources = resources,
                )
                TextButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            }
        }
    }
}

/** The note itself, as a small WebView the book's serving path answers. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewNote(
    noteHtml: String,
    baseUrl: String,
    typography: NovelTypography,
    typographyCss: String,
    resources: TextResourceSource?,
) {
    val html = remember(noteHtml, typographyCss) {
        // The popup's palette is the document's: the same canvas and derived text colour the
        // page wears, so a dark-theme reader's note does not flash white. The height cap is the
        // popup's shape — a genuinely long note scrolls inside the window rather than growing
        // into a second document, which is what navigating to it was for.
        val background = typography.effectiveBackgroundColor ?: "#ffffff"
        val foreground = typography.effectiveFontColor ?: "#000000"
        buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<style>html{background:").append(background).append(";}")
            append("body{color:").append(foreground).append(";margin:12px;}")
            append("</style><style>").append(typographyCss).append("</style></head><body>")
            append(noteHtml)
            append("</body></html>")
        }
    }
    val backgroundColor = remember(typography) {
        (typography.effectiveBackgroundColor ?: "#ffffff").toColorIntOrNull() ?: 0xFFFFFFFF.toInt()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(backgroundColor)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (!EpubArchiveUrls.isArchiveUrl(url)) return null
                        val stream = runCatching { resources?.openResource(url) }.getOrNull()
                            ?: return notFound()
                        val mime = runCatching { resources?.contentTypeFor(url) }.getOrNull()
                            ?: EpubArchiveUrls.contentTypeForPath(
                                EpubArchiveUrls.archivePathOf(url) ?: "",
                            )
                            ?: "application/octet-stream"
                        val encoding = if (mime.startsWith("text/") || mime.contains("xml")) "UTF-8" else null
                        return WebResourceResponse(mime, encoding, stream)
                    }

                    // A note's own links navigate the popup; the book's business stays in the
                    // book. Refuse rather than load: the popup is a window, not a reader.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = true

                    private fun notFound(): WebResourceResponse = WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        404,
                        "Not Found",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            // A recomposition with a different note (a second tap after dismiss, a new build)
            // re-loads; the same content does not, so a palette-only re-compose keeps its scroll.
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
    )
}

/**
 * The selection action sheet, Moon+ Reader's annotation surface: four pens, then copy and
 * look-up. When the selection arrived carrying an existing highlight's id, the pens give way to
 * removal — tapping inside a highlight and picking a colour again is how a highlight changes, so
 * "remove" must not sit where "add" would fight it.
 *
 * The actions themselves go through the viewer: it owns the per-chapter store and the live
 * document, and the sheet only decides.
 */
@Composable
fun NovelHighlightSheet(
    selection: NovelHighlights.Selection,
    onHighlight: (color: Int) -> Unit,
    onRemove: () -> Unit,
    onCopy: () -> Unit,
    onLookup: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = selection.quote.let { if (it.length > 80) it.take(77) + "…" else it },
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column {
                if (selection.existingId == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                        NovelHighlights.COLORS.forEachIndexed { index, hex ->
                            val color = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable {
                                        onHighlight(index)
                                        onDismissRequest()
                                    },
                            )
                        }
                    }
                }
                Text(
                    text = selection.quote.let { if (it.length > 120) it.take(117) + "…" else it },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
            }
        },
        confirmButton = {
            if (selection.existingId != null) {
                TextButton(
                    onClick = {
                        onRemove()
                        onDismissRequest()
                    },
                ) { Text(stringResource(KMR.strings.action_remove_highlight)) }
            }
            TextButton(
                onClick = {
                    onLookup()
                    onDismissRequest()
                },
            ) { Text(stringResource(KMR.strings.action_lookup_selection)) }
            TextButton(
                onClick = {
                    onCopy()
                    onDismissRequest()
                },
            ) { Text(stringResource(KMR.strings.action_copy_text)) }
        },
    )
}
// KMK <--
