// KMK --> The pure MTL spine: providers' SPI, selection, the page pipeline, stores and the
// controller facade. Compiled once and consumed by both flavors — :yakuyomi (mtl, real
// providers over the native engine) and :yakuyomi-stub (nomtl, empty component set) — so the
// two APKs share the exact same orchestration behaviour. Anything that needs ML Kit, llama.cpp
// or the li.joye engine does NOT live here; it lives in :yakuyomi's flavor/ subtree instead.
plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
}

android {
    namespace = "mtl.spine"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    api(projects.core.common)
    api(projects.core.concurrency)
    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
    api(libs.okhttp.core)
}
// KMK <--
