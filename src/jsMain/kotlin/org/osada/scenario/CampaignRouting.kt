package org.osada.scenario

/**
 * The one rule deciding where a finished campaign scenario leads when the player has committed a
 * route choice (`CampaignEffect.Route`) in its briefing.
 *
 * A committed route replaces the WINNING branches only. A loss keeps the transition the campaign
 * authored for it: every imported route decision was the OG "choice of front" scenario, which OG
 * reached only by winning, and a briefing choice made BEFORE the battle cannot have meant to
 * overrule the defeat that followed it. Before this rule `forward15`'s front choice also overrode
 * its loss, so Prokhorovka (`forward16`, reached only by losing Kharkov) could never be played,
 * and losing the Greek spring offensive continued the campaign instead of ending it.
 *
 * Both the engine (`Game.continueCampaign`) and the campaign map (`CampaignGraphBuilder`) read
 * this, so the map can never show a route the engine does not take.
 */
object CampaignRouting {
    const val LOSE = "lose"

    /** Whether a committed route choice decides the transition after [outcome]. */
    fun routeApplies(outcome: String): Boolean = outcome != LOSE

    /** [committedRoute] when it decides the transition after [outcome], otherwise null. */
    fun effectiveRoute(
        outcome: String,
        committedRoute: Int?,
    ): Int? = committedRoute?.takeIf { routeApplies(outcome) }
}
