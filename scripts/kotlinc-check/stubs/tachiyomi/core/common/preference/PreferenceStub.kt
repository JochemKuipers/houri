package tachiyomi.core.common.preference

/** Offline stub for check.sh: the Preference surface the upscaling app layer touches. Never compiled into the app. */
interface Preference<T> {
    fun get(): T
    fun set(value: T)
    fun delete()
    fun isSet(): Boolean
    fun changes(): kotlinx.coroutines.flow.Flow<T>
}

/** Offline stub for check.sh. Never compiled into the app. */
interface PreferenceStore {
    fun getString(key: String, defaultValue: String = ""): Preference<String>
    fun getLong(key: String, defaultValue: Long = 0): Preference<Long>
    fun getInt(key: String, defaultValue: Int = 0): Preference<Int>
    fun getFloat(key: String, defaultValue: Float = 0f): Preference<Float>
    fun getBoolean(key: String, defaultValue: Boolean = false): Preference<Boolean>
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Preference<Set<String>>
}
