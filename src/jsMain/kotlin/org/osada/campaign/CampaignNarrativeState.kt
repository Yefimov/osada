package org.osada.campaign

/**
 * A scenario result as the GAME produced it — never as dialogue imagined it.
 *
 * [outcome] is one of the four engine outcome keys (`briliant`, `victory`, `tactical`, `lose`;
 * the legacy spelling of `briliant` is intentional and load-bearing — it is the key used in
 * campaign JSON, save data and [org.osada.scenario.Campaign.loadNextScenario]).
 *
 * [nextScenario] is the route the EXISTING campaign routing chose, captured for the record. It is
 * null when the campaign ended (goto 254/255).
 */
internal data class ScenarioOutcomeRecord(
    val scenarioFile: String,
    val outcome: String,
    val nextScenario: String?,
)

/** An effect waiting for [targetScenario] to load. Consumed once, before the player gets control. */
internal data class PendingEffect(
    val targetScenario: String,
    val effect: CampaignEffect,
)

/**
 * The player-chosen route override committed via a [CampaignEffect.Route] dialogue effect, if
 * any. Split out purely to keep [CampaignNarrativeState] within the project's function budget,
 * following the same decomposition as [CampaignEffectLedger] and [ScenarioActionLog].
 */
internal class RouteOverride {
    private var scenarioIndex: Int? = null

    val isEmpty: Boolean get() = scenarioIndex == null

    /** Overwriting an existing value is not a concern in practice: effect ids are idempotent, so
     *  an already-applied route effect cannot re-fire and re-commit here. */
    fun set(index: Int) {
        scenarioIndex = index
    }

    /** Reads the override without consuming it. Used by the save serializer, which must not
     *  mutate state as a side effect of writing it out. */
    fun peek(): Int? = scenarioIndex

    /** Consumes and clears the override, if any, so it cannot leak into resolving some later,
     *  unrelated transition. */
    fun take(): Int? {
        val value = scenarioIndex
        scenarioIndex = null
        return value
    }

    fun restore(value: Int?) {
        scenarioIndex = value
    }
}

/**
 * Persistent narrative and consequence state for ONE campaign run.
 *
 * Lifecycle: created empty by `newCampaign`, mutated only through the `record*` functions here
 * and on [actions] / [effects], serialized into the save's `campaign.narrative` block, and
 * restored from it. Old saves have no such block and get an empty state.
 *
 * Collections are exposed read-only; mutation goes through recording functions so that
 * "record exactly once" is enforced in one place rather than at every call site. The effect
 * ledger, scenario-action log and route override are separate collaborators
 * ([CampaignEffectLedger], [ScenarioActionLog], [RouteOverride]) to keep each class within the
 * project's function budget.
 */
internal class CampaignNarrativeState {
    private val outcomes = mutableListOf<ScenarioOutcomeRecord>()
    private val choices = mutableMapOf<String, String>()
    private val flagSet = mutableSetOf<String>()

    /** Optional objectives resolved from real gameplay. */
    val actions = ScenarioActionLog()

    /** Applied-effect ids and queued next-scenario effects. */
    val effects = CampaignEffectLedger()

    /** A player-committed `CampaignEffect.Route`, if any, awaiting `continueCampaign`. */
    val route = RouteOverride()

    /** Completed scenarios in play order. */
    val scenarioOutcomes: List<ScenarioOutcomeRecord> get() = outcomes.toList()

    /** Committed dialogue choices, keyed by the line id that offered them. */
    val selectedChoices: Map<String, String> get() = choices.toMap()

    val flags: Set<String> get() = flagSet.toSet()

    val isEmpty: Boolean
        get() =
            outcomes.isEmpty() &&
                choices.isEmpty() &&
                flagSet.isEmpty() &&
                actions.isEmpty &&
                effects.isEmpty &&
                route.isEmpty

    // ---------------------------------------------------------------- outcomes

    /**
     * Records a finished scenario. Idempotent per scenario file: the first result wins, so a
     * double end-of-scenario detection (move-capture victory AND end-turn defeat both firing)
     * cannot append two records. Returns true when this call actually recorded something.
     */
    fun recordOutcome(record: ScenarioOutcomeRecord): Boolean {
        if (outcomes.any { it.scenarioFile == record.scenarioFile }) return false
        outcomes += record
        return true
    }

    fun outcomeOf(scenarioFile: String): String? = outcomes.firstOrNull { it.scenarioFile == scenarioFile }?.outcome

    /** The scenario played immediately before the current one, or null at campaign start. */
    fun previousOutcome(): ScenarioOutcomeRecord? = outcomes.lastOrNull()

    fun countOutcomes(vararg wanted: String): Int = outcomes.count { it.outcome in wanted }

    // ----------------------------------------------------------------- choices

    /**
     * Commits a dialogue choice. The FIRST selection for a given line is binding: re-entering the
     * dialogue, navigating Back into the line, or double-clicking a choice button cannot change or
     * re-commit it. Returns true only on the committing call, which is what gates effect
     * application.
     */
    fun recordChoice(
        lineId: String,
        choiceId: String,
    ): Boolean {
        if (choices.containsKey(lineId)) return false
        choices[lineId] = choiceId
        return true
    }

    fun chose(choiceId: String): Boolean = choices.containsValue(choiceId)

    fun choiceAt(lineId: String): String? = choices[lineId]

    // ------------------------------------------------------------------- flags

    fun setFlag(flag: String) {
        flagSet += flag
    }

    fun clearFlag(flag: String) {
        flagSet -= flag
    }

    fun hasFlag(flag: String): Boolean = flag in flagSet

    // ------------------------------------------------------------ restore only

    /** Bulk load used by [CampaignNarrativeSerializer]; bypasses the once-only guards by design. */
    fun restoreFrom(
        outcomeRecords: List<ScenarioOutcomeRecord>,
        choiceMap: Map<String, String>,
        flagValues: Set<String>,
        actionMap: Map<String, Set<String>>,
        applied: Set<String>,
        queued: List<PendingEffect>,
        route: Int?,
    ) {
        outcomes.clear()
        outcomes += outcomeRecords
        choices.clear()
        choices += choiceMap
        flagSet.clear()
        flagSet += flagValues
        actions.restore(actionMap)
        effects.restore(applied, queued)
        this.route.restore(route)
    }
}
