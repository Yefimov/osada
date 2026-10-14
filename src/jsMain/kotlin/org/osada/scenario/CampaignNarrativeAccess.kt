package org.osada.scenario

/*
 * Read-only campaign lookups used by the narrative and consequence system.
 *
 * Extension functions rather than [Campaign] members, following the project's facade idiom
 * (`GameMap`, `Player`): the exported class keeps its existing surface and function count, and
 * narrative-only accessors live next to the feature that needs them.
 *
 * Nothing here mutates campaign state — in particular [recordAfter] deliberately does
 * NOT advance the campaign, so it can be called while recording an outcome, before
 * [Campaign.advance] runs.
 */

/** The scenario file [transition] continues the campaign with, or null where the campaign does not continue. */
internal fun Campaign.fileAfter(transition: CampaignTransition): String? = recordAfter(transition)?.scenario as? String

/**
 * The whole campaign record [transition] continues with, read without advancing the campaign, so
 * it can be consulted while the outcome is recorded, before [Campaign.advance] runs.
 */
internal fun Campaign.recordAfter(transition: CampaignTransition): dynamic =
    CampaignTransitions.recordOf(getCampaignData(), transition)

/**
 * The record [outcome] routes to from [currentIndex] of [scenarios], with [routeOverride] the
 * committed route choice if any ([CampaignTransitions.resolve]); null where the campaign does not
 * continue. The imported `gotoplayed` pair is not read: the engine does not read it either.
 */
internal fun nextScenarioRecord(
    scenarios: Array<dynamic>,
    currentIndex: Int,
    outcome: String,
    routeOverride: Int? = null,
): dynamic =
    CampaignTransitions.recordOf(
        scenarios,
        CampaignTransitions.resolve(scenarios, currentIndex, outcome, routeOverride),
    )

/** Raw optional `actions` array (end-of-scenario objective rules) for the current scenario. */
fun Campaign.getCurrentScenarioActions(): dynamic = getCampaignData().getOrNull(currentScenarioIndex)?.actions

/**
 * Raw optional `effects` array on the current scenario's [outcome] branch — consequences of
 * having finished this battle at this grade, queued for whichever scenario the campaign routes to.
 *
 * Authored alongside the existing `prestige` / `goto` / `text` fields, so a campaign that does not
 * use it is unchanged.
 */
fun Campaign.getOutcomeEffects(outcome: String): dynamic {
    val outcomes: dynamic = getCampaignData().getOrNull(currentScenarioIndex)?.outcome ?: return null
    return outcomes[outcome]?.effects
}
