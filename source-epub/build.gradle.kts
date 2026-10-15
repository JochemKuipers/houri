import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.configureTest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("mihon.code.lint")
    kotlin("multiplatform")
    // Metro compiler plugin. Required, not optional: `EpubSourceFileSystem` is only a
    // `@Inject @SingleIn(AppScope::class)` class, and without the plugin its binding is never
    // generated, so `:app`'s AppGraph fails with [Metro/MissingBinding]. `libs.metro.runtime` alone
    // gives the annotations but not the bindings.
    alias(libs.plugins.metro)
}

kotlin {
    android {
        compileSdk { version = release(AndroidConfig.COMPILE_SDK) }
        namespace = "tachiyomi.source.epub"
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.sourceApi)
                // KMK --> this source is a Komikku-only feature, so its strings are KMR, not MR
                api(projects.i18nKmk)
                // KMK <--
                implementation(libs.unifile)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(projects.core.archive)
                implementation(projects.core.common)
                implementation(projects.coreMetadata)

                // ChapterRecognition + Manga, for the same chapter-numbering the manga local
                // source uses so a book sorted here and there agrees with itself.
                implementation(projects.domain)

                implementation(kotlinx.bundles.serialization)
                implementation(libs.metro.runtime)
                implementation(libs.injekt)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(AndroidConfig.JvmTarget)
    }
}

configureTest()
