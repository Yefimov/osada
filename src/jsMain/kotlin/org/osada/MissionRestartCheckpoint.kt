package org.osada

import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.save.SaveStatus
import org.osada.storage.BrowserStorage
import org.osada.storage.localStorage

/**
 * An immutable save of the current operation at the instant control is handed to the player.
 *
 * It is deliberately separate from the ordinary autosave: deployment, purchases and turns keep
 * updating the autosave, while this payload survives a page refresh unchanged and remains the
 * source for "Restart mission". A scenario/campaign identity check prevents a stale checkpoint
 * from being offered for another operation.
 *
 * **One checkpoint per campaign run, plus one for standalone play** ([MissionCheckpointStore]). It used to be a
 * single key for the whole browser, so starting any other battle -- a standalone scenario, another
 * campaign -- overwrote it, and "Continue" on every other campaign then came back with no restart
 * (2026-09-29 owner report: *"in any campaign after Continue there is no Restart Mission"*).
 *
 * **Capture is verified and its failure is visible (2026-10-08).** A write the store refused used
 * to be caught and only logged, and capture reported nothing, so a profile near the old
 * localStorage quota (full saves of ~1 MB each) silently played whole missions with no restart --
 * the "Forward Comrade" report at Kharkiv. [capture] now reads the write back, waits for the
 * profile store to confirm it on disk, and tells [onFailure] when either fails. The payload is also
 * kept in this page's memory for the operation, so Restart (and a backup made now) still work in
 * this session even when the browser refused to keep it.
 */
@Suppress("TooGenericExceptionCaught")
internal class MissionRestartCheckpoint(
    private val game: Game,
) {
    /** What a capture achieved. [FAILED] means the browser does not hold it beyond this page. */
    enum class Outcome { SAVED, ALREADY_TAKEN, SKIPPED, FAILED }

    /** Told once per failed capture, with a short reason for the player. Set by the UI layer. */
    var onFailure: ((reason: String) -> Unit)? = null

    /** The payload of the operation in play when the browser would not keep it (see class doc). */
    private var sessionCopy: String? = null

    /**
     * Takes the opening. [replaceExisting] is for an opening that was REPLAYED
     * (`Game.openingReplay`): the battle was restored from a save written before its briefing
     * ended, so whatever checkpoint already exists came from an earlier completion whose result the
     * restored save no longer holds (its post-BEGIN autosave failed, or recovery fell back to the
     * pre-briefing generation). The player has just chosen again -- possibly differently, e.g. the
     * other Forward 15 route -- and Restart must reproduce THAT opening.
     */
    fun capture(replaceExisting: Boolean = false): Outcome =
        when {
            OsadaMultiplayer.active || !game.gameStarted || game.scenario == null -> Outcome.SKIPPED
            // Otherwise the first valid capture is immutable. Re-entering UI setup inside the same
            // operation must never turn a later autosave into the new "start" state.
            !replaceExisting && currentOpening() != null -> Outcome.ALREADY_TAKEN
            else -> keep(GameStateSerializer.exportGameState(game))
        }

    private fun keep(payload: String): Outcome {
        sessionCopy = payload
        val refusal =
            MissionCheckpointStore.write(game.campaign?.file, payload)
                ?: SaveStatus.SESSION_ONLY.takeUnless { BrowserStorage.persistent }
        if (refusal != null) {
            fail(refusal)
            return Outcome.FAILED
        }
        // Stored in the cache is not stored on disk: IndexedDB answers only when its transaction ends.
        MissionCheckpointStore.confirmDurable(game.campaign?.file, payload, ::fail)
        return Outcome.SAVED
    }

    private fun fail(reason: String) {
        console.error("[osada] mission-start checkpoint not kept by this browser: $reason")
        onFailure?.invoke(reason)
    }

    fun isAvailable(): Boolean =
        !OsadaMultiplayer.active &&
            game.gameStarted &&
            currentOpening() != null

    fun restart(onReady: () -> Unit = {}): Boolean {
        val data = currentOpening()?.takeIf { !OsadaMultiplayer.active && game.gameStarted } ?: return false
        return game.state?.restoreFromString(data, onReady) == true
    }

    /**
     * The checkpoint's raw payload when it belongs to the operation a file export describes, so the
     * file can carry it ([EMBED_KEY]). A save moved to another browser -- a disk save, a campaign-run
     * backup -- otherwise arrives with no opening state, and "Restart mission" is never offered for
     * it. The page's own [sessionCopy] counts too: a backup is exactly how a player rescues an
     * opening their browser refused to store.
     */
    fun exportFor(
        campaignFile: String?,
        campaignScenario: Int?,
        scenarioFile: String?,
    ): String? =
        listOfNotNull(MissionCheckpointStore.read(campaignFile), sessionCopy).firstOrNull {
            matches(it, campaignFile, campaignScenario, scenarioFile)
        }

    /** The checkpoint of the battle currently loaded, for a disk save of it. */
    fun exportForCurrent(): String? =
        exportFor(game.campaign?.file, game.campaign?.currentScenarioIndex, game.scenario?.file)

    /**
     * Installs a checkpoint that arrived inside an imported file. Replacing whatever is stored is
     * right: the import is about to become the operation in play, and [isAvailable] still checks
     * that the adopted payload belongs to it before offering a restart.
     */
    fun adopt(raw: Any?) {
        val payload = raw as? String ?: return
        val saved =
            try {
                JSON.parse<dynamic>(payload)
            } catch (_: Throwable) {
                null
            }
        if (saved == null || (saved.fmt as? Int ?: 0) < GameStateSerializer.SAVE_FORMAT_VERSION) return
        sessionCopy = payload
        val campaignFile = saved.campaign?.file as? String
        val refusal = MissionCheckpointStore.write(campaignFile, payload)
        if (refusal != null) fail(refusal) else MissionCheckpointStore.confirmDurable(campaignFile, payload, ::fail)
    }

    /** Drops the loaded operation's checkpoint: a new battle is about to start in its slot. */
    fun clear() {
        sessionCopy = null
        MissionCheckpointStore.clear(game.campaign?.file)
    }

    /** The stored opening of the operation in play, else this page's copy of it. */
    private fun currentOpening(): String? = exportForCurrent()

    private fun matches(
        raw: String,
        campaignFile: String?,
        campaignScenario: Int?,
        scenarioFile: String?,
    ): Boolean =
        try {
            val saved = JSON.parse<dynamic>(raw)
            val formatMatches =
                (saved.fmt as? Int ?: 0) >= GameStateSerializer.SAVE_FORMAT_VERSION
            val scenarioMatches = saved.scenario?.file as? String == scenarioFile
            val savedCampaign = saved.campaign
            val campaignMatches =
                if (campaignFile == null) {
                    savedCampaign == null || savedCampaign == undefined
                } else {
                    savedCampaign != null &&
                        savedCampaign != undefined &&
                        savedCampaign.file as? String == campaignFile &&
                        savedCampaign.scenario as? Int == campaignScenario
                }
            // An opening is never a pending one: a payload that still says so was taken before its
            // briefing finished and would replay the briefing on top of its own committed choices.
            val released = saved[OPENING_PENDING_KEY] != true
            formatMatches && scenarioMatches && campaignMatches && released
        } catch (_: Throwable) {
            false
        }

    companion object {
        /** The key a file export carries the checkpoint under, beside the save's own keys. */
        const val EMBED_KEY = "missionStart"

        /** Save key marking a battle whose opening briefing has not handed over control yet. */
        const val OPENING_PENDING_KEY = "openingPending"
    }
}

