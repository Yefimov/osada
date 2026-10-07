package org.osada.mpserver.account

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.osada.mpserver.protocol.booleanOrNull
import org.osada.mpserver.protocol.stringOrNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.osada.mpserver.account")

/** Everything the account endpoints need; absent when accounts are switched off. */
class AccountModule(
    val config: AccountConfig,
    val service: AccountService,
    val sessions: SessionService,
    val providers: Map<String, IdentityProvider>,
    val allowedOrigins: Set<String>,
    val clock: () -> Long = System::currentTimeMillis,
) {
    internal val cookies = AccountCookies(config.cookieSecure, config.sessionLifetimeMillis)
    internal val signInLimiter = RateLimiter(limit = SIGN_IN_ATTEMPTS, windowMillis = WINDOW_MILLIS)
    internal val accountLimiter = RateLimiter(limit = ACCOUNT_ACTIONS, windowMillis = WINDOW_MILLIS)

    /** The session the browser's cookie names, if it is live. The account id is derived only from this. */
    suspend fun currentSession(call: ApplicationCall): ActiveSession? =
        call.request.cookies[cookies.session]
            ?.takeIf { it.isNotEmpty() }
            ?.let { sessions.resolveSession(it, clock()) }

    /**
     * The session of a state-changing request: trusted Origin, live cookie and the matching CSRF
     * header. Null means the caller has already been answered with the refusal.
     */
    suspend fun authorizedSession(
        call: ApplicationCall,
        limiter: RateLimiter = accountLimiter,
    ): ActiveSession? {
        val session = currentSession(call)
        val refusal =
            when {
                !originTrusted(call.request.header(HttpHeaders.Origin), config.publicOrigin, allowedOrigins) ->
                    "origin-refused"
                session == null -> "signed-out"
                call.request
                    .header(
                        CSRF_HEADER,
                    )?.let { Tokens.sameSecret(it, session.csrfToken) } != true -> "csrf-refused"
                !limiter.allow(session.accountId, clock()) -> "rate-limited"
                else -> null
            }
        if (refusal != null) {
            call.respondRefusal(
                refusal,
                if (refusal ==
                    "rate-limited"
                ) {
                    HttpStatusCode.TooManyRequests
                } else {
                    HttpStatusCode.Forbidden
                },
            )
        }
        return session.takeIf { refusal == null }
    }

    companion object {
        const val CSRF_HEADER = "X-Osada-Csrf"
        private const val SIGN_IN_ATTEMPTS = 30
        private const val ACCOUNT_ACTIONS = 60
        private const val WINDOW_MILLIS = 10 * 60_000L
    }
}

/**
 * `/api/account/...` and `/api/auth/...` (§6.1). With accounts off only the config endpoint exists,
 * and it says so, so the browser hides every account control and guest play is untouched.
 */
fun Route.accountRoutes(module: AccountModule?) {
    get("/api/account/config") {
        call.respondJson(
            buildJsonObject {
                put("enabled", JsonPrimitive(module != null))
                if (module != null) {
                    put("signupEnabled", JsonPrimitive(module.config.signupEnabled))
                    put(
                        "providers",
                        JsonArray(
                            module.providers.keys
                                .sorted()
                                .map(::JsonPrimitive),
                        ),
                    )
                }
            },
        )
    }
    if (module == null) return

    get("/api/account/me") { module.describeSession(call) }
    post("/api/auth/{provider}/start") { module.startSignIn(call, call.parameters["provider"].orEmpty()) }
    get("/api/auth/{provider}/callback") { module.finishSignIn(call, call.parameters["provider"].orEmpty()) }
    post("/api/auth/logout") { module.logout(call) }
    post("/api/account/unlink") { module.unlink(call) }
    post("/api/account/delete") { module.deleteAccount(call) }
}

/** Polling this does not renew retention (§5.1); it only answers who the cookie belongs to. */
private suspend fun AccountModule.describeSession(call: ApplicationCall) {
    val now = clock()
    var session = currentSession(call)
    // Rotate a session past half its life so a long-lived cookie keeps changing (§6.1).
    if (session != null && now - session.createdAt > config.sessionLifetimeMillis / 2) {
        val renewed = sessions.issueSession(session.accountId, now)
        sessions.revokeSession(session.tokenHash, now)
        call.response.cookies.append(cookies.sessionCookie(renewed.token))
        session = sessions.resolveSession(renewed.token, now)
    }
    val view = session?.let { service.view(it.accountId) }
    if (session == null || view == null) {
        call.respondJson(buildJsonObject { put("signedIn", JsonPrimitive(false)) })
        return
    }
    call.respondJson(accountJson(view, session.csrfToken))
}

private fun accountJson(
    view: AccountView,
    csrfToken: String,
): JsonObject =
    buildJsonObject {
        put("signedIn", JsonPrimitive(true))
        put("accountId", JsonPrimitive(view.id))
        put("csrf", JsonPrimitive(csrfToken))
        put("createdAt", JsonPrimitive(view.createdAt))
        put("lastActiveAt", JsonPrimitive(view.lastActiveAt))
        put("dataEpoch", JsonPrimitive(view.dataEpoch))
        put("usedBytes", JsonPrimitive(view.usedBytes))
        put("quotaBytes", JsonPrimitive(view.quotaBytes))
        put(
            "identities",
            JsonArray(
                view.identities.map {
                    buildJsonObject {
                        put("provider", JsonPrimitive(it.provider))
                        put("linkedAt", JsonPrimitive(it.linkedAt))
                    }
                },
            ),
        )
    }

