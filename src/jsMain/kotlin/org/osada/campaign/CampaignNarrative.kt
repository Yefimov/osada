package org.osada.campaign

import org.osada.campaign.CampaignNarrative.commitChoice
import org.osada.campaign.CampaignNarrative.consumePendingFor
import org.osada.campaign.CampaignNarrative.queueForNextScenario
import org.osada.campaign.CampaignNarrative.recordScenarioCompletion
import org.osada.campaign.CampaignNarrative.reset
import org.osada.campaign.CampaignNarrative.restore
import org.osada.campaign.CampaignNarrative.snapshot
import org.osada.model.Player
import org.osada.scenario.Campaign
import org.osada.ui.briefing.BriefingLocalization

/**
 * The single entry point the rest of the engine uses to talk to the narrative system.
 *
 * Holds the state for the CURRENT campaign run. `Game.newCampaign` calls [reset]; the save
 * layer calls [restore] / [snapshot]; `Game.continueCampaign` calls [recordScenarioCompletion];
 * the dialogue controller calls [commitChoice]; the scenario loader calls [consumePendingFor].
 *
 * Standalone scenarios never touch this object. Nothing here is consulted unless
 * `Game.campaign != null`, so a standalone battle behaves exactly as it did before.
 */
internal object CampaignNarrative {
    private var current = CampaignNarrativeState()

    val state: CampaignNarrativeState get() = current

    /** Starting a new campaign wipes everything. Called from `Game.newCampaign`. */
    fun reset() {
        current = CampaignNarrativeState()
    }

    /** Save payload, or null when there is nothing worth writing (keeps old-shaped saves clean). */
    fun snapshot(): dynamic = if (current.isEmpty) null else CampaignNarrativeSerializer.serialize(current)

    /** Restores from a save's `campaign.narrative` block. A null/corrupt block yields empty state. */
    fun restore(narrative: dynamic) {
        current = CampaignNarrativeSerializer.deserialize(narrative)
    }

    /**
     * Records a definitively completed scenario: its real outcome, the route the EXISTING campaign
     * routing chose, and any optional objectives that real end-state satisfied.
     *
     * Returns false when this scenario was already recorded — the guard that makes the
     * move-capture and end-turn completion paths safe to both fire.
     */
    fun recordScenarioCompletion(
        scenarioFile: String,
        outcome: String,
        nextScenario: String?,
        actionRules: List<ScenarioActionRule>,
        endState: ScenarioEndState?,
    ): Boolean {
        val recorded =
            current.recordOutcome(
                ScenarioOutcomeRecord(
                    scenarioFile = scenarioFile,
                    outcome = outcome,
                    nextScenario = nextScenario,
                ),
            )
        if (!recorded) {
            console.log("[OSADA] scenario '$scenarioFile' already recorded; ignoring duplicate completion")
            return false
        }
        if (endState != null && actionRules.isNotEmpty()) {
            ScenarioActionEvaluator.evaluate(actionRules, endState).forEach { actionId ->
                current.actions.record(scenarioFile, actionId)
                console.log("[OSADA] scenario action achieved: $scenarioFile.$actionId")
            }
        }
        return true
    }

    /**
     * Commits a dialogue choice and applies its effects — both exactly once.
     *
     * Effects apply immediately rather than being queued: campaign dialogue is shown AFTER its
     * scenario has loaded but BEFORE the player has moved, so "next-scenario setup" and "right
     * now" are the same moment. Queuing is for outcome-driven effects, which are resolved at the
     * end of scenario N and must wait for N+1 to load — see [queueForNextScenario].
     *
     * Re-selecting, navigating Back and forward, reopening the briefing, or restoring a save all
     * route through here and are all no-ops after the first commit. Returns true only on the
     * committing call, so the caller can distinguish "committed" from "already committed".
     */
    fun commitChoice(
        lineId: String,
        choiceId: String,
        effects: List<CampaignEffect>,
        player: Player?,
    ): Boolean {
        if (!current.recordChoice(lineId, choiceId)) return false
        CampaignEffectApplier.apply(effects, player, current)
        return true
    }

    /**
     * Queues an outcome-driven effect for the scenario the campaign is routing to. Consumed once
     * by [consumePendingFor] after that scenario loads.
     */
    fun queueForNextScenario(
        targetScenario: String,
        effects: List<CampaignEffect>,
    ) {
        effects.forEach { current.effects.queue(PendingEffect(targetScenario, it)) }
    }

