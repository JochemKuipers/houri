package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.copyToClipboard
import exh.util.isLewd
import mihon.app.di.globalAppGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.domain.manga.model.MangaCover as DomainMangaCover

@Composable
fun MangaInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    manga: Manga,
    sourceName: String,
    isStubSource: Boolean,
    // KMK -->
    isSourceIncognito: Boolean,
    // KMK <--
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // KMK -->
    librarySearch: (query: String) -> Unit,
    onSourceClick: () -> Unit,
    onCoverLoaded: (DomainMangaCover, Image) -> Unit,
    coverRatio: MutableFloatState,
    // KMK <--
    // KMK --> Explicit translated display title (spec 2026-09-23); null keeps original.
    displayTitle: String? = null,
) {
    // KMK -->
    val usePanoramaCover by globalAppGraph.uiPreferences.usePanoramaCoverMangaInfo().collectAsState()
    val topAlignCover by globalAppGraph.uiPreferences.topAlignCover().collectAsState()
    val coverLoadingSpinner by globalAppGraph.uiPreferences.mangaInfoCoverLoadingSpinner().collectAsState()
    val censorEnabled by globalAppGraph.uiPreferences.censorLewdManga().collectAsState()
    val shouldCensor = censorEnabled && manga.isLewd()
    val displayManga = if (shouldCensor) manga.copy(ogTitle = stringResource(KMR.strings.censored_title)) else manga
    val titleOverride = if (shouldCensor) null else displayTitle
    // KMK <--
    Box(modifier = modifier) {
        // Backdrop
        val backdropGradientColors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.background,
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(manga)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // KMK -->
            onSuccess = { result ->
                val image = result.result.image
                coverRatio.floatValue = image.height.toFloat() / image.width
            },
            // KMK <--
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = backdropGradientColors,
                            // KMK -->
                            startY = size.height / 2,
                            // KMK <--
                        ),
                    )
                }
                // KMK -->
                .background(MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.4f))
                .blur(if (shouldCensor) 16.dp else 7.dp)
                // KMK <--
                .alpha(0.2f),
        )

        // Manga & source info
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (!isTabletUi) {
                MangaAndSourceTitlesSmall(
                    appBarPadding = appBarPadding,
                    manga = displayManga,
                    sourceName = sourceName,
                    isStubSource = isStubSource,
                    // KMK -->
                    isSourceIncognito = isSourceIncognito,
                    // KMK <--
                    onCoverClick = onCoverClick,
                    doSearch = doSearch,
                    // KMK -->
                    librarySearch = librarySearch,
                    onSourceClick = onSourceClick,
                    onCoverLoaded = onCoverLoaded,
                    coverRatio = coverRatio,
                    usePanoramaCover = usePanoramaCover,
                    topAlignCover = topAlignCover,
                    showCoverSpinner = coverLoadingSpinner,
                    // KMK <--
                    displayTitle = titleOverride,
                )
            } else {
                MangaAndSourceTitlesLarge(
                    appBarPadding = appBarPadding,
                    manga = displayManga,
                    sourceName = sourceName,
                    isStubSource = isStubSource,
                    // KMK -->
                    isSourceIncognito = isSourceIncognito,
                    // KMK <--
                    onCoverClick = onCoverClick,
                    doSearch = doSearch,
                    // KMK -->
                    librarySearch = librarySearch,
                    onSourceClick = onSourceClick,
                    onCoverLoaded = onCoverLoaded,
                    coverRatio = coverRatio,
                    usePanoramaCover = usePanoramaCover,
                    showCoverSpinner = coverLoadingSpinner,
                    // KMK <--
                    displayTitle = titleOverride,
                )
            }
        }
    }
}

