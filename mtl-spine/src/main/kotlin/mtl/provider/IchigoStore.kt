package mtl.provider

/**
 * Persistence seam for the Ichigo image service — where the auth tokens, device identity and
 * account email live. The old code reached these through `TranslationPreferences` directly;
 * the provider layer only needs this contract, and the app wiring implements it from prefs.
 */
interface IchigoStore {
    fun accessToken(): String
    fun refreshToken(): String
    fun email(): String

    fun setAccessToken(value: String)
    fun setRefreshToken(value: String)
    fun setEmail(value: String)

    fun clientUuid(): String
    fun setClientUuid(value: String)

    fun fingerprint(): String
    fun setFingerprint(value: String)

    /** Base URL override; blank means the default service. */
    fun baseUrl(): String

    /** The translation model the user picked for Ichigo, or null. */
    fun translationModel(): String?
}
