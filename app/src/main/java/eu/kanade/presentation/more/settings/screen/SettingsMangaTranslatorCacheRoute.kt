package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File

@Serializable
data object SettingsMangaTranslatorCacheRoute : NavKey

// KMK --> Saved translations live in the spine's disk page store under
// filesDir/mtl_pages/saved/<safeKey>, where safeKey starts with "<mangaId>_<chapterId>_…",
// so per-manga grouping is recovered from the key prefix. The .title sidecar rides next
// to the translated page (safeKey + ".title") and names the entry after library removal.
private data class CacheEntry(val mangaId: Long, val title: String?, val chapters: Int, val pages: Int, val sizeKb: Long)
// KMK <--

private fun savedPagesDir(context: android.content.Context): File =
    File(context.filesDir, "mtl_pages/saved")

@Composable
fun SettingsMangaTranslatorCacheScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    var entries by remember { mutableStateOf<List<CacheEntry>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        entries = withIOContext { listSavedEntries(context) }
    }

    Column(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Per-Manga Translation Cache", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Wipe translated pages for a specific manga — works even after removing it from library. Saved translations are cached permanently under filesDir/mtl_pages/saved/. Tap Wipe to delete all translated pages for that manga.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                globalAppGraph.mtlPageStore.clear()
                context.toast("All translation cache cleared")
                refreshTick++
            }) { Text("Clear All") }
            TextButton(onClick = { backStack.removeLastOrNull() }) { Text("Back") }
        }
        if (entries.isEmpty()) {
            Text(text = "No cached translations", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        Text(text = "${entries.size} manga with cached translations", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = entry.title ?: "Manga ${entry.mangaId}", style = MaterialTheme.typography.titleSmall)
                        Text(text = "${entry.chapters} chapters · ${entry.pages} pages · ${entry.sizeKb} KB", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                wipeSavedPages(context, entry.mangaId)
                                context.toast("Wiped manga ${entry.mangaId}")
                                refreshTick++
                            }) { Text("Wipe") }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun listSavedEntries(context: android.content.Context): List<CacheEntry> {
    val dir = savedPagesDir(context)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".title") && !it.name.endsWith(".tmp") }
        ?.mapNotNull { file ->
            file.name.substringBefore('_').toLongOrNull()?.let { mangaId -> mangaId to file }
        }
        ?.groupBy({ it.first }, { it.second })
        ?.map { (mangaId, files) ->
            CacheEntry(
                mangaId = mangaId,
                title = resolveTitle(context, mangaId, files),
                chapters = files.mapNotNull { it.name.substringAfter('_').substringBefore('_').takeIf(String::isNotBlank) }.distinct().size,
                pages = files.size,
                sizeKb = files.sumOf { it.length() } / 1024,
            )
        }
        ?.sortedByDescending { it.pages }
        ?: emptyList()
}

private fun wipeSavedPages(context: android.content.Context, mangaId: Long) {
    savedPagesDir(context).listFiles()
        ?.filter { it.name.startsWith("${mangaId}_") }
        ?.forEach { runCatching { it.delete() } }
}

// KMK --> Sidecar first (survives library removal), then the database (covers caches
// saved before sidecars existed), then null so the caller falls back to the raw id.
private suspend fun resolveTitle(context: android.content.Context, mangaId: Long, files: List<File>): String? {
    files.firstNotNullOfOrNull { page ->
        val sidecar = File(page.absolutePath + ".title")
        sidecar.takeIf { it.isFile }?.readText()?.takeIf(String::isNotBlank)
    }?.let { return it }
    return runCatching { globalAppGraph.getManga.await(mangaId)?.title }.getOrNull()
}
// KMK <--
