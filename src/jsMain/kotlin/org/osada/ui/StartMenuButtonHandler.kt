package org.osada.ui

import org.osada.Game
import org.osada.multiplayer.local.LocalTwoTabMultiplayer
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
            "multiplayer" -> LocalTwoTabMultiplayer.openHub(ui.game)
            "tutorial" -> onTutorialButton()
            "continuegame" -> {
                makeHidden("startmenu")
                byId("options")?.let { toggleButton(it, false) }
            }

            "saveload" -> onSaveLoadButton()
            "settings" -> onSettingsButton()
        }
    }

    private fun onNewCampaignButton() {
        makeHidden("smNewGame")
        makeHidden("smMain")
        makeVisible("smCamp")
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

    private fun onSettingsButton() {
        makeHidden("startmenu")
        makeHidden("smNewGame")
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
