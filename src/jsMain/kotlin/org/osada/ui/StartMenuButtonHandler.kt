package org.osada.ui

import org.osada.Game
import org.osada.i18n.I18n
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.uiSettings
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event

/**
 * Start-menu button actions (New Campaign / New Scenario / Tutorial / Save-Load / Settings).
 * Split from the former `MenuController` god-class to stay within the project's
 * function-count/class-size limits.
 */
internal class StartMenuButtonHandler(
    private val ui: UI,
) {
    fun startMenuButton(id: String) {
        when (id) {
            "newgame" -> makeVisible("smNewGame")
            "newcampaign" -> onNewCampaignButton()
            "newscenario" -> onNewScenarioButton()
            "multiplayer" -> OsadaMultiplayer.openHub(ui.game)
            "tutorial" -> onTutorialButton()
            "continuegame" -> {
                makeHidden("startmenu")
                // The other way out of the pause menu; see MainMenuButtonHandler.onOptionsButton.
                MessageDialogs.resumeDynamicMessages()
                byId("options")?.let { toggleButton(it, false) }
            }

            "restartmission" -> onRestartMissionButton()

            "saveload" -> onSaveLoadButton()
            "settings" -> onSettingsButton()
        }
    }

    private fun onNewCampaignButton() {
        makeHidden("smNewGame")
        makeHidden("smMain")
        makeVisible("smCamp")
        // Picks up any run started, started over or cleared since this screen was last built
        // (`StartMenuCampaignScreen.refreshRegister`'s own doc comment).
        StartMenuCampaignScreen.refreshRegister()
        val campSelect = byId("smCampSel")?.firstChild as? HTMLSelectElement
        val campaign = ui.game.campaign
        if (campaign == null) {
            campSelect?.let { it.selectedIndex = 0 }
        } else {
            campSelect?.let { setSelectOption(it, campaign.name) }
        }
        (byId("smCampSel")?.firstChild as? HTMLSelectElement)?.let { triggerChange(it) }
    }

    private fun onNewScenarioButton() {
        makeHidden("smNewGame")
        makeHidden("smMain")
        makeVisible("smScen")
        val scenSelect = byId("smScenSel")?.firstChild as? HTMLSelectElement
        val allScenarios: Array<dynamic> =
            js(
                "typeof scenariolist !== 'undefined' ? scenariolist : []",
            ).unsafeCast<Array<dynamic>>()
        val defaultEntry =
            allScenarios.firstOrNull {
                (it.length as? Int ?: 0) > 1 &&
                    it[0] as? String == Game.defaultScenario
            }
        val scenarioName =
            ui.game.scenario?.name
                ?: (if (defaultEntry != null) defaultEntry[1] as? String else null)
        if (scenarioName != null) scenSelect?.let { setSelectOption(it, scenarioName) }
        (byId("smScenSel")?.firstChild as? HTMLSelectElement)?.let { triggerChange(it) }
    }

    private fun onTutorialButton() {
        makeHidden("smMain")
        makeHidden("startmenu")
        ui.game.campaign = null
        uiSettings.noFOW = false
        uiSettings.isAI[0] = 2
        uiSettings.isAI[1] = 2
        ui.game.newScenario(Game.defaultScenario, null)
        byId("options")?.let { toggleButton(it, false) }
    }

    private fun onSaveLoadButton() {
        makeHidden("smMain")
        makeHidden("smNewGame")
        // Pre-game the window is "Load Game" with Save muted; mid-game the full pair.
        GameStateMenuBuilder.applySaveLoadContext()
        makeVisible("smState")
    }

    private fun onRestartMissionButton() {
        val checkpoint = ui.game.missionRestartCheckpoint
        if (!checkpoint.isAvailable()) return
        // The game's own anchored confirmation card, not the browser's native window.confirm:
        // every other destructive/replacing action in the app already asks this way.
        ConfirmCard.open(
            I18n.t("menu.main.restart_mission.confirm.title"),
            I18n.t("menu.main.restart_mission.confirm.body"),
            I18n.t("menu.main.restart_mission.confirm.confirm_button"),
        ) {
            makeHidden("startmenu")
            byId("options")?.let { toggleButton(it, false) }
            if (!checkpoint.restart()) {
                makeVisible("startmenu")
                makeVisible("smMain")
                UIBuilder.message(
                    I18n.t("game.error.title"),
                    I18n.t("menu.main.restart_mission.failed"),
                )
            }
        }
    }

    private fun onSettingsButton() {
        makeHidden("startmenu")
        makeHidden("smNewGame")
        // Observer Mode is locked for the length of a multiplayer match; the screen itself is
        // built once at startup, so the lock has to be re-applied every time it opens.
        ObserverModeLock.refresh()
        makeVisible("smSettings")
        if (isVisible("ui-message")) {
            makeHidden("ui-message")
            byId("smSettings")?.asDynamic()?.messageHidden = true
        }
    }

    private fun triggerChange(select: HTMLSelectElement) {
        select.dispatchEvent(Event("change"))
    }
}
