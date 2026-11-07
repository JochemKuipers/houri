// KMK --> The upscaling stack: the spine (policy/identity/schedule/status/models) plus the
// native backends (NCNN JNI, ONNX Runtime, simple resample). Compiled once and consumed by
// both flavors — upscaling is deliberately NOT gated on the MTL engine; the JNI bridge and
// the ORT session live here so the nomtl APK gets native upscaling too. The NCNN prebuilt
// archives still come from the external/yakuyomi-engine submodule (see cpp/CMakeLists.txt).
plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
}

android {
    namespace = "upscale"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
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
    // ONNX Runtime for the NNAPI/CPU upscaler path (OCR shares the same AAR inside :yakuyomi).
    implementation(libs.onnxruntime.android)
}
// KMK <--