@Composable
private fun MangaAndSourceTitlesLarge(
    appBarPadding: Dp,
    manga: Manga,
    sourceName: String,
    isStubSource: Boolean,
    // KMK -->
    isSourceIncognito: Boolean,
    // KMK <--
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    // KMK -->
    librarySearch: (query: String) -> Unit,
    onSourceClick: () -> Unit,
    onCoverLoaded: (DomainMangaCover, Image) -> Unit,
    coverRatio: MutableFloatState,
    usePanoramaCover: Boolean = false,
    showCoverSpinner: Boolean = true,
    // KMK <--
    displayTitle: String? = null,
) {
    // KMK -->
    val censorEnabledLarge by globalAppGraph.uiPreferences.censorLewdManga().collectAsState()
    val shouldCensorLarge = censorEnabledLarge && manga.isLewd()
    // KMK <--
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // KMK -->
        if (usePanoramaCover && coverRatio.floatValue <= RatioSwitchToPanorama) {
            MangaCover.Panorama(
                modifier = Modifier.fillMaxWidth(0.65f)
                    .then(if (shouldCensorLarge) Modifier.blur(16.dp) else Modifier),
                data = ImageRequest.Builder(LocalContext.current)
                    .data(manga)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(MR.strings.manga_cover),
                onClick = onCoverClick,
                // KMK -->
                onCoverLoaded = { mangaCover, result ->
                    val image = result.result.image
                    coverRatio.floatValue = image.height.toFloat() / image.width
                    onCoverLoaded(mangaCover, image)
                },
                showLoadingSpinner = showCoverSpinner,
                // Show the cover whole: size the box to the cover's true ratio instead of
                // cropping its borders to the template's fixed one.
                useImageRatio = true,
                // KMK <--
            )
        } else {
            // KMK <--
            MangaCover.Book(
                modifier = Modifier.fillMaxWidth(0.65f)
                    .then(if (shouldCensorLarge) Modifier.blur(16.dp) else Modifier),
                data = ImageRequest.Builder(LocalContext.current)
                    .data(manga)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(MR.strings.manga_cover),
                onClick = onCoverClick,
                // KMK -->
                onCoverLoaded = { mangaCover, result ->
                    val image = result.result.image
                    coverRatio.floatValue = image.height.toFloat() / image.width
                    onCoverLoaded(mangaCover, image)
                },
                showLoadingSpinner = showCoverSpinner,
                // Show the cover whole: size the box to the cover's true ratio instead of
                // cropping its borders to the template's fixed one.
                useImageRatio = true,
                // KMK <--
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        MangaContentInfo(
            title = manga.title,
            author = manga.author,
            artist = manga.artist,
            status = manga.status,
            sourceName = sourceName,
            isStubSource = isStubSource,
            // KMK -->
            isSourceIncognito = isSourceIncognito,
            // KMK <--
            doSearch = doSearch,
            textAlign = TextAlign.Center,
            // KMK -->
            librarySearch = librarySearch,
            onSourceClick = onSourceClick,
            displayTitle = displayTitle,
            // KMK <--
        )
    }
}

@Composable
private fun MangaAndSourceTitlesSmall(
    appBarPadding: Dp,
    manga: Manga,
    sourceName: String,
    isStubSource: Boolean,
    // KMK -->
    isSourceIncognito: Boolean,
    // KMK <--
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    // KMK -->
    librarySearch: (query: String) -> Unit,
    onSourceClick: () -> Unit,
    onCoverLoaded: (DomainMangaCover, Image) -> Unit,
    coverRatio: MutableFloatState,
    usePanoramaCover: Boolean = false,
    topAlignCover: Boolean = false,
    showCoverSpinner: Boolean = true,
    // KMK <--
    displayTitle: String? = null,
) {
    // KMK -->
    val censorEnabledSmall by globalAppGraph.uiPreferences.censorLewdManga().collectAsState()
    val shouldCensorSmall = censorEnabledSmall && manga.isLewd()
    // KMK <--
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = if (topAlignCover) Alignment.Top else Alignment.CenterVertically,
    ) {
        // KMK -->
        if (usePanoramaCover && coverRatio.floatValue <= RatioSwitchToPanorama) {
            MangaCover.Panorama(
                modifier = Modifier
                    .sizeIn(maxHeight = 100.dp)
                    .then(if (shouldCensorSmall) Modifier.blur(16.dp) else Modifier)
                    .align(if (topAlignCover) Alignment.Top else Alignment.CenterVertically),
                // KMK <--
                data = ImageRequest.Builder(LocalContext.current)
                    .data(manga)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(MR.strings.manga_cover),
                onClick = onCoverClick,
                // KMK -->
                onCoverLoaded = { mangaCover, result ->
                    val image = result.result.image
                    coverRatio.floatValue = image.height.toFloat() / image.width
                    onCoverLoaded(mangaCover, image)
                },
                showLoadingSpinner = showCoverSpinner,
                // Show the cover whole: size the box to the cover's true ratio instead of
                // cropping its borders to the template's fixed one.
                useImageRatio = true,
                // KMK <--
            )
        } else {
            // KMK <--
            MangaCover.Book(
                modifier = Modifier
                    .sizeIn(maxWidth = 100.dp)
                    .then(if (shouldCensorSmall) Modifier.blur(16.dp) else Modifier)
                    .align(if (topAlignCover) Alignment.Top else Alignment.CenterVertically),
                // KMK <--
                data = ImageRequest.Builder(LocalContext.current)
                    .data(manga)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(MR.strings.manga_cover),
                onClick = onCoverClick,
                // KMK -->
                onCoverLoaded = { mangaCover, result ->
                    val image = result.result.image
                    coverRatio.floatValue = image.height.toFloat() / image.width
                    onCoverLoaded(mangaCover, image)
                },
                showLoadingSpinner = showCoverSpinner,
                // Show the cover whole: size the box to the cover's true ratio instead of
                // cropping its borders to the template's fixed one.
                useImageRatio = true,
                // KMK <--
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MangaContentInfo(
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                status = manga.status,
                sourceName = sourceName,
                isStubSource = isStubSource,
                // KMK -->
                isSourceIncognito = isSourceIncognito,
                // KMK <--
                doSearch = doSearch,
                // KMK -->
                librarySearch = librarySearch,
                onSourceClick = onSourceClick,
                displayTitle = displayTitle,
                // KMK <--
            )
        }
    }
}

@Suppress("UnusedReceiverParameter")
@Composable
private fun ColumnScope.MangaContentInfo(
    title: String,
    author: String?,
    artist: String?,
    status: Long,
    sourceName: String,
    isStubSource: Boolean,
    // KMK -->
    isSourceIncognito: Boolean,
    // KMK <--
    doSearch: (query: String, global: Boolean) -> Unit,
    textAlign: TextAlign? = LocalTextStyle.current.textAlign,
    // KMK -->
    librarySearch: (query: String) -> Unit,
    onSourceClick: () -> Unit,
    // KMK <--
    displayTitle: String? = null,
) {
    val context = LocalContext.current
    // KMK -->
    var showMenu by remember { mutableStateOf(false) }
    var tagSelected by remember { mutableStateOf("") }
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
    ) {
        DropdownMenuItem(
            text = { Text(text = stringResource(KMR.strings.action_library_search)) },
            onClick = {
                librarySearch(tagSelected)
                showMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(MR.strings.action_global_search)) },
            onClick = {
                doSearch(tagSelected, true)
                showMenu = false
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(MR.strings.action_copy_to_clipboard)) },
            onClick = {
                context.copyToClipboard(
                    tagSelected,
                    tagSelected,
                )
                showMenu = false
            },
        )
    }
    // KMK <--
    // KMK --> Translated display title overrides the domain title without mutating it.
    // Search actions stay on the original title: translated text is a display value only.
    val effectiveTitle = displayTitle?.takeIf { it.isNotBlank() } ?: title
    // KMK <--
    Text(
        text = effectiveTitle.ifBlank { stringResource(MR.strings.unknown_title) },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clickableNoIndication(
            onLongClick = {
                if (title.isNotBlank()) {
                    // KMK -->
                    tagSelected = title
                    showMenu = true
                    // KMK <--
                }
            },
            onClick = { if (title.isNotBlank()) doSearch(title, true) },
        ),
        textAlign = textAlign,
    )

    Spacer(modifier = Modifier.height(2.dp))

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PersonOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = author?.takeIf { it.isNotBlank() }
                ?: stringResource(MR.strings.unknown_author),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .clickableNoIndication(
                    onLongClick = {
                        if (!author.isNullOrBlank()) {
                            // KMK -->
                            tagSelected = author
                            showMenu = true
                            // KMK <--
                        }
                    },
                    onClick = { if (!author.isNullOrBlank()) doSearch(author, true) },
                ),
            textAlign = textAlign,
        )
    }

    if (!artist.isNullOrBlank() && author != artist) {
        Row(
            modifier = Modifier.secondaryItemAlpha(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Brush,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clickableNoIndication(
                        onLongClick = {
                            // KMK -->
                            tagSelected = artist
                            showMenu = true
                            // KMK <--
                        },
                        onClick = { doSearch(artist, true) },
                    ),
                textAlign = textAlign,
            )
        }
    }

    Spacer(modifier = Modifier.height(2.dp))

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (status) {
                SManga.ONGOING.toLong() -> Icons.Outlined.Schedule
                SManga.COMPLETED.toLong() -> Icons.Outlined.DoneAll
                SManga.LICENSED.toLong() -> Icons.Outlined.AttachMoney
                SManga.PUBLISHING_FINISHED.toLong() -> Icons.Outlined.Done
                SManga.CANCELLED.toLong() -> Icons.Outlined.Close
                SManga.ON_HIATUS.toLong() -> Icons.Outlined.Pause
                else -> Icons.Outlined.Block
            },
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(16.dp),
        )
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Text(
                text = when (status) {
                    SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
                    SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
                    SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
                    SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
                    SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
                    SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
                    else -> stringResource(MR.strings.unknown)
                },
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            // KMK -->
            if (isSourceIncognito) {
                DotSeparatorText()
                Icon(
                    painter = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_glasses_with_hat_24dp)),
                    contentDescription = stringResource(MR.strings.pref_incognito_mode),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            // KMK <--
            DotSeparatorText()
            if (isStubSource) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = sourceName,
                modifier = Modifier.clickableNoIndication(
                    // KMK -->
                    onLongClick = {
                        tagSelected = sourceName
                        showMenu = true
                    },
                    onClick = {
                        onSourceClick()
                    },
                    // KMK <--
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}