/**
 * Returns the provider URL as JSON instead of redirecting, so beginning a sign-in is a same-origin
 * POST (Origin-checked; linking also needs the CSRF header) rather than a link any site could embed.
 */
private suspend fun AccountModule.startSignIn(
    call: ApplicationCall,
    providerId: String,
) {
    val provider = providers[providerId]
    val body = call.receiveSmallJson() ?: JsonObject(emptyMap())
    val linking = body.stringOrNull("intent") == "link"
    val now = clock()
    val linkSession = if (linking) authorizedSession(call) ?: return else null
    val refusal =
        when {
            provider == null -> "unknown-provider"
            !originTrusted(
                call.request.header(HttpHeaders.Origin),
                config.publicOrigin,
                allowedOrigins,
            ) -> "origin-refused"
            !signInLimiter.allow(call.clientAddress(), now) -> "rate-limited"
            else -> null
        }
    if (refusal != null || provider == null) {
        call.respondRefusal(refusal ?: "unknown-provider", statusFor(refusal))
        return
    }
    val started = service.beginSignIn(providerId, linkSession?.accountId, now)
    call.response.cookies.append(cookies.signInCookie(started.browserBinding))
    val url = provider.authorizationUrl(started.authorizationRequest(config.redirectUri(providerId)))
    call.respondJson(buildJsonObject { put("url", JsonPrimitive(url)) })
}

private fun StartedSignIn.authorizationRequest(redirectUri: String) =
    AuthorizationRequest(state, codeVerifier, nonce, redirectUri)

/**
 * The provider's redirect back. Always ends in a redirect to the game with a short result code in
 * the fragment (never in a server-logged query): `signed-in`, `linked`, `cancelled` or a refusal.
 */
private suspend fun AccountModule.finishSignIn(
    call: ApplicationCall,
    providerId: String,
) {
    val result =
        runCatching { completeCallback(call, providerId) }
            .getOrElse { failure ->
                when (failure) {
                    is AccountRefusal -> failure.code
                    is ProviderRejected ->
                        "provider-refused".also {
                            logger.info(
                                "{} sign-in refused: {}",
                                providerId,
                                failure.message,
                            )
                        }
                    else -> "server-error".also { logger.warn("{} sign-in failed", providerId, failure) }
                }
            }
    call.response.cookies.append(cookies.expired(cookies.signIn))
    call.respondRedirect("/#osada-account=$result")
}

@Suppress("ReturnCount", "ThrowsCount") // Each refusal ends the sign-in before any account change.
private suspend fun AccountModule.completeCallback(
    call: ApplicationCall,
    providerId: String,
): String {
    val now = clock()
    if (!signInLimiter.allow(call.clientAddress(), now)) throw AccountRefusal("rate-limited")
    val provider = providers[providerId] ?: throw AccountRefusal("unknown-provider")
    val state = call.request.queryParameters["state"] ?: throw AccountRefusal("sign-in-expired")
    val pending = service.consumeSignIn(providerId, state, call.request.cookies[cookies.signIn], now)
    // A cancelled consent screen comes back with `error` and no code: nothing changes.
    val code = call.request.queryParameters["code"]?.takeIf { call.request.queryParameters["error"] == null }
    if (code == null) return "cancelled"
    val identity =
        provider.exchange(
            code,
            AuthorizationRequest(state, pending.codeVerifier, pending.nonce, config.redirectUri(providerId)),
        )
    val accountId = service.completeSignIn(identity, pending.linkAccountId, clock())
    if (pending.linkAccountId != null) return "linked"
    // A fresh sign-in replaces whatever session this browser held (rotation on privilege change).
    currentSession(call)?.let { sessions.revokeSession(it.tokenHash, now) }
    call.response.cookies.append(cookies.sessionCookie(sessions.issueSession(accountId, now).token))
    return "signed-in"
}

private suspend fun AccountModule.logout(call: ApplicationCall) {
    val session = authorizedSession(call) ?: return
    val everywhere = call.receiveSmallJson()?.booleanOrNull("everywhere") == true
    if (everywhere) {
        sessions.revokeAllSessions(
            session.accountId,
            clock(),
        )
    } else {
        sessions.revokeSession(session.tokenHash, clock())
    }
    call.response.cookies.append(cookies.expired(cookies.session))
    call.respondJson(buildJsonObject { put("signedIn", JsonPrimitive(false)) })
}

private suspend fun AccountModule.unlink(call: ApplicationCall) {
    val session = authorizedSession(call) ?: return
    val provider = call.receiveSmallJson()?.stringOrNull("provider").orEmpty()
    runCatching { service.unlink(session.accountId, provider) }
        .onSuccess { describeSession(call) }
        .onFailure { failure ->
            call.respondRefusal((failure as? AccountRefusal ?: throw failure).code, HttpStatusCode.Conflict)
        }
}

/** Requires the literal confirmation word so a stray request cannot delete an account. */
private suspend fun AccountModule.deleteAccount(call: ApplicationCall) {
    val session = authorizedSession(call) ?: return
    if (call.receiveSmallJson()?.stringOrNull("confirm") != "DELETE") {
        call.respondRefusal("confirmation-required", HttpStatusCode.BadRequest)
        return
    }
    service.deleteAccount(session.accountId)
    logger.info("Account deleted on request")
    call.response.cookies.append(cookies.expired(cookies.session))
    call.respondJson(buildJsonObject { put("signedIn", JsonPrimitive(false)) })
}

private fun statusFor(refusal: String?): HttpStatusCode =
    when (refusal) {
        "rate-limited" -> HttpStatusCode.TooManyRequests
        "unknown-provider" -> HttpStatusCode.NotFound
        else -> HttpStatusCode.Forbidden
    }