/**
 * [MissionRestartCheckpoint]'s storage: one slot per campaign run plus a standalone slot.
 *
 * **Retention follows the campaign runs (2026-10-08).** This used to keep at most four campaign
 * checkpoints, least recently captured dropped first, while the run repository keeps every run: a
 * fifth campaign silently took Restart away from whichever run had started its mission longest ago,
 * and "Continue" on that run came back without it. The cap existed to protect localStorage's ~5 MB
 * quota; the profile now lives in IndexedDB (`storage/BrowserStorage`). A slot is now dropped only
 * when no campaign run with its file exists any more, and a write refused for space first frees
 * other runs' recovery generations -- the same relief the autosave uses -- then reports failure.
 */
@Suppress("TooGenericExceptionCaught")
internal object MissionCheckpointStore {
    private const val STANDALONE_SLOT = "standalone"

    private val keyPrefix = "osada-mission-start-${VERSION.split(".").take(2).joinToString(".")}"

    /** The pre-2026-09-29 single slot, still read so a checkpoint taken before the split is kept. */
    private val legacyKey = keyPrefix

    private val indexKey = "$keyPrefix:index"

    internal fun slotKey(campaignFile: String?): String = "$keyPrefix:${campaignFile ?: STANDALONE_SLOT}"

    /** The run's checkpoint, else the legacy single slot (the caller validates either). */
    fun read(campaignFile: String?): String? =
        try {
            localStorage.getItem(slotKey(campaignFile)) ?: localStorage.getItem(legacyKey)
        } catch (error: Throwable) {
            console.warn("[osada] mission-start checkpoint could not be read", error)
            null
        }

