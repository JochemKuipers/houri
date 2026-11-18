package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {

    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    /**
     * [isPreview] is if current version is Preview (beta) build
     *
     * [versionTag] is the version of new release
     *
     * Release (stable) version will compare with current's [versionName] ("v0.1.2")
     *
     * Preview (beta) version will compare with current's [commitCount] ("r1234")
     *
     * The tag shapes are fixed by the publishing workflows: `build_push` tags beta releases
     * `r<commitCount>` in the beta repository, `auto_release` tags stable ones `v<versionName>`.
     * A tag that does not have the expected shape is treated as "no update" rather than throwing:
     * the beta repository also carries hand-made `2.0.0-alphaN` releases, and the preview check
     * used to parse their numbers with `toInt()` and fail on the second dot.
     */
    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        return if (isPreview) {
            val newCommit = previewCommitOf(versionTag) ?: return false
            newCommit > commitCount
        } else {
            // Strips prefixes like "v" and compares numeric segments left to right. Missing
            // segments count as 0 on either side, so "2.0" against "1.25.3" is an update and
            // "1.25.3" against "1.25.3.1" is one too, instead of an index out of bounds.
            val newSemVer = versionTag.removePrefix("v").split(".")
            val oldSemVer = versionName.removePrefix("v").split(".")

            for (index in 0 until maxOf(newSemVer.size, oldSemVer.size)) {
                val new = newSemVer.getOrNull(index)?.toIntOrNull() ?: 0
                val old = oldSemVer.getOrNull(index)?.toIntOrNull() ?: 0
                if (new > old) return true
                if (new < old) return false
            }

            false
        }
    }

    /** Reads the commit count out of a beta tag (`r1234`); null when the tag is not one. */
    private fun previewCommitOf(versionTag: String): Int? =
        PREVIEW_COMMIT_TAG.matchEntire(versionTag)?.groupValues?.get(1)?.toIntOrNull()

    data class Arguments(
        val isFoss: Boolean,
        /** If current version is Preview (beta) build */
        val isPreview: Boolean,
        /** Commit count of current version */
        val commitCount: Int,
        /** Current version name, could be version tag (v0.1.2) or commit count (r1234) */
        val versionName: String,
        /** Repository name */
        val repository: String,
        /** Force check for new update */
        val forceCheck: Boolean = false,
        // KMK -->
        /** Whether this is the lean no-MTL variant (release assets are prefixed "nomtl"). */
        val nomtl: Boolean = false,
        // KMK <--
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }

    companion object {
        private val PREVIEW_COMMIT_TAG = Regex("""^r(\d+)$""", RegexOption.IGNORE_CASE)
    }
}
