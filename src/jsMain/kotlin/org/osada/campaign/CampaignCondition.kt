package org.osada.campaign

/**
 * Facts a dialogue line may be gated on. Every field is optional; a condition with no populated
 * field matches everything (which is what an old, condition-free dialogue line parses to).
 *
 * Semantics are AND across fields, OR within a field's list:
 * `previousOutcome: ["briliant", "victory"]` reads "the previous scenario went at least well",
 * and adding `allFlags: ["x"]` narrows that to "...and flag x is set".
 *
 * These are FACTS ONLY. A condition can read the recorded outcome of a scenario; it can never
 * assert one. Nothing in this file writes to [CampaignNarrativeState].
 */
internal data class CampaignCondition(
    val campaignFile: List<String> = emptyList(),
    val currentScenario: List<String> = emptyList(),
    val previousScenario: List<String> = emptyList(),
    val previousOutcome: List<String> = emptyList(),
    /** Outcome of a specific named earlier scenario: `{"rhu190523.xml": ["briliant"]}`. */
    val scenarioOutcome: Map<String, List<String>> = emptyMap(),
    val selectedChoices: List<String> = emptyList(),
    val allFlags: List<String> = emptyList(),
    val anyFlags: List<String> = emptyList(),
    val noneFlags: List<String> = emptyList(),
    val completedActions: List<String> = emptyList(),
    val failedActions: List<String> = emptyList(),
    val minSuccesses: Int? = null,
    val maxSuccesses: Int? = null,
    val minScenarioIndex: Int? = null,
    val maxScenarioIndex: Int? = null,
) {
    fun isEmpty(): Boolean = this == EMPTY

    companion object {
        val EMPTY = CampaignCondition()

        /** Outcome keys that count as "the operation succeeded" for [minSuccesses]. */
        val SUCCESS_OUTCOMES = arrayOf("briliant", "victory", "tactical")
    }
}

/** Immutable snapshot of where the campaign is right now, evaluated against by [CampaignConditionEvaluator]. */
internal data class CampaignContext(
    val campaignFile: String,
    val currentScenario: String,
    val scenarioIndex: Int,
    val state: CampaignNarrativeState,
)

internal object CampaignConditionEvaluator {
    /**
     * True when [condition] holds for [context].
     *
     * An empty condition is always true, so condition-free dialogue (every line in every campaign
     * shipped before this system) keeps displaying exactly as it did.
     */
    fun matches(
        condition: CampaignCondition,
        context: CampaignContext,
    ): Boolean {
        if (condition.isEmpty()) return true
        val state = context.state
        val previous = state.previousOutcome()
        return matchesLocation(condition, context) &&
            matchesPrevious(condition, previous) &&
            matchesNamedOutcomes(condition, state) &&
            matchesFlagsAndChoices(condition, state) &&
            matchesActions(condition, state) &&
            matchesCounts(condition, context)
    }

    private fun matchesLocation(
        condition: CampaignCondition,
        context: CampaignContext,
    ): Boolean =
        anyOf(condition.campaignFile, context.campaignFile) &&
            anyOf(condition.currentScenario, context.currentScenario)

    private fun matchesPrevious(
        condition: CampaignCondition,
        previous: ScenarioOutcomeRecord?,
    ): Boolean =
        anyOf(condition.previousScenario, previous?.scenarioFile) &&
            anyOf(condition.previousOutcome, previous?.outcome)

    private fun matchesNamedOutcomes(
        condition: CampaignCondition,
        state: CampaignNarrativeState,
    ): Boolean =
        condition.scenarioOutcome.all { (scenario, accepted) ->
            val actual = state.outcomeOf(scenario)
            actual != null && actual in accepted
        }

    private fun matchesFlagsAndChoices(
        condition: CampaignCondition,
        state: CampaignNarrativeState,
    ): Boolean =
        condition.allFlags.all { state.hasFlag(it) } &&
            (condition.anyFlags.isEmpty() || condition.anyFlags.any { state.hasFlag(it) }) &&
            condition.noneFlags.none { state.hasFlag(it) } &&
            condition.selectedChoices.all { state.chose(it) }

