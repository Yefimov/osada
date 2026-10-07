package org.osada.mpserver.account

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal suspend fun ApplicationCall.respondJson(
    body: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    // Account replies describe one browser's session; no proxy or browser cache may keep them.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(body.toString(), ContentType.Application.Json, status)
}

internal suspend fun ApplicationCall.respondRefusal(
    code: String,
    status: HttpStatusCode,
) {
    respondJson(buildJsonObject { put("error", JsonPrimitive(code)) }, status)
}

/** Reads at most [limit] bytes of a JSON object body; null for anything larger, empty or malformed. */
internal suspend fun ApplicationCall.receiveSmallJson(limit: Long = SMALL_BODY_LIMIT): JsonObject? {
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > limit) return null
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    return bytes
        .takeIf { it.size <= limit }
        ?.let { runCatching { Json.parseToJsonElement(it.decodeToString()).jsonObject }.getOrNull() }
}

/**
 * The address to rate-limit. Behind nginx every request arrives from loopback, so the proxy's
 * `X-Real-IP` is trusted only then; a direct client cannot spoof it.
 */
internal fun ApplicationCall.clientAddress(): String {
    val remote = request.origin.remoteAddress
    val proxied = remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1"
    return if (proxied) request.header("X-Real-IP")?.takeIf { it.isNotBlank() } ?: remote else remote
}

/**
 * Explicit origin check for every state-changing request (§6.1), on top of SameSite cookies and the
 * per-session CSRF token. A request without an Origin header is refused.
 */
internal fun originTrusted(
    origin: String?,
    publicOrigin: String,
    allowedOrigins: Set<String>,
): Boolean {
    val normalized = origin?.trimEnd('/') ?: return false
    return normalized == publicOrigin || normalized in allowedOrigins
}

internal const val SMALL_BODY_LIMIT = 16L * 1024
