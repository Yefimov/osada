package org.osada

import org.osada.model.Player
import org.osada.model.addOutcomeToDossier
import org.osada.model.deployReinforcement
import org.osada.model.getPlayer
import org.osada.scenario.getReinforcements
import org.osada.scenario.removeReinforcement
import org.osada.ui.UIBuilder
import org.osada.ui.handleReinforcementDeployment
import org.osada.ui.mainMenuButton
import org.osada.ui.message
import org.osada.ui.showAlert
import org.osada.ui.showCampaignEnd

/**
 * Handles the end-of-scenario victory condition triggered by capturing
 * all objectives during a move. Called from the move-animation callback.
 */
fun Game.handleMoveVictory(winningSide: Int) {
    if (gameEnded) return
    val outcome = scenario?.checkVictory() ?: return
    if (campaign != null) {
        val isHumanWin = campaignPlayer?.side == winningSide
        if (isHumanWin) {
            continueCampaign(outcome, EndGameType.MOVE_CAPTURE)
        } else {
            continueCampaign("lose", EndGameType.MOVE_CAPTURE)
        }
    } else {
        gameEnded = true
        val currentType = scenario?.map?.currentPlayer?.type
        val title = if (currentType == PlayerType.HUMAN_LOCAL) outcomeNames[outcome] ?: outcome else "DEFEAT"
        val message =
            if (currentType == PlayerType.HUMAN_LOCAL) {
                "You have won this scenario!"
            } else {
                "You have lost! Your enemy wins by capturing all victory hexes."
            }
        UIBuilder.message(title, "<br/><br/><br/><br/>$message") {
            ui?.mainMenuButton("options")
        }
    }
}

fun Game.continueCampaign(
    outcome: String,
    reason: EndGameType = EndGameType.MOVE_CAPTURE,
) {
    console.log("[OSADA] continueCampaign", outcome)
    val player = campaignPlayer ?: return
    player.prestige += campaign!!.getOutcomePrestige(outcome)
    player.addOutcomeToDossier(outcome, scenario!!.name)
    OSGlue.reportScore(player.score)
    player.setPlayerToHQ()
    savedCampaignPlayer = Player().apply { copy(player) }
    removeNonCampaignUnitsFlag = true
    buildCoreUnitsFlag = false
    val text = campaign!!.getOutcomeText(outcome)
    nextScenarioData = campaign!!.loadNextScenario(outcome)
    continueCampaignFlag = true
    if (nextScenarioData == null) {
        val finalText = if (outcome == "lose") endGameLossText[reason] + text else text
        UIBuilder.showCampaignEnd(outcome, finalText) { ui?.mainMenuButton("options") }
        gameEnded = true
        gameStarted = false
        if (outcome != "lose") {
            OSGlue.reportAchievement(campaign!!.file)
        }
    } else {
        UIBuilder.message(outcomeNames[outcome] ?: outcome, text, narrative = true)
        if (outcome == "briliant") awardPrototype = true
    }
}

fun Game.getCampaignPlayer(): Player? = campaignPlayer

fun Game.setCurrentSide() {
    spotSide = if (humanSides == 2) scenario?.map?.currentPlayer?.side ?: 0 else humanSides
    console.log(
        "[OSADA] setCurrentSide humanSides=$humanSides spotSide=$spotSide " +
            "currentPlayer.side=${scenario?.map?.currentPlayer?.side}",
    )
}

fun Game.deployReinforcements(
    turn: Int,
    playerId: Int,
) {
    var deployed = false
    val reinforcements = scenario?.getReinforcements(turn, playerId) ?: return
    val side = scenario!!.map.getPlayer(playerId).side
    reinforcements.forEach { reinf ->
        val pos = scenario!!.map.deployReinforcement(reinf.unit, reinf.row, reinf.col)
        if (pos != null) {
            deployed = true
            CombatLog.addReinforcement(reinf.unit)
            scenario!!.removeReinforcement(reinf.turn, reinf.id)
            val hex = scenario!!.map.map!![pos.row][pos.col]
            if (hex.isSpotted(spotSide)) {
                val isFriendly = spotSide == side
                ui?.showAlert(pos.row, pos.col, "Reinforced!", isFriendly)
            }
        }
    }
    if (deployed) {
        // Reinforcements can introduce unit/transport eqids whose sprites were not part of the
        // initial cacheImages() pass. Re-cache (idempotent — already-loaded images are skipped)
        // and repaint so the newly deployed units are drawn instead of rendering invisible.
        ui?.render?.cacheImages { ui?.render?.render() }
        ui?.handleReinforcementDeployment()
    }
}

fun Game.cleanup() {
    console.log("[OSADA] cleanup")
    org.osada.ui.WeatherRenderer
        .stop()
    org.osada.ui.WeatherModel
        .stop()
    state?.clear()
    scenario?.map?.cleanup()
    scenario = null
}
