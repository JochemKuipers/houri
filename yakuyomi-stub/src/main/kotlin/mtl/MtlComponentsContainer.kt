package mtl.provider

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Provides the no-MTL flavor's empty component set for the app graph. */
@BindingContainer
object MtlComponentsContainer {
    @Provides
    @SingleIn(AppScope::class)
    fun components(): MtlComponents = NoMtlComponents()
}
