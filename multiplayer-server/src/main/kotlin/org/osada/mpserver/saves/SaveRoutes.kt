package org.osada.mpserver.saves

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `GET /api/saves` — manifest; `GET|PUT|DELETE /api/saves/runs/{id}` — one playthrough's latest
 * resume; `GET|PUT /api/saves/runs/{id}/mission-start` — its immutable mission opening.
 *
 * The save itself travels as the raw request/response body and is never parsed here: the server
 * stores bytes it hashed and compressed. Metadata travels in `X-Osada-*` headers.
 */
fun Route.saveRoutes(module: SaveModule?) {
    if (module == null) return
    get("/api/saves") { module.sendManifest(call) }
    get("/api/saves/runs/{run}") {
        module.sendSnapshot(
            call,
            call.request.queryParameters["role"] ?: SaveRepository.LATEST,
        )
    }
    get("/api/saves/runs/{run}/mission-start") { module.sendSnapshot(call, SaveRepository.MISSION_START) }
    put("/api/saves/runs/{run}") { module.receiveSnapshot(call, missionStart = false) }
    put("/api/saves/runs/{run}/mission-start") { module.receiveSnapshot(call, missionStart = true) }
    delete("/api/saves/runs/{run}") { module.deleteRun(call) }
}

private suspend fun SaveModule.sendManifest(call: ApplicationCall) {
    val session = accounts.currentSession(call)
    val manifest = session?.let { repository.manifest(it.accountId) }
    if (manifest == null) {
        call.respondError(HttpStatusCode.Unauthorized, "signed-out")
        return
    }
    call.respondJsonText(
        HttpStatusCode.OK,
        buildJsonObject {
            put("dataEpoch", JsonPrimitive(manifest.dataEpoch))
            put("usedBytes", JsonPrimitive(manifest.usedBytes))
            put("quotaBytes", JsonPrimitive(manifest.quotaBytes))
            put("uploadsEnabled", JsonPrimitive(accounts.config.uploadsEnabled))
            put("runs", JsonArray(manifest.runs.map(::runJson)))
        }.toString(),
    )
}

private suspend fun SaveModule.sendSnapshot(
    call: ApplicationCall,
    role: String,
) {
    val session = accounts.currentSession(call)
    val runId = call.parameters["run"].orEmpty()
    val snapshot =
        session
            ?.takeIf { role in READABLE_ROLES && IDENTIFIER.matches(runId) }
            ?.let { repository.snapshot(it.accountId, runId, role) }
    if (snapshot == null) {
        call.respondError(if (session == null) HttpStatusCode.Unauthorized else HttpStatusCode.NotFound, "no-such-save")
        return
    }
    val decoded = SaveCodec.decompress(snapshot.compressedPayload, accounts.config.maxSnapshotBytes)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.ETag, etag(snapshot.revision))
    call.response.header(REVISION_HEADER, snapshot.revision.toString())
    call.response.header(HASH_HEADER, snapshot.contentHash)
    snapshot.missionKey?.let { call.response.header(MISSION_HEADER, it) }
    call.respondBytes(decoded, ContentType.Application.Json)
}

private suspend fun SaveModule.receiveSnapshot(
    call: ApplicationCall,
    missionStart: Boolean,
) {
    val session = accounts.authorizedSession(call, uploadLimiter) ?: return
    val now = accounts.clock()
    val refusal =
        when {
            !accounts.config.uploadsEnabled -> HttpStatusCode.ServiceUnavailable to "uploads-disabled"
            diskBelowReserve() -> HttpStatusCode.InsufficientStorage to "storage-full"
            else -> null
        }
    if (refusal != null) {
        call.respondError(refusal.first, refusal.second)
        return
    }
    uploadPermits.withPermit {
        when (val parsed = parseWrite(call, missionStart, accounts.config.maxSnapshotBytes)) {
            is ParsedWrite.Refused -> call.respondError(parsed.status, parsed.code)
            is ParsedWrite.Ready -> {
                val reply = repository.write(session.accountId, parsed.write, parsed.receipt, now, ::render)
                call.respondJsonText(HttpStatusCode.fromValue(reply.status), reply.body)
            }
        }
    }
}

private suspend fun SaveModule.deleteRun(call: ApplicationCall) {
    val session = accounts.authorizedSession(call) ?: return
    val runId = call.parameters["run"].orEmpty()
    val base = parseEtag(call.request.header(HttpHeaders.IfMatch))
    if (!IDENTIFIER.matches(runId) || base == null) {
        call.respondError(PRECONDITION_REQUIRED, "if-match-required")
        return
    }
    val reply = render(repository.delete(session.accountId, runId, base, accounts.clock()))
    call.respondJsonText(HttpStatusCode.fromValue(reply.status), reply.body)
}

private fun SaveModule.diskBelowReserve(): Boolean =
    accounts.config.databaseFile
        ?.absoluteFile
        ?.parentFile
        ?.usableSpace
        ?.let { it < accounts.config.diskReserveBytes } == true
