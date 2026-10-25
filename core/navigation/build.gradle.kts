plugins {
    id("mihon.library")
    id("mihon.library.compose")
    alias(kotlinx.plugins.serialization)
}

android {
    namespace = "mihon.core.navigation"
}

dependencies {
    implementation(projects.domain)
    implementation(projects.presentationCore)

    implementation(androidx.bundles.navigation3)
    // Nav3 only exposes savedstate-compose on its runtime classpath, but the saveable
    // serializers used by TopLevelBackStack are a compile-time API here.
    implementation(androidx.savedstate.compose)

    api(libs.compose.materialmotion)
    implementation(compose.foundation)
}
