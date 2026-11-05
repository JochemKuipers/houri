// KMK --> The MTL seam for the no-MTL APK: no native providers, no vision runtime — the app
// compiles and runs, gates still work, and translation cleanly reports unavailable. The real
// spine (selection, pipeline, stores, status, controller facade) is the :mtl-spine module,
// shared verbatim with the mtl flavor; the app-facing wiring is identical across flavors.
plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "exh.yakuyomi.stub"
}

dependencies {
    api(projects.core.common)
    api(projects.mtlSpine)
    implementation(libs.metro.runtime)
    implementation(projects.core.metro)
    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
    api(libs.preferencektx)
    api(libs.okhttp.core)
}
// KMK <--
