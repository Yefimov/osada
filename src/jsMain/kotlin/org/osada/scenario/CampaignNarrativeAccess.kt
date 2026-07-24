package org.osada.scenario

/*
 * Read-only campaign lookups used by the narrative and consequence system.
 *
 * Extension functions rather than [Campaign] members, following the project's facade idiom
 * (`GameMap`, `Player`): the exported class keeps its existing surface and function count, and
 * narrative-only accessors live next to the feature that needs them.
 *
 * Nothing here mutates campaign state — in particular [peekNextScenarioFile] deliberately does
 * NOT advance the campaign, so it can be called while recording an outcome, before
 * [Campaign.loadNextScenario] runs.
 */

/**
 * The scenario file [outcome] would route to, without advancing the campaign.
 *
 * Returns null for the campaign-ending sentinels (254 victory / 255 defeat) — which is exactly
 * how "the campaign ends here" is recorded. Both shipping story campaigns route every `lose` to
 * 255, so a loss there yields null.
 */
fun Campaign.peekNextScenarioFile(outcome: String): String? {
    val scenarios = getCampaignData()
    // Bracket access, not `?.get(outcome)`: the outcome map is a plain JS object parsed from the
    // campaign JSON, so a `.get(...)` method call throws "get is not a function" at runtime.
    val outcomes: dynamic = scenarios.getOrNull(currentScenarioIndex)?.outcome
    val goto = if (outcomes == null) null else outcomes[outcome]?.goto as? Int
    return goto?.let { scenarios.getOrNull(it)?.scenario as? String }
}

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
