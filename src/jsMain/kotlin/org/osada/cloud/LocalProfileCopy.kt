package org.osada.cloud

import kotlinx.browser.window
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.storage.localStorage
import org.osada.ui.ProfileBackup
import kotlin.js.Promise

/** This device's committed profile as it would be uploaded: bytes, hash and the card facts. */
class CapturedProfile(
    val body: String,
    val hash: String,
    val contentKey: String,
    val empty: Boolean,
    val summary: CopySummary,
    val playedAt: Double,
) {
    val local: LocalCopy get() = LocalCopy(hash, empty, contentKey)
}

/**
 * Builds the upload document from what is already saved in this browser (never the live battle) and
 * hashes it the way the server does: `sha256-` + base64url of SHA-256 over the UTF-8 bytes.
 */
internal object LocalProfileCopy {
    @Suppress("TooGenericExceptionCaught") // Any unreadable store means "do not sync", never a crash.
    fun capture(done: (CapturedProfile?) -> Unit) {
        val document =
            try {
                ProfileBackup.prepareExport(includeLiveBattle = false)
            } catch (error: Throwable) {
                console.warn("[osada] cloud sync could not read this device's saves", error)
                done(null)
                return
            }
        val body = JSON.stringify(document)
        val runs = LocalStorageSaveSnapshotStore().listCampaignRuns()
        val latest = runs.maxByOrNull { it.lastPlayedAt }
        // A standalone-only profile is described by its saved battle instead.
        val battle: dynamic = if (latest == null) standaloneScenario(document.standalone) else null
        val summary =
            CopySummary(
                campaigns = runs.size,
                campaignName = latest?.campaignName.orEmpty(),
                scenarioName = latest?.scenarioName ?: (battle?.name as? String).orEmpty(),
                turn = latest?.turn ?: (battle?.turn as? Int ?: 0),
                device = deviceLabel(),
            )
        val empty = runs.isEmpty() && document.standalone == null
        sha256(body)
            .then { hash ->
                contentKey(body).then { key ->
                    done(CapturedProfile(body, hash, key, empty, summary, latest?.lastPlayedAt ?: 0.0))
                }
            }.catch { error ->
                console.warn("[osada] cloud sync could not hash this device's saves", error)
                done(null)
            }
    }

    private fun standaloneScenario(standalone: dynamic): dynamic {
        val payload = standalone?.payload as? String
        val saved = payload?.let { runCatching { JSON.parse<dynamic>(it) }.getOrNull() } ?: return null
        val result: dynamic = js("({})")
        result.name = saved.scenario?.name
        result.turn = saved.scenario?.turn
        return result
    }

    /** See [LocalCopy.contentKey]: the document hashed with its pure timestamps zeroed. */
    fun contentKey(body: String): Promise<String> {
        val document = JSON.parse<dynamic>(body)
        document.exportedAt = 0
        if (document.standalone != null) document.standalone.savedAt = 0
        return sha256(JSON.stringify(document))
    }

    /** SubtleCrypto exists only in secure contexts (https or localhost); sync is off elsewhere. */
    fun hashingAvailable(): Boolean = window.asDynamic().crypto?.subtle != null

    fun sha256(text: String): Promise<String> {
        val bytes = js("new TextEncoder()").encode(text)
        val digest: Promise<dynamic> =
            window
                .asDynamic()
                .crypto.subtle
                .digest("SHA-256", bytes)
        return digest.then<String> { buffer -> "sha256-" + base64Url(buffer) }
    }

    @Suppress("UnusedParameter") // Read by name inside js().
    private fun base64Url(buffer: dynamic): String {
        val view = js("new Uint8Array(buffer)")
        var binary = ""
        val length = view.length as Int
        for (i in 0 until length) binary += js("String.fromCharCode")(view[i]) as String
        return (window.btoa(binary))
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    /**
     * The "source device" label on the conflict cards: a coarse kind, never a fingerprint. A
     * player-chosen name can come later; this is enough to tell the PC from the phone.
     */
    private fun deviceLabel(): String {
        val coarse = window.matchMedia("(pointer: coarse)").matches
        val narrow = window.matchMedia("(max-width: 900px)").matches
        return when {
            coarse && narrow -> "phone"
            coarse -> "tablet"
            else -> "computer"
        }
    }

    /** Header form of [summary]: base64url JSON, small and safe for any header value. */
    fun summaryHeader(summary: CopySummary): String {
        val json: dynamic = js("({})")
        json.campaigns = summary.campaigns
        json.campaignName = summary.campaignName
        json.scenarioName = summary.scenarioName
        json.turn = summary.turn
        json.device = summary.device
        val text = JSON.stringify(json)
        val bytes = js("new TextEncoder()").encode(text)
        return base64Url(bytes.buffer)
    }

    /** Device-local and outside the profile: never in a backup, never uploaded. */
    internal const val BINDING_KEY = "osada-cloud-sync-v1"

    fun readBinding(): SyncBinding? =
        runCatching {
            val raw = localStorage.getItem(BINDING_KEY) ?: return null
            val json = JSON.parse<dynamic>(raw)
            SyncBinding(
                accountId = json.accountId as String,
                dataEpoch = (json.dataEpoch as Number).toInt(),
                runInstanceId = json.runInstanceId as String,
                ackRevision = (json.ackRevision as Number).toInt(),
                ackHash = json.ackHash as String,
                ackContentKey = json.ackContentKey as? String ?: "",
                paused = json.paused == true,
            )
        }.getOrNull()

    fun writeBinding(binding: SyncBinding?) {
        runCatching {
            if (binding == null) {
                localStorage.removeItem(BINDING_KEY)
            } else {
                val json: dynamic = js("({})")
                json.accountId = binding.accountId
                json.dataEpoch = binding.dataEpoch
                json.runInstanceId = binding.runInstanceId
                json.ackRevision = binding.ackRevision
                json.ackHash = binding.ackHash
                json.ackContentKey = binding.ackContentKey
                json.paused = binding.paused
                localStorage.setItem(BINDING_KEY, JSON.stringify(json))
            }
        }.onFailure { console.warn("[osada] cloud sync link could not be stored", it) }
    }
}
