package org.osada.mpserver

import kotlinx.coroutines.runBlocking
import org.osada.mpserver.account.AuthorizationRequest
import org.osada.mpserver.account.GitHubProvider
import org.osada.mpserver.account.GoogleProvider
import org.osada.mpserver.account.HttpReply
import org.osada.mpserver.account.ProviderCredentials
import org.osada.mpserver.account.ProviderHttp
import org.osada.mpserver.account.ProviderIdentity
import org.osada.mpserver.account.ProviderRejected
import org.osada.mpserver.account.Tokens
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityProviderTest {
    private class CannedHttp(
        val post: HttpReply,
        val get: HttpReply = HttpReply(500, ""),
    ) : ProviderHttp {
        val posted = mutableListOf<Map<String, String>>()
        var bearer: String? = null

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
        ): HttpReply = post.also { posted += form }

        override suspend fun getJson(
            url: String,
            bearer: String,
        ): HttpReply = get.also { this.bearer = bearer }
    }

    private val now = 1_800_000_000_000L
    private val request =
        AuthorizationRequest("state-1", "verifier-1", "nonce-1", "https://osada.test/api/auth/google/callback")
    private val google = ProviderCredentials("google", "client-g", "secret-g")

    private fun idToken(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            """{"alg":"RS256"}""",
            claims,
            "signature",
        ).joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    private fun claims(
        iss: String = "https://accounts.google.com",
        aud: String = "\"client-g\"",
        exp: Long = now / 1000 + 300,
        nonce: String = "nonce-1",
    ) = """{"iss":"$iss","aud":$aud,"sub":"1234567890","exp":$exp,"nonce":"$nonce","email":"x@example.com"}"""

    private fun googleWith(claims: String): GoogleProvider =
        GoogleProvider(google, CannedHttp(HttpReply(200, """{"id_token":"${idToken(claims)}"}""")), clock = { now })

    @Test
    fun `google yields issuer-scoped subject and never the email`() =
        runBlocking {
            assertEquals(ProviderIdentity("google", "1234567890"), googleWith(claims()).exchange("code", request))
        }

    @Test
    fun `google id tokens for another client, issuer, nonce or past expiry are refused`() {
        val cases =
            mapOf(
                "wrong-audience" to claims(aud = "\"someone-else\""),
                "wrong-issuer" to claims(iss = "https://evil.example"),
                "wrong-nonce" to claims(nonce = "replayed"),
                "expired-token" to claims(exp = now / 1000 - 1),
            )
        cases.forEach { (expected, claims) ->
            val failure =
                assertFailsWith<ProviderRejected> { runBlocking { googleWith(claims).exchange("code", request) } }
            assertEquals(expected, failure.message)
        }
    }

    @Test
    fun `google authorization asks only for openid and binds PKCE, state and nonce`() {
        val url = URI(GoogleProvider(google, CannedHttp(HttpReply(500, ""))).authorizationUrl(request))
        val query = url.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("openid", query["scope"])
        assertEquals("state-1", query["state"])
        assertEquals("nonce-1", query["nonce"])
        assertEquals(Tokens.pkceChallenge("verifier-1"), query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
    }

    @Test
    fun `github resolves the numeric user id with the code verifier and drops the token`() =
        runBlocking {
            val http =
                CannedHttp(
                    post = HttpReply(200, """{"access_token":"gho_abc","token_type":"bearer","scope":""}"""),
                    get = HttpReply(200, """{"id":583231,"login":"octocat","email":null}"""),
                )
            val provider = GitHubProvider(ProviderCredentials("github", "client-h", "secret-h"), http)
            assertEquals(ProviderIdentity("github", "583231"), provider.exchange("code", request))
            assertEquals("verifier-1", http.posted.single()["code_verifier"])
            assertEquals("gho_abc", http.bearer)
            val authorize = provider.authorizationUrl(request)
            assertFalse("scope=" in authorize, "no scope means public profile only")
            assertTrue("code_challenge_method=S256" in authorize)
        }

    @Test
    fun `github's 200-with-error reply for a bad code is a refusal`() {
        val http = CannedHttp(HttpReply(200, """{"error":"bad_verification_code"}"""))
        val provider = GitHubProvider(ProviderCredentials("github", "client-h", "secret-h"), http)
        val failure = assertFailsWith<ProviderRejected> { runBlocking { provider.exchange("code", request) } }
        assertEquals("token-exchange-failed", failure.message)
    }
}
