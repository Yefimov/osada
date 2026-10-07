package org.osada.mpserver.saves

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.util.Base64

internal sealed interface ParsedWrite {
    class Ready(
        val write: SnapshotWrite,
        val receipt: ReceiptKey,
    ) : ParsedWrite

    class Refused(
        val status: HttpStatusCode,
        val code: String,
    ) : ParsedWrite
}

/** Validates every header before the body is read, then reads at most [limit] bytes of it. */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // One guard per untrusted header, failing closed.
internal suspend fun parseWrite(
    call: ApplicationCall,
    missionStart: Boolean,
    limit: Long,
): ParsedWrite {
    val headers = call.request.headers
    val runId = call.parameters["run"].orEmpty()
    val requestId = headers[IDEMPOTENCY_HEADER].orEmpty()
    val epoch = headers[EPOCH_HEADER]?.toLongOrNull()
    val claimedHash = headers[HASH_HEADER].orEmpty()
    val campaignFile =
        headers[CAMPAIGN_HEADER]?.let {
            runCatching {
                URLDecoder.decode(
                    it,
                    Charsets.UTF_8,
                )
            }.getOrNull()
        }
    val summary = headers[SUMMARY_HEADER]?.let(::decodeSummary)
    val missionKey = headers[MISSION_HEADER]?.takeIf { IDENTIFIER.matches(it) }
    val creating = headers[HttpHeaders.IfNoneMatch] == "*"
    val baseRevision = parseEtag(headers[HttpHeaders.IfMatch])
    val malformed =
        when {
            !IDENTIFIER.matches(runId) -> "bad-run-id"
            !IDENTIFIER.matches(requestId) -> "idempotency-key-required"
            epoch == null -> "epoch-required"
            campaignFile.isNullOrEmpty() || campaignFile.length > MAX_CAMPAIGN_FILE_CHARS -> "bad-campaign-file"
            summary == null -> "bad-summary"
            missionStart && missionKey == null -> "mission-key-required"
            else -> null
        }
    if (malformed != null) return ParsedWrite.Refused(HttpStatusCode.BadRequest, malformed)
    // Conditional writes only (§4): create with If-None-Match: *, replace with If-Match.
    if (!missionStart && creating == (baseRevision != null)) {
        return ParsedWrite.Refused(PRECONDITION_REQUIRED, "precondition-required")
    }
    val (body, refused) = readVerifiedBody(call, limit, claimedHash)
    if (body == null) return refused ?: ParsedWrite.Refused(HttpStatusCode.BadRequest, "empty-snapshot")
    val hash = claimedHash
    val write =
        SnapshotWrite(
            runInstanceId = runId,
            campaignFile = checkNotNull(campaignFile),
            baseRevision = baseRevision,
            dataEpoch = checkNotNull(epoch),
            contentHash = hash,
            compressedPayload = SaveCodec.compress(body),
            decodedBytes = body.size.toLong(),
            summary = checkNotNull(summary),
            clientPlayedAt = headers[PLAYED_AT_HEADER]?.toLongOrNull(),
            completed = headers[COMPLETED_HEADER] == "true",
            missionKey = missionKey.takeIf { missionStart },
        )
    val receipt = ReceiptKey.of(requestId, runId, missionStart, baseRevision, epoch, hash, missionKey)
    return ParsedWrite.Ready(write, receipt)
}

/** Reads at most [limit] bytes and checks them against the hash the client claimed. */
private suspend fun readVerifiedBody(
    call: ApplicationCall,
    limit: Long,
    claimedHash: String,
): Pair<ByteArray?, ParsedWrite.Refused?> {
    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val body =
        if (declared != null &&
            declared > limit
        ) {
            null
        } else {
            call.receiveChannel().readRemaining(limit + 1).readByteArray()
        }
    val refusal =
        when {
            body == null || body.size > limit ->
                ParsedWrite.Refused(
                    HttpStatusCode.PayloadTooLarge,
                    "snapshot-too-large",
                )
            body.isEmpty() -> ParsedWrite.Refused(HttpStatusCode.BadRequest, "empty-snapshot")
            SaveCodec.contentHash(
                body,
            ) != claimedHash -> ParsedWrite.Refused(HttpStatusCode.BadRequest, "hash-mismatch")
            else -> null
        }
    return (if (refusal == null) body else null) to refusal
}

/** The summary is display data for the conflict cards: a small JSON object, stored as given. */
private fun decodeSummary(header: String): String? =
    runCatching { String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8) }
        .getOrNull()
        ?.takeIf { it.length <= MAX_SUMMARY_CHARS }
        ?.let { text -> runCatching { Json.parseToJsonElement(text).jsonObject.toString() }.getOrNull() }

internal val IDENTIFIER = Regex("^[A-Za-z0-9_-]{8,64}$")
private const val MAX_CAMPAIGN_FILE_CHARS = 256
private const val MAX_SUMMARY_CHARS = 4096
internal val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")
