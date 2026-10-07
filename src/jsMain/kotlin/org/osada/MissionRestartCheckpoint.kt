package org.osada

import org.osada.multiplayer.client.OsadaMultiplayer
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
 * (2026-09-29 owner report: *"in any campaign after Continue there is no Restart Mission"*). A
 * checkpoint is a whole save, so the number kept is capped (four campaign runs, least recently
 * captured dropped first): the campaign-run repository shares the same storage quota, and an
 * autosave that fails for lack of room is far worse than a missing restart.
 */
@Suppress("TooGenericExceptionCaught")
internal class MissionRestartCheckpoint(
    private val game: Game,
) {
    fun capture() {
        if (OsadaMultiplayer.active || !game.gameStarted || game.scenario == null) return
        // The first valid capture is immutable. Re-entering UI setup inside the same operation
        // must never turn a later autosave into the new "start" state.
        if (read()?.let(::matchesCurrentMission) == true) return
        MissionCheckpointStore.write(game.campaign?.file, GameStateSerializer.exportGameState(game))
    }

    fun isAvailable(): Boolean =
        !OsadaMultiplayer.active &&
            game.gameStarted &&
            read()?.let(::matchesCurrentMission) == true

    fun restart(onReady: () -> Unit = {}): Boolean {
        val data =
            read()?.takeIf {
                !OsadaMultiplayer.active && game.gameStarted && matchesCurrentMission(it)
            } ?: return false
        return game.state?.restoreFromString(data, onReady) == true
    }

    /**
     * The checkpoint's raw payload when it belongs to the operation a file export describes, so the
     * file can carry it ([EMBED_KEY]). A save moved to another browser -- a disk save, a campaign-run
     * backup -- otherwise arrives with no opening state, and "Restart mission" is never offered for
     * it: the restore path does not pass through `releaseToBattle`, which is the only capture site.
     */
    fun exportFor(
        campaignFile: String?,
        campaignScenario: Int?,
        scenarioFile: String?,
    ): String? =
        MissionCheckpointStore.read(campaignFile)?.takeIf {
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
        MissionCheckpointStore.write(saved.campaign?.file as? String, payload)
    }

    /** Drops the loaded operation's checkpoint: a new battle is about to start in its slot. */
    fun clear() = MissionCheckpointStore.clear(game.campaign?.file)

    private fun read(): String? = MissionCheckpointStore.read(game.campaign?.file)

    private fun matchesCurrentMission(raw: String): Boolean =
        matches(raw, game.campaign?.file, game.campaign?.currentScenarioIndex, game.scenario?.file)

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
            formatMatches && scenarioMatches && campaignMatches
        } catch (_: Throwable) {
            false
        }

    companion object {
        /** The key a file export carries the checkpoint under, beside the save's own keys. */
        const val EMBED_KEY = "missionStart"
    }
}

/** [MissionRestartCheckpoint]'s storage: one slot per campaign run plus a standalone slot, capped. */
@Suppress("TooGenericExceptionCaught")
private object MissionCheckpointStore {
    private const val STANDALONE_SLOT = "standalone"

    /** Campaign runs whose opening state is kept at once; see the class doc for why a cap. */
    private const val MAX_CAMPAIGN_SLOTS = 4

    private val keyPrefix = "osada-mission-start-${VERSION.split(".").take(2).joinToString(".")}"

    /** The pre-2026-09-29 single slot, still read so a checkpoint taken before the split is kept. */
    private val legacyKey = keyPrefix

    private val indexKey = "$keyPrefix:index"

    private fun slotKey(campaignFile: String?): String = "$keyPrefix:${campaignFile ?: STANDALONE_SLOT}"

    /** The run's checkpoint, else the legacy single slot (the caller validates either). */
    fun read(campaignFile: String?): String? =
        try {
            localStorage.getItem(slotKey(campaignFile)) ?: localStorage.getItem(legacyKey)
        } catch (error: Throwable) {
            console.warn("[osada] mission-start checkpoint could not be read", error)
            null
        }

    fun write(
        campaignFile: String?,
        payload: String,
    ) {
        val key = slotKey(campaignFile)
        try {
            val order = readIndex().filter { it != key } + key
            val campaigns = order.filter { it != slotKey(null) }
            val evicted = campaigns.take((campaigns.size - MAX_CAMPAIGN_SLOTS).coerceAtLeast(0)).toSet()
            evicted.forEach { localStorage.removeItem(it) }
            localStorage.setItem(key, payload)
            localStorage.setItem(indexKey, JSON.stringify((order - evicted).toTypedArray()))
        } catch (error: Throwable) {
            console.error("[osada] mission-start checkpoint could not be saved", error)
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
}