    /**
     * Consumes the effects queued for [scenarioFile]. Called once after that scenario has loaded
     * and before the player receives control. Restoring a save already inside the scenario does
     * NOT re-apply them: they were removed from the queue and marked applied when first consumed,
     * and both facts are part of the save.
     */
    fun consumePendingFor(
        scenarioFile: String,
        player: Player?,
    ): List<String> {
        val due = current.effects.takeFor(scenarioFile)
        if (due.isEmpty()) return emptyList()
        val applied = CampaignEffectApplier.apply(due, player, current)
        console.log("[OSADA] applied ${applied.size} pending campaign effect(s) for $scenarioFile")
        return applied
    }

    /**
     * Consumes a player-committed [CampaignEffect.Route] override, if any, clearing it so it
     * cannot leak into resolving a later, unrelated transition.
     *
     * Called once by `Game.continueCampaign`, before both [queueForNextScenario]'s target lookup
     * and `Campaign.loadNextScenario` — a committed route replaces the outcome-branch `goto`
     * entirely, it does not just relabel it.
     */
    fun takeCommittedRoute(): Int? = current.route.take()

    /** Builds the evaluation context for dialogue conditions. */
    fun context(
        campaignFile: String,
        currentScenario: String,
        scenarioIndex: Int,
    ): CampaignContext =
        CampaignContext(
            campaignFile = campaignFile,
            currentScenario = currentScenario,
            scenarioIndex = scenarioIndex,
            state = current,
        )
}

/**
 * One authored campaign-ending voice selected from the real final outcome and accumulated
 * narrative state. Epilogues live on the final scenario entry because that is the point at which
 * the campaign still has both its unadvanced scenario index and every committed choice flag.
 */
internal data class CampaignEpilogue(
    val id: String,
    val outcomes: List<String>,
    val speaker: String,
    val role: String,
    val text: String,
    val condition: CampaignCondition,
)

internal object CampaignEpilogueParser {
    fun parseList(
        value: dynamic,
        domain: String? = null,
    ): List<CampaignEpilogue> =
        BriefingDynamic.mapArray(value) { item ->
            if (!BriefingDynamic.isObject(item)) return@mapArray null
            val id = BriefingDynamic.str(item.id) ?: return@mapArray null
            val text = BriefingDynamic.str(item.text) ?: return@mapArray null
            fun localized(field: String, fallback: String): String =
                domain?.let { BriefingLocalization.resolve(it, "epilogue.$id.$field", fallback) } ?: fallback
            CampaignEpilogue(
                id = id,
                outcomes = BriefingDynamic.strList(item.outcomes),
                speaker = localized("speaker", BriefingDynamic.str(item.speaker) ?: ""),
                role = localized("role", BriefingDynamic.str(item.role) ?: ""),
                text = localized("text", text),
                condition = CampaignConditionParser.parse(item.conditions),
            )
        }
}

internal object CampaignEpilogueResolver {
    fun resolve(
        value: dynamic,
        outcome: String,
        context: CampaignContext,
        domain: String? = null,
    ): CampaignEpilogue? =
        CampaignEpilogueParser.parseList(value, domain).firstOrNull { epilogue ->
            (epilogue.outcomes.isEmpty() || outcome in epilogue.outcomes) &&
                CampaignConditionEvaluator.matches(epilogue.condition, context)
        }
}

/** Returns display-safe HTML appended to the ordinary final outcome text. */
internal fun Campaign.resolveEpilogue(outcome: String): String? {
    val scenario = getCurrentScenario() ?: return null
    val scenarioFile = scenario.scenario as? String ?: ""
    val epilogue =
        CampaignEpilogueResolver.resolve(
            value = scenario.epilogues,
            outcome = outcome,
            context = CampaignNarrative.context(file, scenarioFile, currentScenarioIndex),
            domain = BriefingLocalization.domain(file, scenarioFile),
        )
    return epilogue?.let { selected ->
        val byline =
            buildString {
                if (selected.speaker.isNotBlank()) {
                    append("<strong>${escapeEpilogueHtml(selected.speaker)}</strong>")
                }
                if (selected.role.isNotBlank()) append(" — <em>${escapeEpilogueHtml(selected.role)}</em>")
            }
        "<div class=\"osada-campaign-epilogue\">" +
            "$byline<p>${escapeEpilogueHtml(selected.text)}</p></div>"
    }
}

private fun escapeEpilogueHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
