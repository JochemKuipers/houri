package upscale.app

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Metro bindings for the upscaling spine — the exact mirror of `MtlBindings`: the spine classes
 * carry no DI annotations, so this container adapts them, one bootstrap, everything else derived.
 */
@BindingContainer
object UpscaleBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpscalePreferences(preferenceStore: tachiyomi.core.common.preference.PreferenceStore): UpscalePreferences =
        UpscalePreferences(
            preferenceStore,
            UpscaleMangaStore(preferenceStore),
        )

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpscaleMangaStore(preferences: UpscalePreferences): UpscaleMangaStore = preferences.mangaStore

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpscaleBootstrap(
        context: android.content.Context,
        client: okhttp3.OkHttpClient,
        preferences: UpscalePreferences,
        configSource: PrefsUpscaleConfigSource,
        achievementObserver: UpscaleAchievementObserver,
    ): UpscaleBootstrap = UpscaleBootstrap(
        context = context,
        client = client,
        preferences = preferences,
        configSource = configSource,
        achievementObserver = achievementObserver,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpscaleController(bootstrap: UpscaleBootstrap): upscale.UpscaleController = bootstrap.controller

    @Provides
    @SingleIn(AppScope::class)
    fun provideUpscaleStatus(bootstrap: UpscaleBootstrap): upscale.status.UpscaleStatusHub = bootstrap.status
}
