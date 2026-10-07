package org.osada.mpserver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.osada.mpserver.account.AccountConfig
import org.osada.mpserver.account.AccountDatabase
import org.osada.mpserver.account.AccountModule
import org.osada.mpserver.account.AccountSchema
import org.osada.mpserver.account.AccountService
import org.osada.mpserver.account.AuthorizationRequest
import org.osada.mpserver.account.IdentityProvider
import org.osada.mpserver.account.ProviderCredentials
import org.osada.mpserver.account.ProviderIdentity
import org.osada.mpserver.account.ProviderRejected
import org.osada.mpserver.account.SessionService
import org.osada.mpserver.protocol.stringOrNull
import java.net.URI

internal const val TEST_ORIGIN = "https://osada.test"

/** A provider whose consent screen is a map from authorization code to subject. */
internal class FakeProvider(
    override val id: String,
) : IdentityProvider {
    val subjectsByCode = mutableMapOf<String, String>()
    var lastRequest: AuthorizationRequest? = null

    override fun authorizationUrl(request: AuthorizationRequest): String =
        "https://$id.example/authorize?state=${request.state}"

    override suspend fun exchange(
        code: String,
        request: AuthorizationRequest,
    ): ProviderIdentity {
        lastRequest = request
        val subject = subjectsByCode[code] ?: throw ProviderRejected("bad-code")
        return ProviderIdentity(id, subject)
    }
}

internal class AccountHarness(
    signupEnabled: Boolean = true,
    sessionDays: Long = 30,
    uploadsEnabled: Boolean = true,
    maxSnapshotBytes: Long = AccountConfig.DEFAULT_MAX_SNAPSHOT_BYTES,
) {
    var now = 1_800_000_000_000L
    val google = FakeProvider(AccountConfig.GOOGLE)
    val github = FakeProvider(AccountConfig.GITHUB)
    val config =
        AccountConfig(
            enabled = true,
            signupEnabled = signupEnabled,
            databaseFile = null,
            publicOrigin = TEST_ORIGIN,
            cookieSecure = false,
            sessionLifetimeMillis = sessionDays * AccountConfig.MILLIS_PER_DAY,
            providers =
                listOf(
                    ProviderCredentials(AccountConfig.GOOGLE, "g-id", "g-secret"),
                    ProviderCredentials(AccountConfig.GITHUB, "h-id", "h-secret"),
                ),
            uploadsEnabled = uploadsEnabled,
            maxSnapshotBytes = maxSnapshotBytes,
        )
    val database = AccountDatabase.open("jdbc:sqlite::memory:").also { AccountSchema.migrate(it) }
    val service = AccountService(database, config)
    val module =
        AccountModule(
            config = config,
            service = service,
            sessions = SessionService(database, config.sessionLifetimeMillis),
            providers = mapOf(google.id to google, github.id to github),
            allowedOrigins = emptySet(),
            clock = { now },
        )

    fun install(builder: ApplicationTestBuilder) {
        builder.application { roomServerModule(testConfig(), module) }
    }
}

/** One browser: its own cookie jar, never following redirects so the callback result is visible. */
internal class Browser(
    private val client: HttpClient,
    private val origin: String? = TEST_ORIGIN,
) {
    val cookies = mutableMapOf<String, String>()
    var csrf: String? = null

    suspend fun get(path: String): HttpResponse = client.get(path) { applyCookies() }.also(::keepCookies)

    suspend fun post(
        path: String,
        body: String = "{}",
        withCsrf: Boolean = true,
    ): HttpResponse =
        client
            .post(path) {
                applyCookies()
                origin?.let { header(HttpHeaders.Origin, it) }
                if (withCsrf) csrf?.let { header(AccountModule.CSRF_HEADER, it) }
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }.also(::keepCookies)

    /** Any method, with the session's Origin and CSRF header, for the save endpoints. */
    suspend fun send(
        method: HttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse =
        client
            .request(path) {
                this.method = method
                applyCookies()
                origin?.let { header(HttpHeaders.Origin, it) }
                csrf?.let { header(AccountModule.CSRF_HEADER, it) }
                headers.forEach { (name, value) -> header(name, value) }
                if (body != null) {
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(body)
                }
            }.also(::keepCookies)

    suspend fun me(): JsonObject = json(get("/api/account/me")).also { csrf = it.stringOrNull("csrf") }

    /** Runs the whole round trip; returns the fragment result code the game would read. */
    suspend fun signIn(
        provider: FakeProvider,
        code: String,
        intent: String = "signin",
    ): String {
        val started = json(post("/api/auth/${provider.id}/start", """{"intent":"$intent"}"""))
        val url = started.stringOrNull("url") ?: return started.stringOrNull("error") ?: "no-url"
        val state = URI(url).query.substringAfter("state=")
        return callback(provider.id, "state=$state&code=$code")
    }

    suspend fun callback(
        provider: String,
        query: String,
    ): String {
        val response = get("/api/auth/$provider/callback?$query")
        return response.headers[HttpHeaders.Location].orEmpty().substringAfter("#osada-account=")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyCookies() {
        if (cookies.isNotEmpty()) {
            header(
                HttpHeaders.Cookie,
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            )
        }
    }

    private fun keepCookies(response: HttpResponse) {
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader).forEach {
            if (it.value.isEmpty() || it.maxAge == 0) cookies.remove(it.name) else cookies[it.name] = it.value
        }
    }
}

internal suspend fun json(response: HttpResponse): JsonObject =
    Json.parseToJsonElement(response.bodyAsText()).jsonObject

internal fun ApplicationTestBuilder.browser(origin: String? = TEST_ORIGIN): Browser =
    Browser(createClient { followRedirects = false }, origin)
