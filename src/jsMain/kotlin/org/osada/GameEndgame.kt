package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.campaign.resolveEpilogue
import org.osada.hero.ArchiveRunStatus
import org.osada.hero.HeroArchiveService
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.Player
import org.osada.model.addOutcomeToDossier
import org.osada.model.awardPrestige
import org.osada.model.collectPersistentCampaignUnits
import org.osada.model.deployReinforcement
import org.osada.model.ensureFormationIds
import org.osada.model.getPlayer
import org.osada.save.SaveStatus
import org.osada.save.SaveStatusBus
import org.osada.scenario.getReinforcements
import org.osada.scenario.removeReinforcement
import org.osada.ui.HudLog
import org.osada.ui.MessageDialogs
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
        val title =
            if (currentType == PlayerType.HUMAN_LOCAL) {
                localizedOutcomeName(outcome)
            } else {
                I18n.t("game.outcome.lose")
            }
        val message =
            if (currentType == PlayerType.HUMAN_LOCAL) {
                I18n.t("game.scenario.victory.body")
            } else {
                I18n.t("game.scenario.defeat.objectives.body")
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
    player.awardPrestige(campaign!!.getOutcomePrestige(outcome))
    player.addOutcomeToDossier(outcome, scenario!!.name)
    OSGlue.reportScore(player.score)
    val carryOver = scenario!!.map.collectPersistentCampaignUnits(player)
    val outcomeLabel = localizedOutcomeName(outcome)
    player.getCoreUnitList().forEach { unit ->
        HeroCampaign.recordFormationEvent(
            unit = unit,
            eventId = "scenario_completed",
            turn = scenario!!.map.turn,
            location = I18n.t("game.history.outcome", mapOf("outcome" to outcomeLabel)),
        )
    }
    // Formation experience is captured HERE, before `setPlayerToHQ` drops destroyed units from the
    // core roster, so the archive keeps the number the dossier would have shown at the end of the
    // battle (`docs/design/hero-desk-and-profile-archive.md` §4: archive before scenario cleanup
    // can discard a formation detail).
    val formationExperience =
        player
            .getCoreUnitList()
            .mapNotNull { unit -> unit.formationId?.takeIf { it.isNotEmpty() }?.let { it to unit.experience } }
            .toMap()
    archiveHeroRoster(formationExperience, ArchiveRunStatus.IN_PROGRESS)
    console.log(
        "[OSADA] campaign carry-over: ${carryOver.survivors}/${carryOver.candidates} of YOUR formations " +
            "carried forward; lost: destroyed=${carryOver.destroyed}, temporary=${carryOver.temporary}, " +
            "nodossier=${carryOver.noDossier} (re-minted ids=${carryOver.reMintedFormationIds}, not a loss)",
    )
    player.setPlayerToHQ()
    savedCampaignPlayer = Player().apply { copy(player) }
    removeNonCampaignUnitsFlag = true
    buildCoreUnitsFlag = false
    val outcomeText = campaign!!.getOutcomeText(outcome)
    val epilogue = campaign!!.resolveEpilogue(outcome)
    val text = if (epilogue == null) outcomeText else "$outcomeText<hr>$epilogue"
    nextScenarioData = campaign!!.loadNextScenario(outcome, routeOverride)
    continueCampaignFlag = true
    if (nextScenarioData == null) {
        val finalText = if (outcome == "lose") localizedLossReason(reason) + text else text
        UIBuilder.showCampaignEnd(outcome, finalText) { ui?.mainMenuButton("options") }
        gameEnded = true
        gameStarted = false
        // The campaign register's "Completed" state, recorded here because this is the only place
        // that knows a campaign is over -- no further save is written once gameStarted is false,
        // so it can never be inferred from a stored generation. The outcome rides along so a
        // campaign that ended in DEFEAT is not labelled as one the player completed.
        state?.markCampaignRunCompleted(campaign!!.file, outcome)
        // The final upsert (§4). OSADA writes no autosave after a campaign ends, so without this
        // the archive would keep saying the run is still in progress and its survivors would never
        // present as retired from it. Idempotent: the same complete roster, one status later.
        archiveHeroRoster(formationExperience, ArchiveRunStatus.forOutcome(outcome))
        if (outcome != "lose") {
            OSGlue.reportAchievement(campaign!!.file)
        }
    } else {
        UIBuilder.message(localizedOutcomeName(outcome), text, narrative = true)
        if (outcome == "briliant") awardPrototype = true
    }
}

