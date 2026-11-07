package upscale.app

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.achievement.service.AchievementManager
import tachiyomi.domain.achievement.service.AchievementPreferences
import tachiyomi.domain.achievement.service.RotatingAchievementPool

/**
 * Moves the achievement hooks OUT of the upscaling engine: the spine reports events on its
 * status hub, and this observer turns them into unlocks. Ported from the old
 * `UpscaleEngine.reportUpscaleServed` with identical thresholds and ids.
 */
@SingleIn(AppScope::class)
@Inject
class UpscaleAchievementObserver(
    status: UpscaleStatusHub,
    private val achievementManager: AchievementManager,
    private val achievementPrefs: AchievementPreferences,
    private val rotatingPool: RotatingAchievementPool,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        status.events
            .onEach { event: upscale.status.UpscaleStatusHub.Event ->
                when (event) {
                    is upscale.status.UpscaleStatusHub.Event.Served -> onServed(event)
                    is upscale.status.UpscaleStatusHub.Event.Failed -> {}
                }
            }
            .launchIn(scope)
    }

    private fun onServed(event: upscale.status.UpscaleStatusHub.Event.Served) {
        if (event.prefetched) return
        try {
            achievementManager.tryUnlockDirect("upscale_first")
            val total = achievementPrefs.incrementUpscalesServed()
            achievementManager.onUpscaled(total)
            when (event.backendPref) {
                "VULKAN" -> achievementManager.tryUnlockDirect("upscale_vulkan")
                "NPU" -> achievementManager.tryUnlockDirect("upscale_npu")
            }
            rotatingPool.markProgress("rotating_weekly_upscale_20")
            val pageCount = achievementPrefs.incrementUpscalePageCount(event.cacheKey)
            if (pageCount >= SECRET_SAME_PAGE_THRESHOLD) {
                achievementManager.tryUnlockDirect("secret_upscale_4k")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val SECRET_SAME_PAGE_THRESHOLD = 5
    }
}
