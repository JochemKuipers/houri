package eu.kanade.presentation.crash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.util.CrashLogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen
import java.io.File

@Composable
fun CrashScreen(
    exception: Throwable?,
    reportPath: String?,
    onRestartClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Load the full crash report file (if one was written) so the user sees the complete
    // stack trace + debug info, not just `exception.toString()`.
    var reportText by remember { mutableStateOf<String?>(null) }
    produceState(reportPath) {
        reportText = if (reportPath != null) {
            withContext(Dispatchers.IO) {
                runCatching { File(reportPath).readText() }.getOrNull()
            }
        } else {
            null
        }
    }

    InfoScreen(
        icon = Icons.Outlined.BugReport,
        headingText = stringResource(MR.strings.crash_screen_title),
        subtitleText = stringResource(MR.strings.crash_screen_description, stringResource(MR.strings.app_name)),
        acceptText = stringResource(MR.strings.pref_dump_crash_logs),
        onAcceptClick = {
            scope.launch {
                CrashLogUtil(context).dumpLogs(exception)
            }
        },
        rejectText = stringResource(MR.strings.crash_screen_restart_application),
        onRejectClick = onRestartClick,
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = MaterialTheme.padding.small)
                .clip(MaterialTheme.shapes.small)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val text = reportText ?: exception?.toString() ?: "Unknown error"
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = text,
                    modifier = Modifier
                        .padding(all = MaterialTheme.padding.small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun CrashScreenPreview() {
    TachiyomiPreviewTheme {
        CrashScreen(exception = RuntimeException("Dummy"), reportPath = null) {}
    }
}
