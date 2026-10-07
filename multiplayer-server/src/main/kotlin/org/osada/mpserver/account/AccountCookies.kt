package org.osada.mpserver.account

import io.ktor.http.Cookie

/** Cookie names, attributes and request plumbing shared by every account endpoint. */
internal class AccountCookies(
    private val secure: Boolean,
    private val sessionLifetimeMillis: Long,
) {
    // `__Host-` binds a cookie to this exact origin (Secure, Path=/, no Domain); plain-HTTP
    // localhost development cannot use the prefix, so it gets an unprefixed name instead.
    val session = if (secure) "__Host-osada_session" else "osada_session"
    val signIn = if (secure) "__Host-osada_signin" else "osada_signin"

    fun sessionCookie(token: String): Cookie =
        cookie(session, token, (sessionLifetimeMillis / MILLIS_PER_SECOND).toInt())

    fun signInCookie(binding: String): Cookie =
        cookie(signIn, binding, (AccountService.SIGN_IN_WINDOW_MILLIS / MILLIS_PER_SECOND).toInt())

    fun expired(name: String): Cookie = cookie(name, "", 0)

    // Lax, not Strict: the provider's redirect back to the callback is a cross-site top-level
    // navigation, and the sign-in binding cookie must ride along with it.
    private fun cookie(
        name: String,
        value: String,
        maxAgeSeconds: Int,
    ): Cookie =
        Cookie(
            name = name,
            value = value,
            maxAge = maxAgeSeconds,
            path = "/",
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
