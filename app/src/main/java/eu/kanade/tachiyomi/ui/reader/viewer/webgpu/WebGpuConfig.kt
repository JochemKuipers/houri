package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.reader.OnSettingsChanged
import ca.mpreg.webgpuviewer.reader.SettingsDiff
import ca.mpreg.webgpuviewer.reader.SettingsProfile
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Configuration used by WebGPU pager viewers.
 */
class WebGpuConfig(
    private val viewer: WebGpuViewer,
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences,
) : ViewerConfig(readerPreferences, scope) {

    var theme = readerPreferences.readerTheme().get()
        private set

    var automaticBackground = false
        private set

    var imageScaleType = 1
        private set

    var imageZoomType = ReaderPageImageView.ZoomStartPosition.LEFT
        private set

    /**
     * The Pager group's crop switch.
     *
     * Kept under its own name because it is no longer the answer for every mode - see
     * [imageCropBorders].
     */
    internal var pagerCropBorders = false
        private set

    var navigateToPan = false
        private set

    var landscapeZoom = false
        private set

    var transitionAnimation = ReaderPreferences.TransitionAnimation.BASIC
        private set

    var transitionAnimationDual = ReaderPreferences.TransitionAnimation.BASIC
        private set

    var cutoutMode = ReaderPreferences.CutoutMode.AVOID
        private set

    var cutoutModeDual = ReaderPreferences.CutoutMode.AVOID
        private set

    // KMK -->
    /**
     * Single/double/automatic page layout, same switch as the legacy pager
     * ([PagerConfig.PageLayout]). Previously ignored here, so the layout chips
     * and the bottom-bar toggle did nothing in the WebGPU reader.
     */
    var doublePages = readerPreferences.pageLayout().get() == PagerConfig.PageLayout.DOUBLE_PAGES &&
        !readerPreferences.dualPageSplitPaged().get()
        private set

    var autoDoublePages = readerPreferences.pageLayout().get() == PagerConfig.PageLayout.AUTOMATIC
        private set

    /**
     * Pairing direction for spreads, same switch as the legacy pager
     * ([PagerConfig.invertDoublePages]). Unlike the pager it is not gated on
     * the split toggle: both split and double layout modes pair pages here.
     */
    var invertDoublePages = false
        private set
    // KMK <--

    // KMK -->
    /**
     * Transient spread-shift toggle, ported from the legacy pager's shift button
     * ([eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig.shiftDoublePage]):
     * when true, dual-page pairing starts at page 0 instead of leaving the
     * cover solo, fixing mis-paired spreads. Toggled from the reader bottom
     * bar; not persisted, reset on viewer rebuild.
     */
    var shiftDoublePage = false

    var continuousMinWidth = 100
        private set

    var zoomOutDisabled = false
        private set

    // KMK --> paged "disable zoom in" was wired only into the legacy pager;
    // the WebGPU reader ignored it. Registered here so the max-scale clamp
    // applies to WebGPU paged pages too.
    var disableZoomIn = false
        private set
    // KMK <--

    // KMK -->
    var continuousGap = 10
        private set
    // KMK <--

    // KMK -->
    var pageOffset = readerPreferences.webgpuPageOffset().get()
        private set
    // KMK <--

    // KMK -->
    var matchDoublePageHeights = readerPreferences.dualPageMatchHeights().get()
        private set
    // KMK <--

    // KMK -->
    var webgpuDarkMode = readerPreferences.webgpuDarkMode().get()
        private set

    var webgpuDarkModeAmoled = readerPreferences.webgpuDarkModeAmoled().get()
        private set

    var darkModeTolerance = readerPreferences.webgpuDarkModeTolerance().get() / 100f
        private set

    var darkModeChunkRange = readerPreferences.webgpuDarkModeChunkRange().get() / 100f
        private set
    // KMK <--

    // KMK -->
    var brightness = readerPreferences.webgpuBrightness().get() / 100f
        private set

    var contrast = readerPreferences.webgpuContrast().get() / 100f
        private set

    var hlgEnabled = readerPreferences.webgpuHlg().get()
        private set

    var hlgExposure = readerPreferences.webgpuHlgExposure().get() / 100f
        private set

    var lutPreset = readerPreferences.webgpuLutPreset().get()
        private set

    var lutCustomPath = readerPreferences.webgpuLutCustomPath().get()
        private set

    var lutIntensity = readerPreferences.webgpuLutIntensity().get() / 100f
        private set

    var compareTranslation = readerPreferences.webgpuCompareTranslation().get()
        private set

    var perfHud = readerPreferences.webgpuPerfHud().get()
        private set

    var einkPreset = readerPreferences.webgpuEinkPreset().get()
        private set
    // KMK <--

    // KMK -->
    var artCnnUpscaler = readerPreferences.webgpuArtCnnUpscaler().get()
        private set

    var fastRender = readerPreferences.webgpuFastRender().get()
        private set

    var preloadAhead = readerPreferences.webgpuPreloadAhead().get()
        private set

    var preloadBehind = readerPreferences.webgpuPreloadBehind().get()
        private set
    // KMK <--

    // KMK -->
    /**
     * The tap-zone preference for whichever group the reading mode belongs to.
     *
     * Held as two fields rather than read at access time because `register` needs something to
     * write into; [applyNavigationProfile] folds them into the base's `navigationMode`.
     */
    private var pagerNavigationMode = 0

    private var webtoonNavigationMode = 0

    private var pagerNavInvertedMode = ReaderPreferences.TappingInvertMode.NONE

    private var webtoonNavInvertedMode = ReaderPreferences.TappingInvertMode.NONE

    /**
     * Re-resolves the tap zones now that the reading mode is known.
     *
     * The `init`-time registrations resolve while `viewer.isContinuous` is still the base class's
     * `false` - the continuous subclass assigns its override after super construction - so without
     * this a continuous viewer would keep the Pager pair until the user touched a setting. The
     * subclass calls it next to [reseedDiffBaseline], for the same reason.
     */
    fun reseedNavigationProfile() {
        applyNavigationProfile()
    }

    /**
     * Rebuilds the tap zones from the pair of preferences the reading mode selects.
     *
     * All four registrations call this, so whichever preference changed - and whichever mode is
     * open - the result is the same. The order inside is load-bearing: `updateNavigation` replaces
     * the navigator, and the replacement re-applies `invertMode` from `tappingInverted`, so an
     * inversion that arrived first has to be re-asserted after the new navigator is in place.
     */
    private fun applyNavigationProfile() {
        val continuous = viewer.isContinuous
        tappingInverted = if (continuous) webtoonNavInvertedMode else pagerNavInvertedMode
        navigationMode = if (continuous) webtoonNavigationMode else pagerNavigationMode
        updateNavigation(navigationMode)
        navigator.invertMode = tappingInverted
    }

    /**
     * Whether decoded pages are trimmed to their content, resolved by reading mode.
     *
     * Three switches, three modes, and the WebGPU reader used to read the Pager one for all of
     * them - so *Crop borders* in the Webtoon group changed nothing while the strip was open. The
     * legacy viewers make the same split (`WebtoonConfig` registers `cropBordersWebtoon`,
     * `WebtoonPageHolder` picks `cropBordersContinuousVertical` for the paged mode), and the
     * reading-mode-to-switch mapping is the one the reader modes are built from:
     *
     *   - L2R / R2L / vertical (paged) -> the Pager group's switch, which is where those modes
     *     configure it;
     *   - WEBTOON (continuous, no gap) -> the Webtoon group's switch;
     *   - CONTINUOUS_VERTICAL (continuous, gapped) -> its own switch, exactly as the legacy
     *     `WebtoonViewer(isContinuous = false)` reads it.
     *
     * Resolved at access time for the same reason [doubleTapZoom] is: `isContinuous` is only
     * assigned after the base constructor has run.
     */
    val imageCropBorders: Boolean
        get() {
            if (!viewer.isContinuous) return pagerCropBorders
            return if ((viewer as? WebGpuViewerContinuous)?.useGap == true) {
                readerPreferences.cropBordersContinuousVertical().get()
            } else {
                readerPreferences.cropBordersWebtoon().get()
            }
        }

    private val pagedDoubleTapZoomPref = readerPreferences.pagedDoubleTapZoomEnabled()
    private val webtoonDoubleTapZoomPref = readerPreferences.webtoonDoubleTapZoomEnabled()

    /**
     * Resolved at access time instead of init: [WebGpuViewer.isContinuous] is
     * overridden by subclasses whose properties are not assigned while this config's
     * init runs during super construction.
     */
    val doubleTapZoom: Boolean
        get() = if (viewer.isContinuous) {
            webtoonDoubleTapZoomPref.get()
        } else {
            pagedDoubleTapZoomPref.get()
        }

    // KMK -->
    /**
     * Single consumption point for the double-tap-zoom preference. Paged honors it
     * via applyDoubleTapZoomPolicy (WebGpuZoom.kt); both modes read the library
     * ImageViewerState.doubleTapZoomEnabled flag, which WebGpuViewer /
     * WebGpuViewerContinuous assign from this resolver.
     */
    fun resolveDoubleTapZoom(): Boolean = doubleTapZoom

    /**
     * Single settings channel: every preference registration snapshots a
     * [SettingsProfile], diffs it against the previous one, and delivers the
     * [SettingsDiff] here so the viewer applies exactly one impact tier.
     */
    var onSettingsChanged: OnSettingsChanged? = null

    private var lastProfile: SettingsProfile? = null

    fun snapshot(): SettingsProfile = SettingsProfile(
        doubleTapZoom = doubleTapZoom,
        disableZoomIn = disableZoomIn,
        zoomOutDisabled = zoomOutDisabled,
        landscapeZoom = landscapeZoom,
        imageScaleType = imageScaleType,
        imageZoomType = imageZoomType.ordinal,
        imageCropBorders = imageCropBorders,
        dualPageSplit = dualPageSplit,
        doublePages = doublePages,
        autoDoublePages = autoDoublePages,
        invertDoublePages = invertDoublePages,
        matchDoublePageHeights = matchDoublePageHeights,
        shiftDoublePage = shiftDoublePage,
        theme = theme,
        transitionAnimation = transitionAnimation.ordinal,
        transitionAnimationDual = transitionAnimationDual.ordinal,
        cutoutMode = cutoutMode.ordinal,
        cutoutModeDual = cutoutModeDual.ordinal,
        continuousMinWidth = continuousMinWidth,
        continuousGap = continuousGap,
        pageOffset = pageOffset,
        preloadAhead = preloadAhead,
        preloadBehind = preloadBehind,
        artCnnUpscaler = artCnnUpscaler,
        fastRender = fastRender,
        brightness = brightness,
        contrast = contrast,
        hlgEnabled = hlgEnabled,
        hlgExposure = hlgExposure,
        lutPreset = lutPreset,
        lutCustomPath = lutCustomPath,
        lutIntensity = lutIntensity,
        webgpuDarkMode = webgpuDarkMode,
        webgpuDarkModeAmoled = webgpuDarkModeAmoled,
        darkModeTolerance = darkModeTolerance,
        darkModeChunkRange = darkModeChunkRange,
        compareTranslation = compareTranslation,
        perfHud = perfHud,
        einkPreset = einkPreset,
    )

    private fun emitSettingsChange() {
        val current = snapshot()
        val previous = lastProfile
        lastProfile = current
        if (previous == null) return
        val diff = current.diff(previous)
        if (!diff.isEmpty) onSettingsChanged?.invoke(diff)
    }

    /**
     * Continuous mode resolves isContinuous only after super construction, so the
     * baseline captured during base init may reflect the paged double-tap pref;
     * WebGpuViewerContinuous reseeds once its flag is assigned.
     */
    fun reseedDiffBaseline() {
        lastProfile = snapshot()
    }

    init {
        emitSettingsChange()
        merge(
            pagedDoubleTapZoomPref.changes(),
            webtoonDoubleTapZoomPref.changes(),
        )
            .onEach { emitSettingsChange() }
            .launchIn(scope)
    }
    // KMK <--

    init {
        // Harden: wrap each registration in try-catch to survive corrupted prefs or destroyed viewer.
        try {
            readerPreferences.readerTheme()
                .register(
                    {
                        theme = it
                        automaticBackground = it == 3
                    },
                    {
                        try {
                            emitSettingsChange()
                        } catch (_: Exception) {}
                    },
                )
        } catch (_: Exception) {}

        readerPreferences.imageScaleType()
            .register({ imageScaleType = it }, { emitSettingsChange() })

        readerPreferences.zoomStart()
            .register({ zoomTypeFromPreference(it) }, { emitSettingsChange() })

        readerPreferences.cropBorders()
            .register({ pagerCropBorders = it }, { emitSettingsChange() })

        // KMK --> The continuous modes read their own switches (see [imageCropBorders]); watched so
        // a flip re-applies while the strip is open instead of on the next viewer rebuild.
        readerPreferences.cropBordersWebtoon()
            .changes()
            .drop(1)
            .onEach { emitSettingsChange() }
            .launchIn(scope)
        readerPreferences.cropBordersContinuousVertical()
            .changes()
            .drop(1)
            .onEach { emitSettingsChange() }
            .launchIn(scope)
        // KMK <--

        readerPreferences.navigateToPan()
            .register({ navigateToPan = it })

        readerPreferences.landscapeZoom()
            .register({ landscapeZoom = it }, { emitSettingsChange() })

        // KMK --> Tap zones are a per-mode setting, and the WebGPU reader used to read the Pager
        // group's for every mode - so "Viewer navigation" in the Webtoon group changed nothing while
        // a strip was open, and half the readers that navigate by tapping were on the wrong zones.
        // Both groups are registered and [applyNavigationProfile] picks the pair for the mode, which
        // is exactly the split the legacy `WebtoonViewer` makes.
        readerPreferences.navigationModePager()
            .register({ pagerNavigationMode = it }, { applyNavigationProfile() })

        readerPreferences.navigationModeWebtoon()
            .register({ webtoonNavigationMode = it }, { applyNavigationProfile() })

        readerPreferences.pagerNavInverted()
            .register({ pagerNavInvertedMode = it }, { applyNavigationProfile() })

        readerPreferences.webtoonNavInverted()
            .register({ webtoonNavInvertedMode = it }, { applyNavigationProfile() })
        // KMK <--

        readerPreferences.dualPageSplitPaged()
            .register(
                {
                    dualPageSplit = it
                    doublePages =
                        readerPreferences.pageLayout().get() == PagerConfig.PageLayout.DOUBLE_PAGES && !it
                },
                { emitSettingsChange() },
            )

// KMK -->
        // dualPageRotateToFit/Invert are declared on ViewerConfig with a protected setter, so
        // assigning them here is the whole of the wiring - but nothing in this viewer assigned
        // them, so they stayed false and the option did nothing here. The paged reader settings
        // group covers this viewer as well as the classic pager, which made the gap invisible:
        // the toggle was right there in the settings and dead. The pager rotates the encoded
        // bytes; WebGPU has no encode step, so it rotates the decoded pixels instead.
        readerPreferences.dualPageRotateToFit()
            .register(
                { dualPageRotateToFit = it },
                { emitSettingsChange() },
            )

        readerPreferences.dualPageRotateToFitInvert()
            .register(
                { dualPageRotateToFitInvert = it },
                { emitSettingsChange() },
            )
        // KMK <--

        readerPreferences.transitionAnimation()
            .register(
                { transitionAnimation = it },
                { emitSettingsChange() },
            )

        readerPreferences.transitionAnimationDual()
            .register(
                { transitionAnimationDual = it },
                { emitSettingsChange() },
            )

        readerPreferences.cutoutMode()
            .register(
                { cutoutMode = it },
                { emitSettingsChange() },
            )

        readerPreferences.cutoutModeDual()
            .register(
                { cutoutModeDual = it },
                { emitSettingsChange() },
            )

        // KMK -->
        readerPreferences.pageLayout()
            .register(
                {
                    autoDoublePages = it == PagerConfig.PageLayout.AUTOMATIC
                    doublePages = it == PagerConfig.PageLayout.DOUBLE_PAGES && !dualPageSplit
                },
                {
                    autoDoublePages = it == PagerConfig.PageLayout.AUTOMATIC
                    doublePages = it == PagerConfig.PageLayout.DOUBLE_PAGES && !dualPageSplit
                    emitSettingsChange()
                },
            )

        readerPreferences.invertDoublePages()
            .register({ invertDoublePages = it }, { emitSettingsChange() })
        // KMK <--

        readerPreferences.continuousMinWidth()
            .register(
                { continuousMinWidth = it },
                { emitSettingsChange() },
            )

        readerPreferences.webtoonDisableZoomOut()
            .register(
                { zoomOutDisabled = it },
                { emitSettingsChange() },
            )

        // KMK -->
        readerPreferences.pagedDisableZoomIn()
            .register(
                { disableZoomIn = it },
                { emitSettingsChange() },
            )
        // KMK <--

        // KMK -->
        readerPreferences.continuousGap()
            .register(
                { continuousGap = it },
                { emitSettingsChange() },
            )
        // KMK <--

        readerPreferences.webgpuPageOffset()
            .register(
                { pageOffset = it },
                { emitSettingsChange() },
            )

        // KMK -->
        readerPreferences.dualPageMatchHeights()
            .register(
                { matchDoublePageHeights = it },
                { emitSettingsChange() },
            )
        // KMK <--

        // KMK -->
        readerPreferences.webgpuDarkMode()
            .register(
                { webgpuDarkMode = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuDarkModeAmoled()
            .register(
                { webgpuDarkModeAmoled = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuDarkModeTolerance()
            .register(
                { darkModeTolerance = it / 100f },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuDarkModeChunkRange()
            .register(
                { darkModeChunkRange = it / 100f },
                { emitSettingsChange() },
            )
        // KMK <--

        // KMK -->
        // The user's viewer-level toggle AND the spine's display-quality decision (global
        // enablement, mode != Simple, tile refinement pref) must both hold; the spine decides,
        // this pref scopes it to the WebGPU viewer.
        readerPreferences.webgpuArtCnnUpscaler()
            .register(
                { artCnnUpscaler = it && upscaleGpuSamplerEnabled() },
                { emitSettingsChange() },
            )

        // The spine decision can flip independently of this pref (global enable, mode, tile
        // refinement); re-resolve on any settings change so the state sync sees it.
        registerUpscaleDecisionListener()

        readerPreferences.webgpuFastRender()
            .register(
                { fastRender = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuPreloadAhead()
            .register(
                { preloadAhead = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuPreloadBehind()
            .register(
                { preloadBehind = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuBrightness()
            .register(
                { brightness = it / 100f },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuContrast()
            .register(
                { contrast = it / 100f },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuHlg()
            .register(
                { hlgEnabled = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuHlgExposure()
            .register(
                { hlgExposure = it / 100f },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuLutPreset()
            .register(
                { lutPreset = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuLutCustomPath()
            .register(
                { lutCustomPath = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuLutIntensity()
            .register(
                { lutIntensity = it / 100f },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuCompareTranslation()
            .register(
                { compareTranslation = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuPerfHud()
            .register(
                { perfHud = it },
                { emitSettingsChange() },
            )

        readerPreferences.webgpuEinkPreset()
            .register(
                { einkPreset = it },
                { emitSettingsChange() },
            )
        // KMK <--
    }

    private fun zoomTypeFromPreference(value: Int) {
        // The mapping itself is pure and lives with the zoom code it feeds, so it is testable
        // without a viewer; this only reads the reading direction and stores the answer.
        imageZoomType = resolveZoomStartPosition(value, viewer.isReversed)
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = this.tappingInverted }
        }

    override fun defaultNavigation(): ViewerNavigation {
        return if (viewer.isVertical) {
            LNavigation()
        } else {
            RightAndLeftNavigation()
        }
    }

    override fun updateNavigation(navigationMode: Int) {
        navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            5 -> DisabledNavigation()
            else -> defaultNavigation()
        }
        navigationModeChangedListener?.invoke()
    }

    // KMK -->
    /** The spine's display-quality decision for GPU tile refinement. */
    private fun upscaleGpuSamplerEnabled(): Boolean = try {
        mihon.app.di.globalAppGraph.upscaleController.useGpuTileSampler()
    } catch (_: Exception) {
        false
    }

    /**
     * Re-resolves the ArtCNN decision whenever the spine's inputs change (global enable, mode,
     * tile refinement), so the image-state sync notices even when this viewer's own pref did not.
     */
    private fun registerUpscaleDecisionListener() {
        try {
            val prefs = mihon.app.di.globalAppGraph.upscalePreferences
            prefs.enabled().changes().onEach { emitSettingsChange() }.launchIn(scope)
            prefs.mode().changes().onEach { emitSettingsChange() }.launchIn(scope)
            prefs.tileRefinement().changes().onEach { emitSettingsChange() }.launchIn(scope)
        } catch (_: Exception) {
        }
    }
    // KMK <--
}
