package org.osada.ui

import org.osada.campaign.ScenarioActionRule
import org.osada.i18n.I18n

/**
 * Player-facing wording for one authored `scenario.actions` rule, derived from the rule's own
 * parameters (`docs/design/action-affordances-and-objectives.md` §9).
 *
 * A stable id such as `airfield_held_at_end` is a campaign fact key, not UI copy, and must never
 * reach the screen. An authored `label` wins when present; otherwise the rule describes itself with
 * the exact numbers the evaluator will use, so the rail cannot promise a threshold the engine will
 * not check.
 */
internal object ScenarioObjectiveText {
    fun describe(rule: ScenarioActionRule): String =
        when (rule) {
            is ScenarioActionRule.HexesHeld -> hexesHeld(rule)
            is ScenarioActionRule.HexesNotHeld ->
                I18n.plural(
                    "hud.objective.rule.hexes_not_held",
                    rule.hexes.size,
                    mapOf("count" to rule.hexes.size),
                )

            is ScenarioActionRule.UnitsSurvived ->
                I18n.plural(
                    "hud.objective.rule.units_survived",
                    rule.atLeast,
                    mapOf("count" to rule.atLeast),
                )

            is ScenarioActionRule.FinishedByTurn ->
                I18n.t("hud.objective.rule.finished_by_turn", mapOf("turn" to rule.turn))

            // English has no `zero` plural category, so "lose at most 0" would come out as the
            // `other` branch. The strictest and most common case gets its own sentence instead.
            is ScenarioActionRule.CoreLossesAtMost ->
                if (rule.maxLosses == 0) {
                    I18n.t("hud.objective.rule.core_losses_none")
                } else {
                    I18n.plural(
                        "hud.objective.rule.core_losses",
                        rule.maxLosses,
                        mapOf("count" to rule.maxLosses),
                    )
                }

            is ScenarioActionRule.EventFired -> I18n.t("hud.objective.rule.event_fired")
        }

    /** `atLeast` is the partial-success threshold the evaluator applies; when absent it demands all
     *  of them, and the two read very differently to a player deciding where to push. */
    private fun hexesHeld(rule: ScenarioActionRule.HexesHeld): String {
        val required = rule.atLeast ?: rule.hexes.size
        return if (required >= rule.hexes.size) {
            I18n.plural("hud.objective.rule.hexes_held_all", rule.hexes.size, mapOf("count" to rule.hexes.size))
        } else {
            I18n.t(
                "hud.objective.rule.hexes_held_some",
                mapOf("count" to required, "total" to rule.hexes.size),
            )
        }
    }
}
