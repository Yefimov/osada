package org.osada

import kotlinx.browser.localStorage
import org.osada.multiplayer.client.OsadaMultiplayer

/**
 * An immutable save of the current operation at the instant control is handed to the player.
 *
 * It is deliberately separate from the ordinary autosave: deployment, purchases and turns keep
 * updating the autosave, while this payload survives a page refresh unchanged and remains the
 * source for "Restart mission". A scenario/campaign identity check prevents a stale checkpoint
 * from being offered for another operation.
 */
@Suppress("TooGenericExceptionCaught")
internal class MissionRestartCheckpoint(
    private val game: Game,
) {
    private val storageKey =
        "osada-mission-start-${VERSION.split(".").take(2).joinToString(".")}"

    fun capture() {
        if (OsadaMultiplayer.active || !game.gameStarted || game.scenario == null) return
        // The first valid capture is immutable. Re-entering UI setup inside the same operation
        // must never turn a later autosave into the new "start" state.
        if (read()?.let(::matchesCurrentMission) == true) return
        try {
            localStorage.setItem(storageKey, GameStateSerializer.exportGameState(game))
        } catch (error: Throwable) {
            console.error("[osada] mission-start checkpoint could not be saved", error)
        }
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

    fun clear() {
        try {
            localStorage.removeItem(storageKey)
        } catch (error: Throwable) {
            console.warn("[osada] mission-start checkpoint could not be cleared", error)
        }
    }

    private fun read(): String? =
        try {
            localStorage.getItem(storageKey)
        } catch (error: Throwable) {
            console.warn("[osada] mission-start checkpoint could not be read", error)
            null
        }

    private fun matchesCurrentMission(raw: String): Boolean =
        try {
            val saved = JSON.parse<dynamic>(raw)
            val formatMatches =
                (saved.fmt as? Int ?: 0) >= GameStateSerializer.SAVE_FORMAT_VERSION
            val scenarioMatches = saved.scenario?.file as? String == game.scenario?.file
            val currentCampaign = game.campaign
            val savedCampaign = saved.campaign
            val campaignMatches =
                if (currentCampaign == null) {
                    savedCampaign == null || savedCampaign == undefined
                } else {
                    savedCampaign != null &&
                        savedCampaign != undefined &&
                        savedCampaign.file as? String == currentCampaign.file &&
                        savedCampaign.scenario as? Int == currentCampaign.currentScenarioIndex
                }
            formatMatches && scenarioMatches && campaignMatches
        } catch (_: Throwable) {
            false
        }
}
