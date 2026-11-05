package mtl.provider

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import exh.yakuyomi.LocalLlmManager
import exh.yakuyomi.TranslationPreferences
import exh.yakuyomi.YakuyomiEngine

/** Provides the mtl flavor's real component set for the app graph. */
@BindingContainer
object MtlComponentsContainer {
    @Provides
    @SingleIn(AppScope::class)
    fun components(
        context: Context,
        prefs: TranslationPreferences,
        engine: YakuyomiEngine,
        localLlm: LocalLlmManager,
    ): MtlComponents = RealMtlComponents(context, prefs, engine, localLlm)
}
