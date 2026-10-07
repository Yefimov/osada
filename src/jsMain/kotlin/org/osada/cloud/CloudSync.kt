package org.osada.cloud

import kotlinx.browser.window
import org.osada.Game
import org.osada.account.HttpResult
import org.osada.account.OnlineAccount
import org.osada.current
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.storage.BrowserStorage
import org.osada.ui.ProfileBackup

/** The quiet sync status of §3.2, plus the states that need the player. */
enum class SyncStatus {
    OFF,
    CHECKING,
    SYNCED,
    PENDING,
    OFFLINE,
    NEEDS_CHOICE,
    OFFER_UPLOAD,
    OFFER_DOWNLOAD,
    NEWER_ONLINE,
    CLOUD_GONE,
    STORAGE_FULL,
    LOCAL_FAILURE,
}

/**
 * Keeps the whole-profile online copy in step with this device (online-saves §3–§4, Stage D).
 *
 * Local saves stay authoritative for play: sync runs after them, never before, and a failure only
 * ever changes the status line. Automatic actions are limited to the two cases that cannot lose
 * anything -- uploading this device's own descendant of the acknowledged copy, and (at the start
 * menu only) taking a cloud descendant when this device has no unsent saves. Every other case waits
 * for the player in the Online saves dialog.
 */
@Suppress("TooManyFunctions") // One state machine: triggers, decision, upload, download, choice.
object CloudSync {
    var status: SyncStatus = SyncStatus.OFF
        private set
    var decision: SyncDecision? = null
        private set
    var manifest: CloudManifest? = null
        private set
    var lastLocal: CapturedProfile? = null
        private set
    var lastSyncedAt: Double = 0.0
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private var running = false
    private var rerun = false
    private var debounce: Int? = null
    private var retryDelay = RETRY_MIN_MILLIS

    /** Reused while the same upload is retried, so a lost reply cannot create a second revision. */
    private var pendingUpload: CloudUpload? = null
    private var knownAccountId: String? = null
    private var playerActed = false

    /** A player's choice made while an automatic pass was in flight; runs as soon as it ends. */
    private var queuedChoice: (() -> Unit)? = null

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    /** Wires the triggers once at boot; nothing runs until an account is signed in. */
    fun install() {
        BrowserStorage.onProfileWrite = { schedule(SAVE_DEBOUNCE_MILLIS) }
        // The boot window ends at the player's first input: until then the battle on screen is the
        // one just restored from this device's own saves, and a cloud descendant may replace it.
        listOf("pointerdown", "keydown").forEach { type ->
            window.addEventListener(type, { playerActed = true }, js("({ capture: true, once: true })"))
        }
        // Only a change of who is signed in starts a pass; a refreshed CSRF token alone does not.
        OnlineAccount.onChange {
            val accountId = OnlineAccount.profile?.accountId
            if (accountId != knownAccountId) {
                knownAccountId = accountId
                pendingUpload = null
                schedule(0)
            }
        }
        window.addEventListener("online", { schedule(0) })
        window.document.addEventListener("visibilitychange", {
            // Best effort only: a phone may never deliver this; saves were already uploaded after commit.
            if (window.document.asDynamic().visibilityState == "hidden") schedule(0)
        })
    }

    fun schedule(delayMillis: Int) {
        debounce?.let(window::clearTimeout)
        debounce = window.setTimeout({ reconcile(automatic = true) }, delayMillis)
    }

