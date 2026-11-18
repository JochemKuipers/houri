package eu.kanade.tachiyomi.data.updater

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
@SingleIn(AppScope::class)
class AppUpdateChecker(
    private val peekIntoPreview: Boolean = false,
) {

    private val getApplicationRelease: GetApplicationRelease by lazy { globalAppGraph.getApplicationRelease }

    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
    ): GetApplicationRelease.Result {
        return withIOContext {
            getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                    forceCheck = forceCheck,
                    // KMK --> the no-MTL variant must fetch its own (nomtl-prefixed) APK assets
                    nomtl = BuildConfig.IS_NOMTL,
                    // KMK <--
                ),
            )
        }
    }
}

val GITHUB_REPO: String by lazy { getGithubRepo() }

// KMK -->
// Beta builds are published to their own repository by build_push, so a preview install checks
// that repository instead of the stable one. Pointing both at the same place meant the beta
// updater read stable tags and never saw a newer beta (issue #80), and the stable check relied on
// GitHub's rename redirect to find the real repository.
private const val STABLE_REPO = "PineappleTwilight/hyoukan"
private const val BETA_REPO = "PineappleTwilight/houri-beta"
// KMK <--

fun getGithubRepo(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        BETA_REPO
    } else {
        STABLE_REPO
    }

val RELEASE_TAG: String by lazy { getReleaseTag() }

fun getReleaseTag(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
