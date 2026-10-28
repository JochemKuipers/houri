package exh.log

import android.content.Context
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import mihon.app.di.globalAppGraph
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.time.Duration.Companion.nanoseconds

@Composable
fun DebugModeOverlay() {
    val tabletUi = isTabletUi()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .only(if (tabletUi) WindowInsetsSides.Start else WindowInsetsSides.Bottom),
                )
                // Clear the app's own navigation chrome, not just the system gesture area: the
                // bottom bar on phones and the rail on tablets are both 80dp, and without this
                // the overlay sat directly on top of the Library tab / first rail item.
                .padding(
                    bottom = if (tabletUi) 0.dp else 80.dp,
                    start = if (tabletUi) 80.dp else 0.dp,
                )
                .align(Alignment.BottomStart)
                .background(Color(0x7F000000), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            FpsDebugModeOverlay()
            EHDebugModeOverlay()
        }
    }
}

@Composable
private fun FpsDebugModeOverlay() {
    val state = remember { FpsState(FpsState.DEFAULT_INTERVAL) }
    val format = remember {
        DecimalFormat(
            "0.0",
            DecimalFormatSymbols.getInstance(Locale.ENGLISH),
        )
    }

    val fps = state.fps
    val frameTime = state.frameTimeMillis
    val text = remember(fps, frameTime) {
        if (fps <= 0.0) {
            "measuring…"
        } else {
            "${format.format(fps)} fps · ${format.format(frameTime)} ms"
        }
    }
    // Colour-coded so a bad frame rate is legible at a glance instead of being one more
    // number to parse while scrolling: green is healthy, amber is a real hitch, red is
    // the "something is re-composing under me" band.
    val color = when {
        fps >= 50.0 -> Color(0xFF81C784)
        fps >= 30.0 -> Color(0xFFFFD54F)
        fps > 0.0 -> Color(0xFFE57373)
        else -> Color.White
    }
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun EHDebugModeOverlay() {
    val scope = rememberCoroutineScope()
    val enableSourceBlacklist by remember {
        globalAppGraph.sourcePreferences.enableSourceBlacklist().asState(scope)
    }
    val context = LocalContext.current
    Text(
        text = remember(enableSourceBlacklist) {
            buildInfo(context, enableSourceBlacklist)
        },
        color = Color.White,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1f.sp,
    )
}

private fun buildInfo(context: Context, sourceBlacklist: Boolean) = buildAnnotatedString {
    withStyle(SpanStyle(color = Color.Green)) {
        append("===[ ")
        append(context.stringResource(MR.strings.app_name))
        append(" ]===")
    }
    append('\n')
    appendItem("Build type:", BuildConfig.BUILD_TYPE)
    appendItem("Debug mode:", isDebugBuildType.asEnabledString())
    appendItem("Version code:", BuildConfig.VERSION_CODE.toString())
    // Short SHA: the full hash wraps the overlay block onto an extra line for no
    // diagnostic value - anything that needs the full one is a build artifact away.
    appendItem("Commit SHA:", BuildConfig.COMMIT_SHA.take(8))
    appendItem("Log level:", EHLogLevel.currentLogLevel.name.lowercase(Locale.getDefault()))
    appendItem("Source blacklist:", sourceBlacklist.asEnabledString(), newLine = false)
}

fun AnnotatedString.Builder.appendItem(title: String, item: String, newLine: Boolean = true) {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        append(title)
    }
    append(' ')
    append(item)
    if (newLine) {
        append('\n')
    }
}

private fun Boolean.asEnabledString() = if (this) "enabled" else "disabled"

private class FpsState(private val interval: Int) :
    Choreographer.FrameCallback,
    RememberObserver {

    private val choreographer = Choreographer.getInstance()
    private var startFrameTimeMillis: Long = 0
    private var numFramesRendered = 0

    var fps by mutableDoubleStateOf(0.0)
        private set

    /** Average time per rendered frame over the same window [fps] is measured over. */
    var frameTimeMillis by mutableDoubleStateOf(0.0)
        private set

    override fun onRemembered() {
        choreographer.postFrameCallback(this)
    }

    override fun onAbandoned() {
        choreographer.removeFrameCallback(this)
    }

    override fun onForgotten() {
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val currentFrameTimeMillis = frameTimeNanos.nanoseconds.inWholeMilliseconds
        if (startFrameTimeMillis > 0) {
            val duration = currentFrameTimeMillis - startFrameTimeMillis
            numFramesRendered++
            if (duration > interval) {
                fps = numFramesRendered * 1000.0 / duration
                frameTimeMillis = duration.toDouble() / numFramesRendered
                startFrameTimeMillis = currentFrameTimeMillis
                // The frame that closed this window has already been rendered, so it is also
                // the next window's first frame. Seeding the count with it stops the counter
                // from losing one frame every window - a steady 60 Hz read as a permanent 59.
                numFramesRendered = 1
            }
        } else {
            startFrameTimeMillis = currentFrameTimeMillis
        }
        choreographer.postFrameCallback(this)
    }

    companion object {
        const val DEFAULT_INTERVAL = 1000
    }
}
