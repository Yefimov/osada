package org.osada.account

import kotlinx.browser.window
import org.w3c.fetch.Response

/**
 * The browser end of `/api/account` and `/api/auth`. Same-origin only: the session is an HttpOnly
 * cookie the page never sees, and each state-changing call carries the CSRF token from `/me`.
 *
 * Every call answers through [AccountReply]; a network failure is status 0, never an exception, so
 * a provider or server outage can only ever leave the player a guest (§6.1).
 */
class AccountReply(
    val status: Int,
    val json: dynamic,
) {
    val ok: Boolean get() = status in HTTP_OK_RANGE
    val error: String? get() = json?.error as? String

    private companion object {
        val HTTP_OK_RANGE = 200..299
    }
}

/** A raw answer: [text] is the body as received, [header] reads a response header. */
class HttpResult(
    val status: Int,
    val text: String,
    private val response: Response?,
) {
    fun header(name: String): String? = response?.headers?.get(name)
}

object AccountApi {
    /**
     * Accounts exist only where the page is served by the OSADA server in a secure context (HTTPS,
     * or localhost in development). On the legacy plain-HTTP address the session cookie is `Secure`
     * and could never be set, so the account controls are not offered there at all.
     */
    fun reachable(): Boolean {
        val protocol = window.location.protocol
        return (protocol == "https:" || protocol == "http:") && window.asDynamic().isSecureContext == true
    }

    fun config(done: (AccountReply) -> Unit) = send("GET", "/api/account/config", null, null, done)

    fun me(done: (AccountReply) -> Unit) = send("GET", "/api/account/me", null, null, done)

    /** Answers with the provider URL to navigate to; linking needs the signed-in session's CSRF token. */
    fun startSignIn(
        provider: String,
        link: Boolean,
        csrf: String?,
        done: (AccountReply) -> Unit,
    ) = send(
        "POST",
        "/api/auth/$provider/start",
        if (link) """{"intent":"link"}""" else """{"intent":"signin"}""",
        csrf,
        done,
    )

    fun logout(
        everywhere: Boolean,
        csrf: String,
        done: (AccountReply) -> Unit,
    ) = send("POST", "/api/auth/logout", """{"everywhere":$everywhere}""", csrf, done)

    fun unlink(
        provider: String,
        csrf: String,
        done: (AccountReply) -> Unit,
    ) {
        val body: dynamic = js("({})")
        body.provider = provider
        send("POST", "/api/account/unlink", JSON.stringify(body), csrf, done)
    }

    /** The server also demands the literal confirmation word; the UI asks the player first. */
    fun deleteAccount(
        csrf: String,
        done: (AccountReply) -> Unit,
    ) = send("POST", "/api/account/delete", """{"confirm":"DELETE"}""", csrf, done)

    private fun send(
        method: String,
        path: String,
        body: String?,
        csrf: String?,
        done: (AccountReply) -> Unit,
    ) {
        val headers = buildMap { if (csrf != null) put(CSRF_HEADER, csrf) }
        request(method, path, body, headers) { result -> done(AccountReply(result.status, parseOrNull(result.text))) }
    }

    /**
     * One same-origin request with the session cookie. Answers exactly once; status 0 means the
     * server was not reached (offline, blocked, not served by OSADA).
     */
    internal fun request(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
        done: (HttpResult) -> Unit,
    ) {
        if (!reachable()) {
            done(HttpResult(0, "", null))
            return
        }
        val requestHeaders: dynamic = js("({})")
        requestHeaders["Accept"] = "application/json"
        if (body != null) requestHeaders["Content-Type"] = "application/json"
        headers.forEach { (name, value) -> requestHeaders[name] = value }
        val init: dynamic = js("({})")
        init.method = method
        init.headers = requestHeaders
        init.credentials = "same-origin"
        init.cache = "no-store"
        if (body != null) init.body = body
        // Answer exactly once: a throw inside [done] must not be mistaken for a network failure.
        var answered = false
        val answer = { result: HttpResult ->
            if (!answered) {
                answered = true
                done(result)
            }
        }
        window
            .fetch(path, init)
            .then { response: Response ->
                val status = response.status.toInt()
                response
                    .text()
                    .then { text -> answer(HttpResult(status, text, response)) }
                    .catch { answer(HttpResult(status, "", response)) }
            }.catch { answer(HttpResult(0, "", null)) }
    }

    internal fun parseOrNull(text: String): dynamic =
        try {
            JSON.parse<dynamic>(text)
        } catch (_: Throwable) {
            null
        }

    const val CSRF_HEADER = "X-Osada-Csrf"
}
