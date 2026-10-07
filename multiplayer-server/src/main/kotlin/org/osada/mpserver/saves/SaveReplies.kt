package org.osada.mpserver.saves

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal fun render(outcome: WriteOutcome): RenderedReply =
    when (outcome) {
        is WriteOutcome.Accepted ->
            reply(HttpStatusCode.OK) {
                put("run", runJson(outcome.run))
                put("unchanged", JsonPrimitive(outcome.unchanged))
            }
        is WriteOutcome.Stale ->
            reply(HttpStatusCode.PreconditionFailed) {
                put("error", JsonPrimitive("stale"))
                put("run", outcome.current?.let(::runJson) ?: JsonNull)
            }
        is WriteOutcome.Gone ->
            reply(HttpStatusCode.Gone) {
                put("error", JsonPrimitive(outcome.reason))
                put("dataEpoch", JsonPrimitive(outcome.dataEpoch))
            }
        is WriteOutcome.QuotaExceeded ->
            reply(HttpStatusCode.InsufficientStorage) {
                put("error", JsonPrimitive("quota-exceeded"))
                put("usedBytes", JsonPrimitive(outcome.usedBytes))
                put("quotaBytes", JsonPrimitive(outcome.quotaBytes))
            }
        WriteOutcome.MissionStartImmutable ->
            reply(HttpStatusCode.Conflict) {
                put("error", JsonPrimitive("mission-start-immutable"))
            }
    }

private fun reply(
    status: HttpStatusCode,
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): RenderedReply = RenderedReply(status.value, buildJsonObject(build).toString())

internal fun runJson(run: RunRecord): JsonObject =
    buildJsonObject {
        put("runInstanceId", JsonPrimitive(run.runInstanceId))
        put("campaignFile", JsonPrimitive(run.campaignFile))
        put("revision", JsonPrimitive(run.revision))
        put("parentRevision", JsonPrimitive(run.parentRevision))
        put("contentHash", JsonPrimitive(run.contentHash))
        put("state", JsonPrimitive(run.state))
        put(
            "summary",
            runCatching { Json.parseToJsonElement(run.summary) }.getOrDefault(JsonObject(emptyMap())) as JsonElement,
        )
        put("clientPlayedAt", run.clientPlayedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("serverSavedAt", JsonPrimitive(run.serverSavedAt))
    }

internal fun etag(revision: Long): String = "\"r$revision\""

internal fun parseEtag(value: String?): Long? =
    value
        ?.trim()
        ?.removePrefix("W/")
        ?.removeSurrounding("\"")
        ?.removePrefix("r")
        ?.toLongOrNull()

internal suspend fun ApplicationCall.respondJsonText(
    status: HttpStatusCode,
    body: String,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(body, ContentType.Application.Json, status)
}

internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
) {
    respondJsonText(status, buildJsonObject { put("error", JsonPrimitive(code)) }.toString())
}

internal val READABLE_ROLES = setOf(SaveRepository.LATEST, SaveRepository.PREVIOUS, SaveRepository.MISSION_START)
const val IDEMPOTENCY_HEADER = "Idempotency-Key"
const val EPOCH_HEADER = "X-Osada-Epoch"
const val HASH_HEADER = "X-Osada-Content-Hash"
const val REVISION_HEADER = "X-Osada-Revision"
const val CAMPAIGN_HEADER = "X-Osada-Campaign-File"
const val SUMMARY_HEADER = "X-Osada-Summary"
const val PLAYED_AT_HEADER = "X-Osada-Played-At"
const val COMPLETED_HEADER = "X-Osada-Completed"
const val MISSION_HEADER = "X-Osada-Mission-Key"