    private fun matchesActions(
        condition: CampaignCondition,
        state: CampaignNarrativeState,
    ): Boolean =
        condition.completedActions.all { state.actions.has(it) } &&
            condition.failedActions.none { state.actions.has(it) }

    private fun matchesCounts(
        condition: CampaignCondition,
        context: CampaignContext,
    ): Boolean {
        val successes = context.state.countOutcomes(*CampaignCondition.SUCCESS_OUTCOMES)
        val index = context.scenarioIndex
        return (condition.minSuccesses?.let { successes >= it } ?: true) &&
            (condition.maxSuccesses?.let { successes <= it } ?: true) &&
            (condition.minScenarioIndex?.let { index >= it } ?: true) &&
            (condition.maxScenarioIndex?.let { index <= it } ?: true)
    }

    /** Empty list = unconstrained. A constrained field with a null actual value cannot match. */
    private fun anyOf(
        accepted: List<String>,
        actual: String?,
    ): Boolean = accepted.isEmpty() || (actual != null && actual in accepted)
}

/**
 * Reads a `conditions` object. Unknown keys are IGNORED WITH A WARNING rather than treated as
 * false — an authoring typo must not silently delete a line from the campaign, and must never
 * block progression. A malformed object degrades to [CampaignCondition.EMPTY] (always shown).
 */
internal object CampaignConditionParser {
    private val KNOWN_KEYS =
        setOf(
            "campaignFile",
            "currentScenario",
            "previousScenario",
            "previousOutcome",
            "scenarioOutcome",
            "selectedChoices",
            "allFlags",
            "anyFlags",
            "noneFlags",
            "completedActions",
            "failedActions",
            "minSuccesses",
            "maxSuccesses",
            "minScenarioIndex",
            "maxScenarioIndex",
        )

    @Suppress("TooGenericExceptionCaught")
    fun parse(value: dynamic): CampaignCondition {
        if (!BriefingDynamic.isObject(value)) return CampaignCondition.EMPTY
        return try {
            warnUnknownKeys(value)
            CampaignCondition(
                campaignFile = BriefingDynamic.strList(value.campaignFile),
                currentScenario = BriefingDynamic.strList(value.currentScenario),
                previousScenario = BriefingDynamic.strList(value.previousScenario),
                previousOutcome = BriefingDynamic.strList(value.previousOutcome),
                scenarioOutcome = parseOutcomeMap(value.scenarioOutcome),
                selectedChoices = BriefingDynamic.strList(value.selectedChoices),
                allFlags = BriefingDynamic.strList(value.allFlags),
                anyFlags = BriefingDynamic.strList(value.anyFlags),
                noneFlags = BriefingDynamic.strList(value.noneFlags),
                completedActions = BriefingDynamic.strList(value.completedActions),
                failedActions = BriefingDynamic.strList(value.failedActions),
                minSuccesses = BriefingDynamic.int(value.minSuccesses),
                maxSuccesses = BriefingDynamic.int(value.maxSuccesses),
                minScenarioIndex = BriefingDynamic.int(value.minScenarioIndex),
                maxScenarioIndex = BriefingDynamic.int(value.maxScenarioIndex),
            )
        } catch (e: Throwable) {
            console.warn("[OSADA] campaign condition parse failed, line will always show", e)
            CampaignCondition.EMPTY
        }
    }

    private fun parseOutcomeMap(value: dynamic): Map<String, List<String>> {
        if (!BriefingDynamic.isObject(value)) return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        val keys = js("Object.keys")(value).unsafeCast<Array<String>>()
        for (key in keys) {
            val accepted = BriefingDynamic.strList(value[key])
            if (accepted.isNotEmpty()) out[key] = accepted
        }
        return out
    }

    private fun warnUnknownKeys(value: dynamic) {
        val keys = js("Object.keys")(value).unsafeCast<Array<String>>()
        val unknown = keys.filterNot { it in KNOWN_KEYS }
        if (unknown.isNotEmpty()) {
            console.warn("[OSADA] unknown campaign condition keys ignored: ${unknown.joinToString(", ")}")
        }
    }
}
