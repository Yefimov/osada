package org.osada.cloud

import org.osada.account.AccountApi
import org.osada.account.HttpResult

/** What the server said about the account's online saves. */
data class CloudManifest(
    val dataEpoch: Int,
    val usedBytes: Double,
    val quotaBytes: Double,
    val uploadsEnabled: Boolean,
    /** The profile copy: the binding's run when listed, else the most recently saved live one. */
    val copies: List<CloudCopy>,
) {
    fun profileCopy(binding: SyncBinding?): CloudCopy? =
        copies.firstOrNull { it.runInstanceId == binding?.runInstanceId } ?: copies.firstOrNull { !it.deleted }
}

/** One upload, exactly as it will be sent. [requestId] stays the same across retries of this upload. */
class CloudUpload(
    val runInstanceId: String,
    /** Null creates the copy (`If-None-Match: *`). */
    val baseRevision: Int?,
    val dataEpoch: Int,
    val body: String,
    val contentHash: String,
    val summaryHeader: String,
    val playedAt: Double,
    val requestId: String,
    /** The uploaded document's content key, recorded with the receipt. */
    val contentKey: String,
) {
    /** Which copy and base this upload addresses; a retry must match it exactly. */
    val target: String get() = "$runInstanceId@$baseRevision"
}

/** `/api/saves`: the server stores the profile document as opaque bytes it hashed and compressed. */
object CloudSaveApi {
    fun manifest(done: (HttpResult, CloudManifest?) -> Unit) {
        AccountApi.request("GET", "/api/saves", null, emptyMap()) { result ->
            done(result, if (result.status == HTTP_OK) parseManifest(AccountApi.parseOrNull(result.text)) else null)
        }
    }

    fun download(
        runInstanceId: String,
        done: (HttpResult) -> Unit,
    ) = AccountApi.request("GET", "/api/saves/runs/$runInstanceId", null, emptyMap(), done)

    fun upload(
        upload: CloudUpload,
        csrf: String,
        done: (HttpResult) -> Unit,
    ) {
        val headers =
            buildMap {
                put(AccountApi.CSRF_HEADER, csrf)
                if (upload.baseRevision ==
                    null
                ) {
                    put("If-None-Match", "*")
                } else {
                    put("If-Match", "\"r${upload.baseRevision}\"")
                }
                put("Idempotency-Key", upload.requestId)
                put("X-Osada-Epoch", upload.dataEpoch.toString())
                put("X-Osada-Content-Hash", upload.contentHash)
                put("X-Osada-Campaign-File", CloudSyncRules.PROFILE_FILE)
                put("X-Osada-Summary", upload.summaryHeader)
                put("X-Osada-Played-At", upload.playedAt.toLong().toString())
            }
        AccountApi.request("PUT", "/api/saves/runs/${upload.runInstanceId}", upload.body, headers, done)
    }

    fun parseManifest(json: dynamic): CloudManifest? {
        if (json == null) return null
        val runs = (json.runs as? Array<*>).orEmpty().mapNotNull { parseCopy(it.asDynamic()) }
        return CloudManifest(
            dataEpoch = number(json.dataEpoch).toInt(),
            usedBytes = number(json.usedBytes),
            quotaBytes = number(json.quotaBytes),
            uploadsEnabled = json.uploadsEnabled != false,
            copies = runs.filter { it.second == CloudSyncRules.PROFILE_FILE }.map { it.first },
        )
    }

    private fun parseCopy(run: dynamic): Pair<CloudCopy, String>? {
        val id = run?.runInstanceId as? String
        val hash = run?.contentHash as? String
        if (id == null || hash == null) return null
        val summary: dynamic = run.summary
        val copy =
            CloudCopy(
                runInstanceId = id,
                revision = number(run.revision).toInt(),
                contentHash = hash,
                deleted = run.state == "deleted",
                summary =
                    CopySummary(
                        campaigns = number(summary?.campaigns).toInt(),
                        campaignName = summary?.campaignName as? String ?: "",
                        scenarioName = summary?.scenarioName as? String ?: "",
                        turn = number(summary?.turn).toInt(),
                        device = summary?.device as? String ?: "",
                    ),
                serverSavedAt = number(run.serverSavedAt),
                clientPlayedAt = (run.clientPlayedAt as? Number)?.toDouble(),
            )
        return copy to (run.campaignFile as? String ?: "")
    }

    private fun number(value: dynamic): Double = (value as? Number)?.toDouble() ?: 0.0

    private const val HTTP_OK = 200
}