    /** Writes and reads back [payload]; null when it is stored, else why it is not. */
    fun write(
        campaignFile: String?,
        payload: String,
    ): String? {
        val key = slotKey(campaignFile)
        var failure = attempt(key, payload)
        if (failure == QUOTA) {
            val runs = LocalStorageSaveSnapshotStore()
            runs.pruneOrphans()
            while (failure == QUOTA && runs.evictOldestRecovery(campaignFile)) {
                failure = attempt(key, payload)
            }
        }
        return failure
    }

    /**
     * Waits until [payload] is on disk and calls [onFailed] if it never gets there.
     *
     * [write]'s quota relief only sees a SYNCHRONOUS refusal, which is localStorage's. On the
     * IndexedDB backend the write lands in the cache at once, and the quota refusal arrives later, as
     * a failed transaction. That transaction is all-or-nothing and the store retries the refused put
     * together with anything newer, so a recovery generation deleted beside it would never be freed.
     * Each relief round therefore withdraws the refused checkpoint first: one other run's recovery
     * generation is deleted in a transaction of its own, and only after that delete is durable is the
     * checkpoint written again. The number of rounds is bounded, and each round removes a generation,
     * so a disk that stays full ends in the visible failure rather than a loop.
     */
    fun confirmDurable(
        campaignFile: String?,
        payload: String,
        onFailed: (String) -> Unit,
        onDurable: () -> Unit = {},
        roundsLeft: Int = MAX_RELIEF_ROUNDS,
    ) {
        BrowserStorage.whenDurable { durable ->
            val error = BrowserStorage.lastDurableErrorName ?: "storage error"
            when {
                durable -> onDurable()
                error != QUOTA || roundsLeft <= 0 -> onFailed(error)
                else -> relieve(campaignFile, payload, onFailed, onDurable, roundsLeft)
            }
        }
    }

    private fun relieve(
        campaignFile: String?,
        payload: String,
        onFailed: (String) -> Unit,
        onDurable: () -> Unit,
        roundsLeft: Int,
    ) {
        try {
            if (!LocalStorageSaveSnapshotStore().evictOldestRecovery(campaignFile)) return onFailed(QUOTA)
            // Supersedes the queued put: the eviction now commits without the payload that overflowed.
            localStorage.removeItem(slotKey(campaignFile))
        } catch (error: Throwable) {
            return onFailed(error.message ?: "storage error")
        }
        BrowserStorage.whenDurable { freed ->
            val error = BrowserStorage.lastDurableErrorName ?: QUOTA
            // The eviction shares its transaction with any other refused write still queued (an
            // autosave of the same moment), so it can be refused too: free one more and try again.
            val retry = if (freed) write(campaignFile, payload) else error
            when {
                retry == null -> confirmDurable(campaignFile, payload, onFailed, onDurable, roundsLeft - 1)
                !freed && error == QUOTA && roundsLeft > 1 ->
                    relieve(campaignFile, payload, onFailed, onDurable, roundsLeft - 1)
                else -> onFailed(retry)
            }
        }
    }

    private fun attempt(
        key: String,
        payload: String,
    ): String? =
        try {
            val retained = retainedSlots(key)
            readIndex().filter { it != key && it !in retained }.forEach { localStorage.removeItem(it) }
            localStorage.setItem(key, payload)
            localStorage.setItem(indexKey, JSON.stringify((retained + key).toTypedArray()))
            if (localStorage.getItem(key) == payload) null else "readback mismatch"
        } catch (error: Throwable) {
            console.error("[osada] mission-start checkpoint could not be saved", error)
            if (error.asDynamic()?.name == "QuotaExceededError") QUOTA else error.message ?: "storage error"
        }

    /** Indexed slots other than [key] that still belong to something: the standalone slot, or a run. */
    private fun retainedSlots(key: String): List<String> {
        val runFiles =
            LocalStorageSaveSnapshotStore()
                .listCampaignRuns()
                .map { it.campaignFile }
                .toSet()
        return readIndex().filter { slot ->
            slot != key && (slot == slotKey(null) || runFiles.any { slotKey(it) == slot })
        }
    }

    /** Drops a run's checkpoint (and the legacy slot): a new battle is about to start in it. */
    fun clear(campaignFile: String?) {
        try {
            localStorage.removeItem(slotKey(campaignFile))
            localStorage.removeItem(legacyKey)
        } catch (error: Throwable) {
            console.warn("[osada] mission-start checkpoint could not be cleared", error)
        }
    }

    private fun readIndex(): List<String> =
        try {
            localStorage.getItem(indexKey)?.let { JSON.parse<Array<String>>(it).toList() }.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

    private const val QUOTA = "QuotaExceededError"

    /** Recovery generations [confirmDurable] may free for one checkpoint before giving up. */
    private const val MAX_RELIEF_ROUNDS = 8
}
