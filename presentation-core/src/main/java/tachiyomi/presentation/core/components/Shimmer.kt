package tachiyomi.presentation.core.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.shimmer(
    baseColor: Color = Color.Unspecified,
    highlightColor: Color = Color.Unspecified,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    val resolvedBase = if (baseColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else {
        baseColor
    }
    val resolvedHighlight = if (highlightColor == Color.Unspecified) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    } else {
        highlightColor
    }
    val brush = Brush.linearGradient(
        colors = listOf(resolvedBase, resolvedHighlight, resolvedBase),
        start = Offset(translate - 500f, 0f),
        end = Offset(translate, 0f),
    )
    background(brush)
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    width: Dp? = null,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(shape)
            .shimmer(),
    )
}

/**
 * The insets the detail skeletons need to sit where the loaded details screen will put its own
 * first row.
 *
 * The real screen's Scaffold opens the info block below the collapsed app bar: status bar plus
 * the M3 `TopAppBar` height, handed to `MangaAndSourceTitles*` as `appBarPadding + 16.dp`. A
 * skeleton that skipped that started its first row at the display's absolute top edge — roughly
 * 80dp higher than where the real content lands — and the content visibly jumped downward when
 * the details arrived. The bottom had the mirror-image problem: the last chapter row rendered
 * straight into the gesture-navigation area, where a clipped row with the running shimmer over
 * it read as a stray gray bar pinned to the bottom edge, with nothing drawn below it.
 */
// The M3 `TopAppBar` container height. The detail screen's Scaffold hands its content
// `status bar inset + this height` as top padding, and the skeleton insets mirror that
// arithmetic. Spelled as a literal because the Material constant keeps renaming between
// versions (`SmallTopAppBarHeight` before 1.3, `TopAppBarHeight` after), while the height
// itself has been the 64dp M3 spec since the beginning.
private val DetailSkeletonAppBarHeight = 64.dp

private fun Modifier.detailSkeletonInsets(): Modifier = composed {
    padding(
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            DetailSkeletonAppBarHeight,
        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )
}

@Composable
fun MangaListShimmer(modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(8.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp, 80.dp)
                .clip(RoundedCornerShape(6.dp))
                .shimmer(),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            ShimmerBox(height = 14.dp, width = 140.dp)
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(height = 10.dp, width = 100.dp)
            Spacer(modifier = Modifier.height(6.dp))
            ShimmerBox(height = 10.dp, width = 80.dp)
        }
    }
}

@Composable
fun ChapterListShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        repeat(5) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(height = 12.dp, width = 180.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    ShimmerBox(height = 10.dp, width = 100.dp)
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .shimmer(),
                )
            }
        }
    }
}

@Composable
fun FeedShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        repeat(6) {
            MangaListShimmer()
        }
    }
}

@Composable
fun MangaDetailTabletShimmer(modifier: Modifier = Modifier) {
    TwoPanelBox(
        modifier = modifier,
        startContent = {
            Column(
                modifier = Modifier.detailSkeletonInsets().padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(120.dp, 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .shimmer(),
                    )
                    Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        ShimmerBox(height = 18.dp, width = 160.dp)
                        ShimmerBox(height = 12.dp, width = 100.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                        ShimmerBox(height = 10.dp)
                        ShimmerBox(height = 10.dp, width = 140.dp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ShimmerBox(height = 36.dp)
                ShimmerBox(height = 10.dp)
                ShimmerBox(height = 10.dp)
                ShimmerBox(height = 10.dp, width = 200.dp)
            }
        },
        endContent = {
            Column(
                modifier = Modifier.detailSkeletonInsets().padding(vertical = 16.dp),
            ) {
                ShimmerBox(height = 24.dp, width = 180.dp, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(8.dp))
                ChapterListShimmer()
            }
        },
    )
}

@Composable
fun MangaDetailShimmer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .detailSkeletonInsets()
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp, 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .shimmer(),
            )
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                ShimmerBox(height = 18.dp, width = 160.dp)
                ShimmerBox(height = 12.dp, width = 100.dp)
                Spacer(modifier = Modifier.height(8.dp))
                ShimmerBox(height = 10.dp)
                ShimmerBox(height = 10.dp, width = 140.dp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        ShimmerBox(height = 36.dp)
        ChapterListShimmer()
    }
}
