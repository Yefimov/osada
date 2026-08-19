package org.osada

import org.osada.hero.HeroCampaign
import org.osada.hero.LeaderMigration
import org.osada.model.acquireUnit
import org.osada.model.buildCoreUnitList
import org.osada.model.ensureFormationIds
import org.osada.model.getPlayers
import org.osada.model.getUnits
import org.osada.model.initDossier
import org.osada.model.removeNonCampaignUnits
import org.osada.model.restoreCoreUnitList
import org.osada.model.undeployCoreUnits
import org.osada.scenario.getBalancedPrestige
import org.osada.scenario.getRandomPrototype
import org.osada.ui.UI
import org.osada.ui.UIBuilder
import org.osada.ui.briefing.CampaignBriefingCatalog
import org.osada.ui.hideStartMenu
import org.osada.ui.showPrototypeAwardMessage

/**
 * Applies a restored save's core roster, which `GameStateRestore.restoreCampaign` parks rather than
 * applying itself: [Game.campaignPlayer] is assigned by `setupPlayers()`, which runs after the
 * campaign block has been read, so restoring there always saw a null player and silently dropped
 * the whole roster.
 *
 * Consumed exactly once, and before `ensureFormationIds`, so restored core units keep the formation
 * ids they were saved with instead of being minted new ones.
 *
 * `internal`, not `private`: [Game.setupGameState] -- the completion of every `restoreFromString`
 * call (a saved-game load, "Continue" on a campaign run, and `MissionRestartCheckpoint.restart`) --
 * calls this too. Those paths never run `onScenarioLoadFinished`/`handleCampaignScenarioLoaded` at
 * all, so a save's undeployed reserve was parked in [Game.pendingCoreUnitRestore] and never claimed:
 * the roster came back with every on-map unit but an empty reserve tray (2026-08-19 user report --
 * "if I Restart the campaign my Reserves are empty"). Safe to call from both places: consuming sets
 * the field to `null`, so whichever call site runs second is a no-op.
 */
internal fun Game.applyPendingCoreUnitRestore() {
    val pending = pendingCoreUnitRestore ?: return
    pendingCoreUnitRestore = null
    val player = campaignPlayer ?: return
    scenario!!.map.restoreCoreUnitList(player, pending.savedUnits)
    // Idempotent, so running it on an already-migrated save changes nothing.
    LeaderMigration.migrate(player, pending.campaignFile)
}