    /** Re-reads both sides and acts. [automatic] false comes from the dialog: show, don't surprise. */
    fun reconcile(
        automatic: Boolean = false,
        done: () -> Unit = {},
    ) {
        val profile = OnlineAccount.profile
        if (profile == null || !OnlineAccount.config.enabled || !LocalProfileCopy.hashingAvailable()) {
            publish(SyncStatus.OFF, null)
            done()
            return
        }
        if (running) {
            rerun = true
            done()
            return
        }
        running = true
        val finish = {
            running = false
            done()
            queuedChoice?.let {
                queuedChoice = null
                it()
            }
            if (rerun) {
                rerun = false
                schedule(SAVE_DEBOUNCE_MILLIS)
            }
        }
        if (decision == null) publish(SyncStatus.CHECKING, null)
        CloudSaveApi.manifest { result, cloud ->
            if (cloud == null) {
                publish(statusForFailure(result), decision)
                if (result.status == 0 ||
                    result.status >= HTTP_SERVER_ERROR ||
                    result.status == HTTP_TOO_MANY
                ) {
                    retryLater()
                }
                finish()
                return@manifest
            }
            manifest = cloud
            LocalProfileCopy.capture { local ->
                if (local == null) {
                    publish(SyncStatus.LOCAL_FAILURE, null)
                    finish()
                    return@capture
                }
                lastLocal = local
                val binding = LocalProfileCopy.readBinding()
                val next =
                    CloudSyncRules.decide(
                        profile.accountId,
                        cloud.dataEpoch,
                        binding,
                        local.local,
                        cloud.profileCopy(binding),
                    )
                act(next, local, cloud, automatic, finish)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One arm per decision; uploads and downloads finish later.
    private fun act(
        next: SyncDecision,
        local: CapturedProfile,
        cloud: CloudManifest,
        automatic: Boolean,
        finish: () -> Unit,
    ) {
        val accountId = OnlineAccount.profile?.accountId ?: return finish()
        when (next) {
            SyncDecision.InSync -> synced(next)
            is SyncDecision.Adopt -> {
                bind(
                    accountId,
                    cloud.dataEpoch,
                    next.copy.runInstanceId,
                    next.copy.revision,
                    next.copy.contentHash,
                    local.contentKey,
                )
                synced(next)
            }
            is SyncDecision.Upload ->
                return upload(local, cloud, next.runInstanceId, next.baseRevision, finish)
            is SyncDecision.Download ->
                if (automatic && safeToReplaceNow()) {
                    return download(next.copy, confirm = false) { finish() }
                } else {
                    publish(SyncStatus.NEWER_ONLINE, next)
                }
            SyncDecision.OfferFirstUpload -> publish(SyncStatus.OFFER_UPLOAD, next)
            is SyncDecision.OfferDownload -> publish(SyncStatus.OFFER_DOWNLOAD, next)
            is SyncDecision.Choose -> publish(SyncStatus.NEEDS_CHOICE, next)
            is SyncDecision.CloudGone -> publish(SyncStatus.CLOUD_GONE, next)
            SyncDecision.Empty -> synced(next)
        }
        finish()
    }

    /**
     * The player's "Copy this device's saves online" / "Use this device" / upload-again choice.
     * [observed] is the online copy the player was shown when choosing: replacing names exactly that
     * revision, so if another device saved since, the server answers 412 and the refreshed comparison
     * is shown instead of overwriting that device's work.
     */
    fun uploadThisDevice(
        observed: CloudCopy? = (decision as? SyncDecision.Choose)?.copy,
        done: () -> Unit = {},
    ) {
        if (running) {
            queuedChoice = { uploadThisDevice(observed, done) }
            return
        }
        val local = lastLocal
        val cloud = manifest
        if (local == null || cloud == null) return done()
        running = true
        upload(local, cloud, observed?.runInstanceId ?: newRunId(), observed?.revision) {
            running = false
            done()
        }
    }

    /** The player's "Use online" / "Download to this device": preview, confirm, import, reload. */
    fun downloadOnline(done: () -> Unit = {}) {
        val copy =
            when (val current = decision) {
                is SyncDecision.Choose -> current.copy
                is SyncDecision.OfferDownload -> current.copy
                is SyncDecision.Download -> current.copy
                else -> null
            } ?: return done()
        if (running) {
            queuedChoice = { download(copy, confirm = true, done) }
            return
        }
        download(copy, confirm = true, done)
    }

    /** "Decide later": keep both versions, stop automatic sync for this copy until a choice. */
    fun decideLater() {
        val choose = decision as? SyncDecision.Choose ?: return
        val profile = OnlineAccount.profile ?: return
        val binding = LocalProfileCopy.readBinding()?.takeIf { it.accountId == profile.accountId }
        LocalProfileCopy.writeBinding(
            (
                binding ?: SyncBinding(
                    profile.accountId,
                    manifest?.dataEpoch ?: 1,
                    choose.copy.runInstanceId,
                    0,
                    "",
                )
            ).copy(paused = true),
        )
        publish(SyncStatus.NEEDS_CHOICE, choose.copy(paused = true))
    }

    /** The online copy's exact bytes, for "Download both versions". */
    fun fetchOnlineDocument(done: (String?) -> Unit) {
        val copy = (decision as? SyncDecision.Choose)?.copy ?: return done(null)
        CloudSaveApi.download(copy.runInstanceId) { result -> done(result.text.takeIf { result.status == HTTP_OK }) }
    }

    private fun upload(
        local: CapturedProfile,
        cloud: CloudManifest,
        runId: String,
        baseRevision: Int?,
        finish: () -> Unit,
    ) {
        val profile = OnlineAccount.profile ?: return finish()
        if (!cloud.uploadsEnabled) {
            publish(SyncStatus.PENDING, decision)
            return finish()
        }
        val previous = pendingUpload
        val upload =
            if (previous != null && previous.target == "$runId@$baseRevision" && previous.contentHash == local.hash) {
                previous
            } else {
                CloudUpload(
                    runInstanceId = runId,
                    baseRevision = baseRevision,
                    dataEpoch = cloud.dataEpoch,
                    body = local.body,
                    contentHash = local.hash,
                    summaryHeader = LocalProfileCopy.summaryHeader(local.summary),
                    playedAt = local.playedAt,
                    requestId = newRequestId(),
                    contentKey = local.contentKey,
                )
            }
        pendingUpload = upload
        publish(SyncStatus.PENDING, decision)
        CloudSaveApi.upload(upload, profile.csrf) { result -> uploaded(upload, result, finish) }
    }

    private fun uploaded(
        upload: CloudUpload,
        result: HttpResult,
        finish: () -> Unit,
    ) {
        val accountId = OnlineAccount.profile?.accountId
        val run =
            org.osada.account.AccountApi
                .parseOrNull(result.text)
                ?.run
        when {
            result.status == HTTP_OK && accountId != null && run != null -> {
                pendingUpload = null
                retryDelay = RETRY_MIN_MILLIS
                // The receipt covers exactly the uploaded bytes; saves made since stay dirty.
                bind(
                    accountId,
                    upload.dataEpoch,
                    upload.runInstanceId,
                    (run.revision as Number).toInt(),
                    upload.contentHash,
                    upload.contentKey,
                )
                lastSyncedAt = kotlin.js.Date.now()
                finish()
                schedule(0)
                return
            }
            // Someone else moved the copy, or it expired: re-read and let the rules decide.
            result.status == HTTP_PRECONDITION_FAILED || result.status == HTTP_GONE -> {
                pendingUpload = null
                finish()
                schedule(0)
                return
            }
            result.status == HTTP_INSUFFICIENT_STORAGE || result.status == HTTP_TOO_LARGE ->
                publish(SyncStatus.STORAGE_FULL, decision)
            // Usually a rotated session's new CSRF token: re-read the account, then try again later.
            result.status == HTTP_FORBIDDEN -> {
                publish(SyncStatus.PENDING, decision)
                OnlineAccount.refresh()
                retryLater()
            }
            else -> {
                publish(statusForFailure(result), decision)
                retryLater()
            }
        }
        finish()
    }

    private fun download(
        copy: CloudCopy,
        confirm: Boolean,
        done: () -> Unit,
    ) {
        CloudSaveApi.download(copy.runInstanceId) { result ->
            val revision = result.header("X-Osada-Revision")?.toIntOrNull()
            val hash = result.header("X-Osada-Content-Hash")
            if (result.status != HTTP_OK || revision == null || hash == null) {
                publish(statusForFailure(result), decision)
                return@download done()
            }
            LocalProfileCopy.sha256(result.text).then { actual ->
                if (actual != hash) {
                    // Corrupt in transit or at rest: this device's saves stay exactly as they are.
                    publish(SyncStatus.LOCAL_FAILURE, decision)
                    done()
                    return@then
                }
                LocalProfileCopy.contentKey(result.text).then { key ->
                    apply(Downloaded(result.text, copy.runInstanceId, revision, hash, key), confirm, done)
                }
            }
        }
    }

    /** A verified online copy, with what this device records once it holds it. */
    private class Downloaded(
        val text: String,
        val runInstanceId: String,
        val revision: Int,
        val hash: String,
        val contentKey: String,
    )

    private fun apply(
        downloaded: Downloaded,
        confirm: Boolean,
        done: () -> Unit,
    ) {
        val accountId = OnlineAccount.profile?.accountId ?: return done()
        val epoch = manifest?.dataEpoch ?: return done()
        ProfileBackup.applyDocument(
            JSON.parse(downloaded.text),
            confirm = confirm,
            // Recorded before the reload, so the next boot sees this device in step.
            beforeReload = {
                with(downloaded) { bind(accountId, epoch, runInstanceId, revision, hash, contentKey) }
            },
            onError = { key ->
                console.warn("[osada] online copy not applied:", key)
                // "Not now" (animation, computer turn) is not a failure: offer it again later.
                val busy = key == "save_load.backup.busy"
                publish(if (busy) SyncStatus.NEWER_ONLINE else SyncStatus.LOCAL_FAILURE, decision)
                done()
            },
        )
    }

    /**
     * Alone, with durable storage, and either at the start menu or before the player's first input
     * since the page loaded (the battle on screen is then exactly this device's last save, which the
     * rules already found identical to the acknowledged copy). Otherwise a reload would interrupt play.
     */
    private fun safeToReplaceNow(): Boolean {
        val game = Game.current
        return !OsadaMultiplayer.active && BrowserStorage.persistent && (game?.gameStarted != true || !playerActed)
    }

    private fun bind(
        accountId: String,
        dataEpoch: Int,
        runId: String,
        revision: Int,
        hash: String,
        contentKey: String,
    ) {
        LocalProfileCopy.writeBinding(
            SyncBinding(accountId, dataEpoch, runId, revision, hash, contentKey, paused = false),
        )
    }

    private fun synced(next: SyncDecision) {
        if (next != SyncDecision.Empty) lastSyncedAt = kotlin.js.Date.now()
        publish(SyncStatus.SYNCED, next)
    }

    private fun publish(
        next: SyncStatus,
        nextDecision: SyncDecision?,
    ) {
        status = next
        decision = nextDecision
        listeners.toList().forEach { it() }
    }

    private fun statusForFailure(result: HttpResult): SyncStatus =
        when {
            result.status == 0 || result.status >= HTTP_SERVER_ERROR -> SyncStatus.OFFLINE
            result.status == HTTP_UNAUTHORIZED || result.status == HTTP_FORBIDDEN -> SyncStatus.OFF
            else -> SyncStatus.PENDING
        }

    private fun retryLater() {
        schedule(retryDelay + (kotlin.random.Random.nextDouble() * retryDelay / 2).toInt())
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MILLIS)
    }

    private fun newRunId(): String = "p_" + randomToken()

    private fun newRequestId(): String = "u_" + randomToken()

    private fun randomToken(): String {
        val bytes = js("new Uint8Array(18)")
        window.asDynamic().crypto.getRandomValues(bytes)
        var text = ""
        for (i in 0 until TOKEN_LENGTH) text += ALPHABET[(bytes[i] as Int) % ALPHABET.length]
        return text
    }

    private const val TOKEN_LENGTH = 18
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val SAVE_DEBOUNCE_MILLIS = 4000
    private const val RETRY_MIN_MILLIS = 15_000
    private const val RETRY_MAX_MILLIS = 600_000
    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_PRECONDITION_FAILED = 412
    private const val HTTP_GONE = 410
    private const val HTTP_TOO_LARGE = 413
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_INSUFFICIENT_STORAGE = 507
}
