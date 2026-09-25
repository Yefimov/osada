package org.osada.scenario

import org.osada.i18n.I18n

/**
 * Why a battle that ran out of turns was lost, in terms the player can check against the map.
 *
 * "You have run out of turns" was the whole explanation, and in a campaign it was followed by the
 * author's own defeat text. For `forward4` that text says Odessa has fallen -- written for OG's
 * defeat branch -- while the player who lost had Odessa safe and had taken two of the three
 * objectives the counterattack needed (2026-09-24 report). The rule is OG's and stays: with no hold
 * counts authored, anything short of every objective by the last turn is a defeat. What changes is
 * that the message now says which rule the player actually failed.
 */
internal object TimedDefeatReason {
    fun text(
        scenario: Scenario,
        side: Int,
    ): String {
        val counts = if (side == 0) scenario.victoryHoldCounts else scenario.victoryHoldCountsSide1
        val tacticalTier = 2
        val remaining =
            scenario.map.sidesVictoryHexes
                .getOrNull(side)
                ?.size ?: 0
        return when {
            counts.size > tacticalTier ->
                I18n.t(
                    "game.loss_reason.turns_hold",
                    mapOf("held" to objectivesHeldBy(scenario.map, side), "need" to counts[tacticalTier]),
                )

            remaining > 0 -> I18n.t("game.loss_reason.turns_capture", mapOf("count" to remaining))
            else -> I18n.t("game.loss_reason.turns")
        }
    }
}
