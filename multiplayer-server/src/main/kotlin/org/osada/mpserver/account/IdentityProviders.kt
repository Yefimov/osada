package org.osada.mpserver.account

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.osada.mpserver.protocol.stringOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** What a provider proved: a stable subject inside that provider's namespace. Nothing else is kept. */
data class ProviderIdentity(
    val provider: String,
    val subject: String,
)

class ProviderRejected(
    reason: String,
) : Exception(reason)

internal fun refuse(reason: String): Nothing = throw ProviderRejected(reason)

/** One sign-in round trip: where to send the browser, and how to turn the returned code into an identity. */
interface IdentityProvider {
    val id: String

    fun authorizationUrl(request: AuthorizationRequest): String

    suspend fun exchange(
        code: String,
        request: AuthorizationRequest,
    ): ProviderIdentity
}

class AuthorizationRequest(
    val state: String,
    val codeVerifier: String,
    val nonce: String,
    val redirectUri: String,
)

/** The only outbound HTTP the account service makes; tests replace it with canned provider replies. */
interface ProviderHttp {
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
    ): HttpReply

    suspend fun getJson(
        url: String,
        bearer: String,
    ): HttpReply
}

class HttpReply(
    val status: Int,
    val body: String,
)

class JdkProviderHttp : ProviderHttp {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
    ): HttpReply =
        send(
            HttpRequest
                .newBuilder(URI(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build(),
        )

    override suspend fun getJson(
        url: String,
        bearer: String,
    ): HttpReply =
        send(
            HttpRequest
                .newBuilder(URI(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $bearer")
                .header("User-Agent", "osada-server")
                .GET()
                .build(),
        )

    private suspend fun send(request: HttpRequest): HttpReply {
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return HttpReply(response.statusCode(), response.body().take(MAX_PROVIDER_REPLY_CHARS))
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        const val MAX_PROVIDER_REPLY_CHARS = 64 * 1024
    }
}

internal fun formEncode(form: Map<String, String>): String =
    form.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
    }

internal fun parseObject(body: String): JsonObject =
    runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { refuse("unreadable-reply") }

/**
 * Google OpenID Connect, authorization-code flow with PKCE and a nonce. Scope `openid` only: OSADA
 * needs the stable `sub` and nothing else — no email, no profile, no Drive.
 *
 * The ID token arrives straight from Google's token endpoint over TLS, which OpenID Connect Core
 * §3.1.3.7 accepts in place of a signature check; issuer, audience, expiry and nonce are still verified.
 */
class GoogleProvider(
    private val credentials: ProviderCredentials,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) : IdentityProvider {
    override val id = AccountConfig.GOOGLE

    override fun authorizationUrl(request: AuthorizationRequest): String =
        "https://accounts.google.com/o/oauth2/v2/auth?" +
            formEncode(
                mapOf(
                    "response_type" to "code",
                    "client_id" to credentials.clientId,
                    "redirect_uri" to request.redirectUri,
                    "scope" to "openid",
                    "state" to request.state,
                    "nonce" to request.nonce,
                    "code_challenge" to Tokens.pkceChallenge(request.codeVerifier),
                    "code_challenge_method" to "S256",
                    "prompt" to "select_account",
                ),
            )

    override suspend fun exchange(
        code: String,
        request: AuthorizationRequest,
    ): ProviderIdentity {
        val reply =
            http.postForm(
                "https://oauth2.googleapis.com/token",
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "client_id" to credentials.clientId,
                    "client_secret" to credentials.clientSecret,
                    "redirect_uri" to request.redirectUri,
                    "code_verifier" to request.codeVerifier,
                ),
            )
        if (reply.status != HTTP_OK) refuse("token-exchange-failed")
        val idToken = parseObject(reply.body).stringOrNull("id_token") ?: refuse("no-id-token")
        val claims = verifiedClaims(idToken, request.nonce)
        val subject = claims.stringOrNull("sub")?.takeIf { it.isNotBlank() } ?: refuse("no-subject")
        return ProviderIdentity(id, subject)
    }

    internal fun verifiedClaims(
        idToken: String,
        expectedNonce: String,
    ): JsonObject {
        val claims = decodeJwtPayload(idToken)
        val audience =
            when (val aud = claims["aud"]) {
                is JsonPrimitive -> listOf(aud.content)
                is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
        val expiresAtSeconds = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: 0L
        val failure =
            when {
                claims.stringOrNull("iss") !in GOOGLE_ISSUERS -> "wrong-issuer"
                credentials.clientId !in audience -> "wrong-audience"
                claims.stringOrNull("azp")?.let { it != credentials.clientId } == true -> "wrong-party"
                expiresAtSeconds * MILLIS_PER_SECOND <= clock() -> "expired-token"
                claims.stringOrNull("nonce")?.let { Tokens.sameSecret(it, expectedNonce) } != true -> "wrong-nonce"
                else -> null
            }
        if (failure != null) refuse(failure)
        return claims
    }

    private fun decodeJwtPayload(token: String): JsonObject {
        val parts = token.split('.')
        if (parts.size != JWT_PARTS) refuse("malformed-id-token")
        val payload =
            runCatching { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }
                .getOrElse { refuse("malformed-id-token") }
        return parseObject(payload)
    }

    private companion object {
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        const val JWT_PARTS = 3
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * GitHub OAuth app web flow with PKCE. No scope is requested, which grants read access to public
 * profile information only — no repositories, no Gists. The access token is used once to learn the
 * numeric user id and is then dropped; it is never stored.
 */
class GitHubProvider(
    private val credentials: ProviderCredentials,
    private val http: ProviderHttp,
) : IdentityProvider {
    override val id = AccountConfig.GITHUB

    override fun authorizationUrl(request: AuthorizationRequest): String =
        "https://github.com/login/oauth/authorize?" +
            formEncode(
                mapOf(
                    "client_id" to credentials.clientId,
                    "redirect_uri" to request.redirectUri,
                    "state" to request.state,
                    "allow_signup" to "true",
                    "code_challenge" to Tokens.pkceChallenge(request.codeVerifier),
                    "code_challenge_method" to "S256",
                ),
            )

    override suspend fun exchange(
        code: String,
        request: AuthorizationRequest,
    ): ProviderIdentity {
        val tokenReply =
            http.postForm(
                "https://github.com/login/oauth/access_token",
                mapOf(
                    "client_id" to credentials.clientId,
                    "client_secret" to credentials.clientSecret,
                    "code" to code,
                    "redirect_uri" to request.redirectUri,
                    "code_verifier" to request.codeVerifier,
                ),
            )
        // GitHub answers 200 with an `error` field for a bad code, so the token's presence is the test.
        val accessToken =
            parseObject(tokenReply.body).stringOrNull("access_token")?.takeIf { tokenReply.status == HTTP_OK }
                ?: refuse("token-exchange-failed")
        val userReply = http.getJson("https://api.github.com/user", accessToken)
        if (userReply.status != HTTP_OK) refuse("user-lookup-failed")
        val userId =
            (parseObject(userReply.body)["id"] as? JsonPrimitive)?.longOrNull ?: refuse("no-subject")
        return ProviderIdentity(id, userId.toString())
    }
}

private const val HTTP_OK = 200
