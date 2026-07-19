package org.osada

import kotlinx.browser.localStorage
import org.osada.model.getPlayers

/**
 * All save-state I/O: browser localStorage and file import/export.
 *
 * OSADA: cloud save/load was removed — it used a hardcoded GitHub gist token belonging to the
 * original Panzer Marshal author's account, not something a fork should keep using without its
 * own server/credentials. Reintroduce only against a project-owned backend/token if ever needed.
 *
 * Extracted from the former `GameState` god-class. This collaborator owns the storage
 * keys and the asynchronous restore orchestration; it produces save payloads via
 * [GameStateSerializer] and rebuilds the game via the injected [GameStateRestore]. It
 * holds no serialization or graph-building logic of its own.
 */
class GameStatePersistence(
    private val game: Game,
    private val restorer: GameStateRestore,
) {
    private val majorVersion: String = VERSION.split(".").take(2).joinToString(".")
    private val scenarioKey = "osada-scenario-$majorVersion"
    private val playersKey = "osada-players-$majorVersion"
    private val settingsKey = "osada-settings-$majorVersion"
    private val campaignKey = "osada-campaign-$majorVersion"

    private val loadingState = mutableMapOf<String, dynamic>()

    fun save() {
        val scenario = game.scenario ?: return
        val scenarioData = GameStateSerializer.serializeScenario(scenario)
        val playersData =
            scenario.map
                .getPlayers()
                .map { GameStateSerializer.serializePlayer(it) }
                .toTypedArray()

        localStorageSet(scenarioKey, scenarioData)
        localStorageSet(playersKey, playersData)
        saveCampaign()
        saveSettings()
    }

    fun saveCampaign() {
        localStorageSet(campaignKey, GameStateSerializer.buildCampaignData(game))
    }

    fun saveSettings() {
        localStorageSet(settingsKey, uiSettings)
    }

    fun restore(
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) {
        console.log("[osada] GameState.restore start")
        var pending = 4
        val check: () -> Unit = {
            pending--
            console.log("[osada] restore check pending:", pending)
            if (pending <= 0) finishRestore(onSuccess, onFail)
        }
        console.log(
            "[osada] restore requesting localStorage keys:",
            settingsKey,
            scenarioKey,
            playersKey,
            campaignKey,
        )
        localStorageGet(settingsKey) {
            loadingState[settingsKey] = it
            check()
        }
        localStorageGet(scenarioKey) {
            loadingState[scenarioKey] = it
            check()
        }
        localStorageGet(playersKey) {
            loadingState[playersKey] = it
            check()
        }
        localStorageGet(campaignKey) {
            loadingState[campaignKey] = it
            check()
        }
    }

    // Extracted from restore() to keep it under the LongMethod line limit. Reads the four
    // localStorage payloads once all pending reads land, then either restores or bails to menu.
    private fun finishRestore(
        onSuccess: () -> Unit,
        onFail: () -> Unit,
    ) {
        val scenarioData = loadingState[scenarioKey]
        val playersData = loadingState[playersKey]
        val campaignData = loadingState[campaignKey]
        val settingsData = loadingState[settingsKey]
        console.log(
            "[osada] restore all localStorage reads done; scenarioData!=null:",
            scenarioData != null,
            "playersData!=null:",
            playersData != null,
            "campaignData!=null:",
            campaignData != null,
            "settingsData!=null:",
            settingsData != null,
        )
        if (settingsData != null) restorer.applySettings(settingsData)
        if (scenarioData != null && playersData != null && scenarioData.file != null) {
            if (!hasAnyUnits(scenarioData, playersData)) {
                // Stale/empty saved state (e.g. an old Operation Uranus left in localStorage):
                // no units on the map, in reserve, or in any player's core. Auto-restoring it
                // dumps the player onto a blank board. Drop it and show the menu instead.
                console.log(
                    "[osada] restore: saved scenario has NO units (stale/empty) -> clearing and showing menu",
                )
                clear()
                onFail()
            } else {
                console.log("[osada] restore calling restoreGame for", scenarioData.file)
                restorer.restoreGame(scenarioData, playersData, campaignData, onSuccess)
            }
        } else {
            console.log("[osada] restore missing data, calling onFail")
            onFail()
        }
    }

    // Save data is an external, untrusted JSON blob -- any parse/shape error must fail this
    // call gracefully (return false) rather than crash, so a broad catch is intentional here.
    @Suppress("TooGenericExceptionCaught")
    fun restoreFromString(
        data: String,
        onReady: () -> Unit = {},
    ): Boolean =
        try {
            val parsed = JSON.parse<dynamic>(data)
            if (isLoadableSave(parsed)) {
                game.cleanup()
                restorer.restoreGame(parsed.scenario, parsed.players, parsed.campaign) {
                    game.setupGameState()
                    onReady()
                }
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            console.error("restoreFromString failed: " + e.message, e)
            false
        }

    fun clear() {
        localStorageRemove(scenarioKey)
        localStorageRemove(playersKey)
        localStorageRemove(campaignKey)
    }

    fun restoreSettings() {
        localStorageGet(settingsKey) { restorer.applySettings(it) }
    }

    private fun localStorageSet(
        key: String,
        value: dynamic,
    ) {
        console.log("[osada] localStorageSet", key)
        localStorage.setItem(key, JSON.stringify(value))
    }

    private fun localStorageGet(
        key: String,
        callback: (dynamic) -> Unit,
    ) {
        console.log("[osada] localStorageGet", key)
        val item = localStorage.getItem(key)
        val parsed = if (item != null) JSON.parse(item) else null
        console.log(
            "[osada] localStorageGet",
            key,
            "item!=null:",
            item != null,
            "parsed type:",
            js("typeof parsed"),
        )
        callback(parsed)
    }

    private fun localStorageRemove(key: String) {
        console.log("[osada] localStorageRemove", key)
        localStorage.removeItem(key)
    }
}

// Top-level (not a class member) so it doesn't count against GameStatePersistence's
// TooManyFunctions budget -- only restoreFromString (same file) calls it.
private fun isLoadableSave(parsed: dynamic): Boolean {
    if (parsed.scenario == undefined) return false
    val fmt = parsed.fmt as? Int ?: 0
    return when {
        fmt < GameStateSerializer.SAVE_FORMAT_VERSION -> {
            // Pre-eqp-united save: its eqids/country codes are from the old per-efile
            // numbering and no longer resolve against the merged equipment DB. Reject
            // rather than load and silently show wrong/missing units.
            console.error(
                "[osada] refusing to load save with fmt=$fmt " +
                    "(need >= ${GameStateSerializer.SAVE_FORMAT_VERSION}): saved before the " +
                    "equipment merge, its unit ids are no longer valid",
            )
            false
        }
        else -> true
    }
}