internal fun Game.handleCampaignScenarioLoaded() {
    console.log("[OSADA] onScenarioLoadFinished campaign branch")
    if (buildCoreUnitsFlag) {
        campaignPlayer?.prestige = campaign!!.startprestige
        campaignPlayer?.initDossier()
        scenario!!.map.buildCoreUnitList(campaignPlayer!!)
        // Only campaigns that OG starts with a buy/deploy phase (an undeployed reserve pool
        // in scenario 1 — forward, rcampdfr) hand the core back to the player UNDEPLOYED to
        // place; every other imported campaign has its first-scenario units PRE-PLACED, so we
        // must NOT lift them into the tray. (Scenarios 2+ always get the tray via carry-over.)
        if (campaign!!.deployPhase) {
            campaignPlayer?.let { scenario!!.map.undeployCoreUnits(it) }
        }
    }
    applyPendingCoreUnitRestore()
    // The player's whole force is their army and thus hero-eligible (§9.1) — not just the units on
    // deployment hexes. Mint a formation id for every remaining on-map unit so a pre-placed campaign
    // enters the hero system instead of falling back to the dossier-less legacy leader.
    campaignPlayer?.let { player ->
        val scriptedReinforcements =
            scenario!!
                .reinforcements.values
                .flatten()
                .map { it.unit }
        scenario!!.map.ensureFormationIds(player, scriptedReinforcements)
    }
    if (removeNonCampaignUnitsFlag) {
        scenario!!.map.removeNonCampaignUnits(campaignPlayer!!)
    }
    // Tell the hero system which campaign/scenario/year it is now in, so an emergence check deep in
    // combat can seed deterministically and date a new officer's biography. Runs on both a fresh
    // start and a restore (both reach this handler), and after the scenario date is set.
    HeroCampaign.setContext(
        campaignId = campaign!!.file,
        scenarioIndex = campaign!!.currentScenarioIndex,
        serviceYear = scenario!!.date.getFullYear(),
        country = campaignPlayer?.country,
        availableUnitClasses =
            buildSet {
                campaignPlayer
                    ?.getCoreUnitList()
                    ?.filterNot { it.isTemporaryBorrowed }
                    ?.mapTo(this) { it.unitData().uclass }
                scenario!!
                    .map
                    .getUnits()
                    .filter { it.owner == campaignPlayer?.id && !it.isTemporaryBorrowed }
                    .mapTo(this) { it.unitData().uclass }
                scenario!!
                    .reinforcements.values
                    .flatten()
                    .map { it.unit }
                    .filter { it.owner == campaignPlayer?.id && !it.isTemporaryBorrowed }
                    .mapTo(this) { it.unitData().uclass }
            },
    )
    // Consume next-scenario effects queued by the previous transition, after the core roster
    // exists and before the player receives control.
    applyPendingCampaignEffects()
    if (awardPrototype) {
        val prototype = scenario!!.getRandomPrototype(campaignPlayer!!.country + 1)
        if (prototype > 0) {
            campaignPlayer?.acquireUnit(prototype, 0)
            awardPrototype = false
            UIBuilder.showPrototypeAwardMessage(prototype)
        }
    }
    state?.saveCampaign()
}

internal fun Game.handleStandaloneScenarioLoaded(fromRestore: Boolean) {
    console.log("[OSADA] onScenarioLoadFinished scenario branch")
    scenario!!.showStatistics()
    if (!fromRestore && scenario!!.file != "tutorial.xml") {
        scenario!!.map.getPlayers().forEach { player ->
            player.prestige = scenario!!.getBalancedPrestige(player.side)
        }
    }
}

internal fun Game.ensureUiCreated() {
    if (ui != null) return
    ui = UI(this)
    val uiInstance = ui
    console.log("Game: created UI instance", uiInstance)
    js("window.game.ui = uiInstance")
    js("window.ui = uiInstance")
    console.log("[OSADA] onScenarioLoadFinished hiding start menu")
    UIBuilder.hideStartMenu()
}

internal fun Game.onCampaignLoadFinished() {
    console.log("[OSADA] onCampaignLoadFinished")
    savedCampaignPlayer = null
    removeNonCampaignUnitsFlag = false
    buildCoreUnitsFlag = true
    val current = campaign?.getCurrentScenario()
    if (current != null) {
        val scenarioFile = current.scenario as String
        val intro = current.intro as? String
        pendingScenarioBriefing = resolveScenarioBriefing(current, scenarioFile)
        newScenario(scenarioFile, intro)
    }
}

private fun Game.extractScenarioBriefing(data: dynamic): dynamic {
    if (data == null || data == undefined) return null
    return firstPresent(data.briefing, data.dialogue, data.dialogues)
}

private fun Game.firstPresent(vararg candidates: dynamic): dynamic {
    for (candidate in candidates) {
        if (candidate != null && candidate != undefined) return candidate
    }
    return null
}

internal fun Game.resolveScenarioBriefing(
    data: dynamic,
    scenarioFile: String,
): dynamic {
    val embedded = extractScenarioBriefing(data)
    val resolved =
        if (embedded != null && embedded != undefined) {
            embedded
        } else {
            CampaignBriefingCatalog.forScenario(scenarioFile)
        }
    console.log(
        "[OSADA] campaign briefing resolved",
        scenarioFile,
        resolved != null && resolved != undefined,
    )
    return resolved
}
