package eu.kanade.presentation.more.settings.screen.debug

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.logFileDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LogFileEntry(
    val file: File,
    val name: String,
    val size: String,
    val modified: String,
)

/**
 * In-app log viewer. Lists every `houri_*.log` file (crash reports, manual dumps, logcat rings)
 * in the app's log dir, and lets the user tap one to read it full-screen with copy/share.
 *
 * This is the "read the logs without leaving the app" half of the crash/log system: a user who
 * hit a non-crash problem can open the last dump, copy the relevant tail into Discord, and share
 * it — no `adb`, no file manager, no waiting for a crash screen.
 */
@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current

    var files by remember { mutableStateOf<List<LogFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<LogFileEntry?>(null) }

    produceState(Unit) {
        loading = true
        files = withContext(Dispatchers.IO) { listLogFiles(context) }
        loading = false
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = if (selected != null) "Log" else "Logs",
                navigateUp = {
                    if (selected != null) {
                        selected = null
                    } else {
                        backStack.removeLastOrNull()
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val selectedEntry = selected
        if (selectedEntry != null) {
            LogContent(
                entry = selectedEntry,
                paddingValues = paddingValues,
            )
        } else {
            LogFileList(
                files = files,
                loading = loading,
                paddingValues = paddingValues,
                onSelect = { selected = it },
            )
        }
    }
}

@Composable
private fun LogFileList(
    files: List<LogFileEntry>,
    loading: Boolean,
    paddingValues: PaddingValues,
    onSelect: (LogFileEntry) -> Unit,
) {
    if (loading) {
        Text(
            text = "Loading…",
            modifier = Modifier.padding(paddingValues).padding(16.dp),
        )
        return
    }
    if (files.isEmpty()) {
        Text(
            text = "No log files yet. Logs appear here after a crash, a manual dump, or when the app has been running long enough to fill the ring.",
            modifier = Modifier.padding(paddingValues).padding(16.dp),
        )
        return
    }
    ScrollbarLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = paddingValues,
    ) {
        items(files, key = { it.file.absolutePath }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.size} · ${entry.modified}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun LogContent(
    entry: LogFileEntry,
    paddingValues: PaddingValues,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    produceState(entry.file) {
        text = withContext(Dispatchers.IO) {
            runCatching { entry.file.readText() }.getOrElse { "Failed to read file: ${it.message}" }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                text?.let { context.copyToClipboard(entry.name, it) }
            }) { Text("Copy") }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        entry.file.getUriCompat(context).toShareIntent(context, "text/plain"),
                    )
                }.onFailure { context.toast("Failed to share: ${it.message}") }
            }) { Text("Share") }
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = text ?: "Loading…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun listLogFiles(context: Context): List<LogFileEntry> {
    val dir = context.logFileDir()
    val files = dir.listFiles { f ->
        f.isFile && f.name.startsWith("houri_") && f.name.endsWith(".log")
    } ?: return emptyList()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return files
        .sortedByDescending { it.lastModified() }
        .map { file ->
            LogFileEntry(
                file = file,
                name = file.name,
                size = formatSize(file.length()),
                modified = dateFormat.format(Date(file.lastModified())),
            )
        }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / 1024 / 1024} MB"
}