/**
 * Upserts this campaign run's complete hero roster into the profile archive
 * (`docs/design/hero-desk-and-profile-archive.md` §4).
 *
 * Runs inside `continueCampaign`, the single funnel that knows a mission outcome is committed, so
 * the archive is written in the same logical operation as the campaign transition. A failed write
 * never marks the mission incomplete — the game save has already committed by this point — but it
 * raises the persistent save-status warning, because a silently missing career is exactly the loss
 * the archive exists to prevent.
 */
private fun Game.archiveHeroRoster(
    formationExperience: Map<String, Int>,
    runStatus: ArchiveRunStatus,
) {
    val campaign = campaign ?: return
    val result =
        HeroArchiveService.upsert(
            roster = HeroCampaign.roster(),
            formationExperience = formationExperience,
            campaignRunId = campaign.file,
            campaignFile = campaign.file,
            campaignName = campaign.name,
            lastScenarioId = scenario?.name.orEmpty(),
            lastScenarioIndex = campaign.currentScenarioIndex,
            runStatus = runStatus,
        )
    if (result != null && !result.isSuccess) {
        SaveStatusBus.update(SaveStatus.Failed(result.message ?: result.kind.name))
    }
}

fun Game.getCampaignPlayer(): Player? = campaignPlayer

/**
 * Hands the spotting side to whoever is now playing, and prints the log's **turn banner**.
 *
 * This runs on every hand-off, which makes it the one place that can date everything after it. It
 * used to print only `humanSides`/`spotSide`/`side`, so a log had no turn numbers anywhere in it —
 * a bug report of the form "on turn 2 I saw X" could not be checked against its own log, and the
 * only way to tell which turn a line belonged to was to count these lines by hand. Player id, side
 * and type are all here too, because "the enemy's turn" is a claim about `type`, not about `side`.
 */
fun Game.setCurrentSide() {
    spotSide = if (humanSides == 2) scenario?.map?.currentPlayer?.side ?: 0 else humanSides
    val map = scenario?.map
    val player = map?.currentPlayer
    console.log(
        "[OSADA] === turn ${map?.turn}/${map?.maxTurns} · player ${player?.id} " +
            "(side ${player?.side}, ${player?.type?.name}) · spotSide=$spotSide ===",
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
                ui?.showAlert(pos.row, pos.col, I18n.t("game.reinforcement.alert"), isFriendly)
            }
            // The floating "Reinforced!" alert is fog-gated, so a scripted reinforcement that
            // lands off-screen or in unspotted territory arrived with no notice at all. OG always
            // announces your OWN arrivals ("reinforcements have arrived"), so log those
            // unconditionally; the row is clickable and jumps to the unit.
            if (spotSide == side) {
                HudLog.addAt(
                    pos.row,
                    pos.col,
                    I18n.t(
                        "game.reinforcement.arrived",
                        mapOf("unit" to reinf.unit.unitData(true).name),
                    ),
                )
            }
        }
    }
    // Authored announcement for this wave (`<reinforce message="...">`), OG-style. Only for the
    // side the player is watching — the enemy's reinforcements are not theirs to be told about.
    if (deployed && spotSide == side) {
        scenario?.reinforcementMessages?.get(turn)?.let {
            UIBuilder.message(I18n.t("game.reinforcement.title"), it)
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
    // Nothing about the battle just finished may follow the player into the next one. Both of these
    // outlived the transition: HeroCampaign's queues are on a long-lived object cleared only by
    // reset() (a NEW RUN, not a new scenario), and MessageDialogs' boxes are plain DOM nodes under
    // #mainbody that no teardown touched. That is how a Frigate's hero announcement from N_Kiel
    // opened on Willhelmshafen turn 1.
    HeroCampaign.discardPendingAnnouncements()
    MessageDialogs.clearDynamicMessages()
    state?.clear()
    scenario?.map?.cleanup()
    scenario = null
}

private fun localizedOutcomeName(outcome: String): String =
    when (outcome) {
        "lose" -> I18n.t("game.outcome.lose")
        "victory" -> I18n.t("game.outcome.victory")
        "tactical" -> I18n.t("game.outcome.tactical")
        "briliant" -> I18n.t("game.outcome.brilliant")
        else -> outcome
    }

private fun localizedLossReason(reason: EndGameType): String =
    when (reason) {
        EndGameType.MOVE_CAPTURE -> I18n.t("game.loss_reason.objectives")
        EndGameType.NO_TURNS_LEFT -> I18n.t("game.loss_reason.turns")
        EndGameType.NO_ENEMY_LEFT -> I18n.t("game.loss_reason.units")
    }
