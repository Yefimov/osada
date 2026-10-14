package org.osada.ui.briefing

import org.osada.Game
import org.osada.campaign.CampaignConditionEvaluator
import org.osada.campaign.CampaignContext
import org.osada.campaign.CampaignEffect
import org.osada.campaign.CampaignNarrative
import org.osada.campaign.CampaignNarrativeState
import org.osada.resolveScenarioBriefing

/**
 * The briefing decision a campaign run still owes, judged from the SAVED narrative, never from the
 * intro tracker.
 *
 * `BriefingIntroTracker` remembers "this briefing was opened" outside the save. Loading an earlier
 * save brings back a narrative without the choice while the marker stays, so the seen fast path
 * skipped `forward15`'s front choice and the victory fell through to the authored `goto: 23`
 * (Smolensk) without the player ever choosing (reported 2026-10-14). The ceremony, a restored
 * battle and the campaign's routing all ask this instead: walk the conversation along the choices
 * the narrative has committed, and report the first choice line it has no answer for.
 *
 * Lines are filtered by their conditions exactly as [CampaignDialogueFilter] does, and a pointer at
 * a filtered-out line falls through to the next visible one, as its repair does.
 */
internal object OwedBriefingDecision {
    /** Cycle guard, as in `BriefingPendingChoice`: authored `next` ids can point backwards. */
    private const val MAX_STEPS = 500

    /**
     * The first unanswered choice line on the committed path through [briefing] (of
     * [scenarioFile]), or null. A line is answered only by a committed choice it still offers
     * (`CampaignNarrativeState.answerTo`): another mission's answer to a line with the same id, or
     * an id the content no longer has, leaves it owed.
     */
    fun firstOwed(
        briefing: ScenarioBriefing,
        state: CampaignNarrativeState,
        scenarioFile: String,
    ): BriefingLine? {
        var line = briefing.dialogue.firstOrNull()
        var steps = 0
        while (line != null && steps < MAX_STEPS) {
            steps++
            val current: BriefingLine = line
            var taken: BriefingChoice? = null
            if (current.choices.isNotEmpty()) {
                val committed = state.answerTo(scenarioFile, current.id, current.choices.map { it.id })
                taken = current.choices.firstOrNull { it.id == committed } ?: return current
            }
            line = follow(briefing, current, taken?.next ?: current.next)
        }
        return null
    }

    /**
     * Whether a route decision can still be reached from [from], [from] included, along ANY of the
     * conversation's branches. An earlier decision without a route can stand before the route
     * choice (the Greek `bn4s11` offers `earned-decision` first), so the owed line itself not
     * setting a route does not mean the transition is free. Read over the unfiltered dialogue: a
     * later line may become visible only through the flag an earlier answer sets.
     */
    fun routeAhead(
        briefing: ScenarioBriefing,
        from: BriefingLine,
    ): Boolean {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty() && seen.size < MAX_STEPS) {
            val line = queue.removeFirst()
            if (!seen.add(line.id)) continue
            if (decidesRoute(line)) return true
            val targets = if (line.choices.isEmpty()) listOf(line.next) else line.choices.map { it.next ?: line.next }
            targets.mapNotNullTo(queue) { follow(briefing, line, it) }
        }
        return false
    }

    /** [target], or the next line in order when it is absent or was filtered out. */
    private fun follow(
        briefing: ScenarioBriefing,
        line: BriefingLine,
        target: String?,
    ): BriefingLine? = target?.let { briefing.lineById(it) } ?: briefing.nextSequential(line)

    /** [firstOwed] for authored briefing data, filtered by [context]'s campaign state. */
    fun find(
        rawBriefing: dynamic,
        context: CampaignContext,
    ): BriefingLine? = owed(rawBriefing, context)?.line

    /** The owed line, and whether a route decision lies at or beyond it ([routeAhead]). */
    fun owed(
        rawBriefing: dynamic,
        context: CampaignContext,
    ): OwedDecision? {
        if (rawBriefing == null || rawBriefing == undefined) return null
        val parsed = BriefingParser.parse("", rawBriefing)
        val visible = parsed.dialogue.filter { CampaignConditionEvaluator.matches(it.condition, context) }
        return firstOwed(parsed.copy(dialogue = visible), context.state, context.currentScenario)?.let { line ->
            OwedDecision(line, routeAhead(parsed, parsed.lineById(line.id) ?: line))
        }
    }

    /** Whether answering [line] commits a campaign route (`CampaignEffect.Route`). */
    fun decidesRoute(line: BriefingLine): Boolean =
        line.choices.any { choice -> choice.effects.any { it is CampaignEffect.Route } }

    /**
     * The decision the campaign battle in progress still owes, or null. Never in a networked game:
     * co-op decisions are the room's (`CoopBriefingBridge`), not this browser's narrative.
     */
    fun forCurrentBattle(game: Game): BriefingLine? = owedInCurrentBattle(game)?.line

    /** [forCurrentBattle] with [OwedDecision.routeAhead]. */
    fun owedInCurrentBattle(game: Game): OwedDecision? {
        val campaign = game.campaign
        val scenarioFile = game.scenario?.file
        if (org.osada.multiplayer.client.OsadaMultiplayer.active || campaign == null || scenarioFile == null) {
            return null
        }
        val raw = game.resolveScenarioBriefing(campaign.getCurrentScenario(), scenarioFile)
        return owed(raw, CampaignNarrative.context(campaign.file, scenarioFile, campaign.currentScenarioIndex))
    }
}

/** A decision a briefing still owes, and whether a route decision is at or beyond it. */
internal data class OwedDecision(
    val line: BriefingLine,
    val routeAhead: Boolean,
)
