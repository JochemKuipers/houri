package tachiyomi.domain.achievement.service

/** Offline stubs for check.sh: only the surfaces UpscaleAchievementObserver calls. Never compiled into the app. */
class AchievementManager {
    fun tryUnlockDirect(id: String): Boolean = false
    fun onUpscaled(count: Long): List<String> = emptyList()
}

class AchievementPreferences {
    fun incrementUpscalesServed(): Long = 0
    fun incrementUpscalePageCount(cacheKey: String): Int = 0
}

class RotatingAchievementPool {
    fun markProgress(id: String, progress: Int = 1) { }
}
