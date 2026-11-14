package eu.kanade.tachiyomi.network

import okhttp3.Response

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @see Response.isSuccessful
 * @since tachiyomix 1.6
 * @param code [Int] the HTTP status code
 *
 * KMK --> ABI pin: `code` must stay a plain Kotlin property (getter `getCode()`), never
 * `@JvmField`. Extensions are compiled against this shape and read `.code` across the
 * process boundary, so a package that drops the accessor kills the app the moment an
 * extension catches an HTTP error - NoSuchMethodError: No virtual method getCode()I
 * (shipped 1.25.3 / 218 carried a stale dex of this class and crashed exactly that way).
 * Same rule in reverse: any local uncommitted tweak of this file that leaks into a release
 * build takes in-process extensions down with it.
 * KMK <--
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
