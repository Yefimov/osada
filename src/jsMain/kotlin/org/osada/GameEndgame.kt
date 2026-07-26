package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.model.Player
import org.osada.model.addOutcomeToDossier
import org.osada.model.collectPersistentCampaignUnits
import org.osada.model.deployReinforcement
import org.osada.model.ensureFormationIds
import org.osada.model.getPlayer
import org.osada.scenario.getReinforcements
import org.osada.scenario.removeReinforcement
import org.osada.ui.HudLog
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
                "Excellent work, Commander!<br/><br/>You have won this scenario.<br/><br/>" +
                    "Good luck in your next operation!"
            } else {
                "You have lost! Your enemy wins by capturing all victory hexes."
            }
        UIBuilder.message(title, message) {
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
    // A player CHOICE (BGCchoice-style), committed earlier in this scenario's own briefing
    // dialogue, overrides the outcome-branch goto entirely. Taken once, here, so it cannot leak
    // into resolving some later, unrelated transition.
    val routeOverride = CampaignNarrative.takeCommittedRoute()
    // The scenario is definitively over at this single funnel, so this is the one correct place
    // to record the REAL outcome. recordScenarioCompletion is idempotent per scenario: the
    // move-capture and end-turn completion paths can both reach here for the same battle.
    recordCampaignOutcome(outcome, routeOverride)
    player.prestige += campaign!!.getOutcomePrestige(outcome)
    player.addOutcomeToDossier(outcome, scenario!!.name)
    OSGlue.reportScore(player.score)
    val carryOver = scenario!!.map.collectPersistentCampaignUnits(player)
    val outcomeLabel = outcomeNames[outcome] ?: outcome
    player.getCoreUnitList().forEach { unit ->
        HeroCampaign.recordFormationEvent(
            unit = unit,
            eventId = "scenario_completed",
            turn = scenario!!.map.turn,
            location = "Outcome: $outcomeLabel",
        )
    }
    console.log(
        "[OSADA] campaign carry-over: ${carryOver.survivors}/${carryOver.candidates} formations; " +
            "destroyed=${carryOver.destroyed}, temporary=${carryOver.temporary}, " +
            "nodossier=${carryOver.noDossier}, duplicateIds=${carryOver.duplicateFormationIds}",
    )
    player.setPlayerToHQ()
    savedCampaignPlayer = Player().apply { copy(player) }
    removeNonCampaignUnitsFlag = true
    buildCoreUnitsFlag = false
    val text = campaign!!.getOutcomeText(outcome)
    nextScenarioData = campaign!!.loadNextScenario(outcome, routeOverride)
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
        campaignPlayer
            ?.takeIf { it.id == playerId }
            ?.let { scenario!!.map.ensureFormationIds(it, listOf(reinf.unit)) }
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
            // The floating "Reinforced!" alert is fog-gated, so a scripted reinforcement that
            // lands off-screen or in unspotted territory arrived with no notice at all. OG always
            // announces your OWN arrivals ("reinforcements have arrived"), so log those
            // unconditionally; the row is clickable and jumps to the unit.
            if (spotSide == side) {
                HudLog.addAt(pos.row, pos.col, "Reinforcement arrived: ${reinf.unit.unitData(true).name}")
            }
        }
    }
    // Authored announcement for this wave (`<reinforce message="...">`), OG-style. Only for the
    // side the player is watching — the enemy's reinforcements are not theirs to be told about.
    if (deployed && spotSide == side) {
        scenario?.reinforcementMessages?.get(turn)?.let { UIBuilder.message("Reinforcements", it) }
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
