// KMK -->
package eu.kanade.presentation.reader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.ui.reader.viewer.novel.NovelStatusLine
import eu.kanade.tachiyomi.ui.reader.viewer.novel.toColorIntOrNull
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The reader's status line, Moon+ Reader style (`PrefStatusSetting`): one quiet line of facts at
 * the screen's edge — clock, chapter progress, the chapter's name, battery — over the book.
 *
 * The composable resolves what only the device knows (the clock, the battery) and delegates the
 * *format* to [NovelStatusLine.format], which is pure; the reader's own harness asserts that.
 * Fields the device has not answered yet (battery before the first sticky broadcast) simply do not
 * render rather than showing a placeholder — a dash for missing data is chrome pretending to be
 * information.
 *
 * Visibility is the reader's menu state plus the reader's own "always show" choice: when the
 * chrome hides, the line hides with it unless the reader asked to keep it — Moon+ shows its status
 * outside the menu chrome, which is the whole point of a status line.
 */
@Composable
fun NovelStatusLineOverlay(
    fields: Set<NovelStatusLine.Field>,
    position: NovelStatusLine.Position,
    textSizeSp: Int,
    colorHex: String?,
    backgroundHex: String?,
    menuVisible: Boolean,
    showWhenHidden: Boolean,
    progressPct: Int?,
    chapterName: String?,
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) return
    if (!menuVisible && !showWhenHidden) return

    val context = LocalContext.current
    var timeText by remember { mutableStateOf<String?>(null) }
    var batteryPct by remember { mutableStateOf<Int?>(null) }

    // The clock and the battery tick together, at a pace that outruns neither: a minute is the
    // clock's resolution and slower than any battery moves, so thirty seconds re-reads both
    // without being a timer that fires between the two things it exists to update.
    LaunchedEffect(fields) {
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
        while (true) {
            if (NovelStatusLine.Field.TIME in fields) timeText = timeFormat.format(Date())
            if (NovelStatusLine.Field.BATTERY in fields) {
                batteryPct = readBatteryPct(context)
            }
            delay(30_000)
        }
    }

    val text = NovelStatusLine.format(
        fields = fields,
        time = timeText,
        progressPct = progressPct,
        chapter = chapterName,
        batteryPct = batteryPct,
    )
    if (text.isEmpty()) return

    val textColor = colorHex?.toColorIntOrNull()?.let(::Color)
        ?: backgroundHex?.let { background -> contrastingForeground(background.toColorIntOrNull()) }
        ?: Color.Unspecified

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                when (position) {
                    NovelStatusLine.Position.TOP -> Modifier.statusBarsPadding()
                    NovelStatusLine.Position.BOTTOM -> Modifier.navigationBarsPadding()
                },
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = when (position) {
            NovelStatusLine.Position.TOP -> Alignment.TopCenter
            NovelStatusLine.Position.BOTTOM -> Alignment.BottomCenter
        },
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = textSizeSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier
                // KMK --> Solid, not translucent: at 35% alpha the book's own text bled through
                // the pill and fought the status text for legibility, most visibly over a
                // two-column layout or an illustration. The colour is still the reader's canvas
                // (or black when there is none), just all of it. KMK <--
                .background(
                    color = backgroundHex?.toColorIntOrNull()?.let(::Color) ?: Color.Black,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** Reads the battery percentage from the sticky broadcast, or null when the device has none. */
private fun readBatteryPct(context: Context): Int? {
    val intent = ContextCompat.registerReceiver(
        context,
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    ) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    return (level * 100 / scale).coerceIn(0, 100)
}

/**
 * Foreground that reads on [background], when the reader has not chosen a colour of their own:
 * the palette-derived rule the typography stylesheet uses, so the line and the text share one
 * answer instead of inventing two.
 */
private fun contrastingForeground(background: Int?): Color {
    val argb = background ?: return Color.Unspecified
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
    return if (luminance > 140) Color(0xFF1B1B1B) else Color(0xFFEAEAEA)
}
// KMK <--
