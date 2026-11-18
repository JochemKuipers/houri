import kotlinx.coroutines.runBlocking
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

/**
 * The in-app updater's version math, against the real [GetApplicationRelease].
 *
 * The two channels tag their releases differently — stable `v<versionName>`, beta `r<commitCount>`
 * — and the beta repository also carries hand-made `2.0.0-alphaN` releases that are not commit
 * tags at all. Parsing those with `toInt()` used to throw, so a beta install silently stopped
 * checking for updates; this pins the rules that replaced it.
 */

private class FakeReleaseService(private val release: Release?) : ReleaseService {
    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? = release
}

private var failures = 0

private fun case(
    name: String,
    expected: Boolean,
    releaseVersion: String?,
    isPreview: Boolean,
    commitCount: Int,
    versionName: String,
) {
    val service = FakeReleaseService(
        releaseVersion?.let { Release(version = it, info = "info", releaseLink = "link", downloadLink = "apk") },
    )
    val result = runBlocking {
        GetApplicationRelease(service).await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = isPreview,
                commitCount = commitCount,
                versionName = versionName,
                repository = "test",
            ),
        )
    }
    val actual = result is GetApplicationRelease.Result.NewUpdate
    if (actual == expected) {
        println("ok: $name")
    } else {
        failures++
        println("FAIL: $name — expected newUpdate=$expected, got $result (tag=$releaseVersion)")
    }
}

fun main() {
    // Beta channel: releases are tagged r<commitCount> in the beta repository.
    case("beta: newer commit is an update", true, "r2000", isPreview = true, commitCount = 1000, versionName = "")
    case("beta: same commit is not", false, "r1000", isPreview = true, commitCount = 1000, versionName = "")
    case("beta: older commit is not", false, "r999", isPreview = true, commitCount = 1000, versionName = "")
    // Hand-made releases in the beta repository; these must not throw or compare as commits.
    case("beta: legacy alpha tag is not an update", false, "2.0.0-alpha4", isPreview = true, commitCount = 1000, versionName = "")
    case("beta: unprefixed tag is not an update", false, "2000", isPreview = true, commitCount = 1000, versionName = "")
    case("beta: no release means nothing to do", false, null, isPreview = true, commitCount = 1000, versionName = "")

    // Stable channel: releases are tagged v<versionName> in the app repository.
    case("stable: newer minor is an update", true, "v1.26.0", isPreview = false, commitCount = 0, versionName = "v1.25.3")
    case("stable: newer major is an update", true, "v2.0.0", isPreview = false, commitCount = 0, versionName = "v1.25.3")
    case("stable: same version is not", false, "v1.25.3", isPreview = false, commitCount = 0, versionName = "v1.25.3")
    case("stable: older patch is not", false, "v1.25.2", isPreview = false, commitCount = 0, versionName = "v1.25.3")
    case("stable: shorter tag is an update", true, "v2.0", isPreview = false, commitCount = 0, versionName = "v1.25.3")
    case("stable: longer tag is an update", true, "v1.25.3.1", isPreview = false, commitCount = 0, versionName = "v1.25.3")
    case("stable: unprefixed tag still compares", true, "1.26.0", isPreview = false, commitCount = 0, versionName = "1.25.3")
    case("stable: no release means nothing to do", false, null, isPreview = false, commitCount = 0, versionName = "v1.25.3")

    if (failures > 0) {
        println("FAIL — $failures case(s)")
        kotlin.system.exitProcess(1)
    }
    println("PASS — all cases")
}
