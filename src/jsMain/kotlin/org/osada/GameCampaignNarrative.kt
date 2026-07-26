package org.osada

import org.osada.campaign.CampaignEffectParser
import org.osada.campaign.CampaignNarrative
import org.osada.campaign.ScenarioActionParser
import org.osada.campaign.ScenarioEndState
import org.osada.scenario.getCurrentScenarioActions
import org.osada.scenario.getOutcomeEffects
import org.osada.scenario.peekNextScenarioFile

/*
 * Bridges the running game to the campaign narrative system. Everything here is a no-op outside a
 * campaign, so standalone scenarios are untouched.
 */

/**
 * Records the REAL outcome of the scenario that just finished, together with the route the
 * existing campaign routing selected and any optional objectives that live end-state satisfied.
 *
 * Called from `Game.continueCampaign`, the single point at which the engine has definitively
 * completed a scenario. Must run BEFORE `Campaign.loadNextScenario` advances the index, so the
 * "current" scenario is still the one that was played.
 *
 * [routeOverride] is the committed `CampaignEffect.Route`, if any, that will replace the
 * outcome's `goto` — passed through so the recorded route and the queued outcome effects agree
 * with where the campaign is actually headed.
 */
internal fun Game.recordCampaignOutcome(
    outcome: String,
    routeOverride: Int?,
) {
    val activeCampaign = campaign ?: return
    val scenarioFile = activeCampaign.getCurrentScenario()?.scenario as? String ?: return
    val nextScenario = activeCampaign.peekNextScenarioFile(outcome, routeOverride)
    val recorded =
        CampaignNarrative.recordScenarioCompletion(
            scenarioFile = scenarioFile,
            outcome = outcome,
            nextScenario = nextScenario,
            actionRules = ScenarioActionParser.parseList(activeCampaign.getCurrentScenarioActions()),
            endState = buildScenarioEndState(),
        )
    // Queue this grade's authored consequences for the battle the campaign routes to. Guarded by
    // `recorded` so a duplicate completion cannot queue them twice, and skipped entirely when the
    // campaign ends here (no next scenario to carry them into).
    if (recorded && nextScenario != null) {
        CampaignNarrative.queueForNextScenario(
            targetScenario = nextScenario,
            effects = CampaignEffectParser.parseList(activeCampaign.getOutcomeEffects(outcome)),
        )
    }
}

/**
 * Snapshot of live end-of-scenario facts for optional-objective evaluation. Null when the map or
 * campaign player is unavailable, in which case the outcome is still recorded but no optional
 * objective is credited — never the reverse.
 */
private fun Game.buildScenarioEndState(): ScenarioEndState? {
    val map = scenario?.map
    val player = campaignPlayer
    return if (map == null || player == null) {
        null
    } else {
        ScenarioEndState(
            map = map,
            playerSide = player.side,
            turn = map.turn,
            coreLosses = player.getCoreUnitList().count { it.destroyed },
        )
    }
}

/**
 * Consumes the effects queued for the scenario that has just loaded, once, before the player
 * receives control. Restoring a save already inside the scenario applies nothing: consumption
 * both dequeues the effect and marks its id applied, and the save carries both facts.
 */
internal fun Game.applyPendingCampaignEffects() {
    val scenarioFile = scenario?.file ?: return
    if (campaign == null) return
    CampaignNarrative.consumePendingFor(scenarioFile, campaignPlayer)
}
