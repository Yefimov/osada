package org.osada.ui.briefing

import org.osada.Game
import org.osada.campaign.CampaignConditionEvaluator
import org.osada.campaign.CampaignNarrative
import org.osada.current

/**
 * Drops dialogue lines whose conditions the current campaign state does not satisfy, so a
 * briefing shows only the reactions that are actually true of this run.
 *
 * Outside a campaign this is a pass-through: a standalone scenario has no campaign state to react
 * to, and must never show campaign-only reactive dialogue. Condition-free dialogue is also a
 * pass-through, because [org.osada.campaign.CampaignCondition.EMPTY] matches everything — which
 * is what keeps every pre-existing campaign's dialogue rendering exactly as before.
 *
 * Filtering happens once, at parse time, so the rest of the controller (navigation, `lineById`,
 * `nextSequential`, the conversation log) operates on visible lines only and needs no knowledge
 * of conditions.
 */
internal object CampaignDialogueFilter {
    fun apply(parsed: ScenarioBriefing): ScenarioBriefing {
        val game = Game.current
        val activeCampaign = game?.campaign
        val hasConditions = parsed.dialogue.any { !it.condition.isEmpty() }
        if (activeCampaign == null || !hasConditions) return parsed

        val context =
            CampaignNarrative.context(
                campaignFile = activeCampaign.file,
                currentScenario = game.scenario?.file.orEmpty(),
                scenarioIndex = activeCampaign.currentScenarioIndex,
            )
        val visible = parsed.dialogue.filter { CampaignConditionEvaluator.matches(it.condition, context) }
        return if (visible.size == parsed.dialogue.size) {
            parsed
        } else {
            parsed.copy(dialogue = repairDanglingLinks(visible))
        }
    }

    /**
     * Rewrites `next` pointers that a filtered-out line left dangling.
     *
     * A conditional reaction line normally converges on a shared branch, so its target survives.
     * When the target itself was filtered out, the pointer is cleared rather than left broken:
     * navigation then falls through to the next sequential visible line instead of dead-ending
     * the conversation. A conditional campaign must never be able to strand the player.
     */
    private fun repairDanglingLinks(visible: List<BriefingLine>): List<BriefingLine> {
        val ids = visible.mapTo(mutableSetOf()) { it.id }
        return visible.map { line ->
            val nextOk = line.next == null || line.next in ids
            val choicesOk = line.choices.all { it.next == null || it.next in ids }
            if (nextOk && choicesOk) {
                line
            } else {
                warnDangling(line, ids)
                line.copy(
                    next = line.next?.takeIf { it in ids },
                    choices = line.choices.map { choice -> choice.copy(next = choice.next?.takeIf { it in ids }) },
                )
            }
        }
    }

    private fun warnDangling(
        line: BriefingLine,
        ids: Set<String>,
    ) {
        val broken =
            (listOfNotNull(line.next) + line.choices.mapNotNull { it.next })
                .filterNot { it in ids }
                .distinct()
        console.warn(
            "[OSADA] dialogue line '${line.id}' points at filtered-out node(s) " +
                "${broken.joinToString(", ")}; falling through to the next visible line",
        )
    }
}
