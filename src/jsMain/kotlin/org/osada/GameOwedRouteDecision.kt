package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.i18n.I18n
import org.osada.scenario.CampaignRouting
import org.osada.scenario.CampaignTransition
import org.osada.scenario.CampaignTransitionProblem
import org.osada.scenario.CampaignTransitions
import org.osada.ui.UIBuilder
import org.osada.ui.briefing.BriefingLine
import org.osada.ui.briefing.OwedBriefingDecision
import org.osada.ui.mainMenuButton
import org.osada.ui.message
import org.osada.ui.showOwedDecision

// Split from GameEndgame.kt (function-count limit), beside the defeat question in GameDefeatChoice.kt.

/**
 * The transition [continueCampaign] commits for [outcome], or null while it must not commit yet.
 * Null when this battle's result is already committed, or while a question comes first: a defeat may
 * be restarted instead (`offerRestartBeforeDefeat`), a victory may still owe its route decision;
 * either answer re-enters [continueCampaign]. Null as well, for good, when the campaign data cannot
 * route [outcome] ([refuseInvalidTransition]).
 *
 * Once per battle: the first commit already advanced `campaign` to the next mission, so a second
 * one (another objective captured, or the last turn ended, under the outcome message) recorded a
 * result for a mission not yet played, paid its prestige and skipped it (review, 2026-10-14).
 */
internal fun Game.committableTransition(
    outcome: String,
    reason: EndGameType,
): CampaignTransition? {
    val active = campaign
    if (active == null || continueCampaignFlag || offerRestartBeforeDefeat(outcome, reason)) return null
    val resolved =
        CampaignTransitions.resolve(
            active.getCampaignData(),
            active.currentScenarioIndex,
            outcome,
            committedRoute = CampaignNarrative.state.route.peek(),
            decisionOwed = owedRouteDecision(outcome) != null,
        )
    val transition =
        when {
            resolved !is CampaignTransition.NeedsDecision -> resolved
            offerOwedRouteDecision(outcome, reason) -> null
            else -> resolved.withoutDecision
        }
    if (transition is CampaignTransition.InvalidData) refuseInvalidTransition(transition.problem)
    return transition?.takeUnless { it is CampaignTransition.InvalidData }
}

/**
 * A victory whose briefing's route decision was never recorded asks for it before routing.
 *
 * A committed route replaces the winning branches (`CampaignRouting`); without one, the authored
 * `goto` is taken, so a `forward15` victory restored from a save with no front choice went to
 * Smolensk as if Centre had been chosen. The restored battle normally gets the decision back before
 * play resumes (`UI.showOwedDecision`); this is the last line for a battle that ends without it. A
 * loss keeps its authored transition and never asks. Returns true while the decision is pending;
 * answering it re-enters [continueCampaign], where the committed route is taken exactly once.
 */
private fun Game.offerOwedRouteDecision(
    outcome: String,
    reason: EndGameType,
): Boolean {
    awaitingRouteDecision = true
    val shown =
        ui?.showOwedDecision("briefing.decision.continue.label") {
            awaitingRouteDecision = false
            continueCampaign(outcome, reason)
        } ?: false
    if (!shown) awaitingRouteDecision = false
    return shown
}

/**
 * The campaign data routes this result nowhere it can go (`CampaignTransitions.validate` logged it
 * when the campaign loaded). Committing anything would corrupt the run: a negative target used to
 * set a negative scenario index, and a target past the list ended the campaign and marked it
 * completed. So nothing is recorded or paid, the battle stops, and the save stays at it -- a
 * repaired campaign file resumes it, and the battle can be finished again.
 */
private fun Game.refuseInvalidTransition(problem: CampaignTransitionProblem) {
    console.error("[OSADA] campaign '${campaign?.file}' cannot continue: ${problem.describe()}")
    gameEnded = true
    UIBuilder.message(
        I18n.t("game.error.title"),
        I18n.t(
            "game.campaign.invalid_transition",
            mapOf("mission" to (scenario?.name ?: "—"), "target" to (problem.target?.toString() ?: "—")),
        ),
    ) { ui?.mainMenuButton("options") }
}

/**
 * The first owed decision [outcome] would otherwise be routed past, or null: never for a loss (it
 * keeps its authored transition), never once a route is committed, and only when a route decision
 * lies at or beyond it -- the owed line itself may be an earlier decision without one
 * (`OwedBriefingDecision.routeAhead`). The sheet then asks each owed decision in turn.
 */
internal fun Game.owedRouteDecision(outcome: String): BriefingLine? {
    if (!CampaignRouting.routeApplies(outcome) || !CampaignNarrative.state.route.isEmpty) return null
    return OwedBriefingDecision.owedInCurrentBattle(this)?.takeIf { it.routeAhead }?.line
}
