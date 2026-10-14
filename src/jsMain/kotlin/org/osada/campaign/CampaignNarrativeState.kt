package org.osada.campaign

/**
 * A scenario result as the GAME produced it — never as dialogue imagined it.
 *
 * [outcome] is one of the four engine outcome keys (`briliant`, `victory`, `tactical`, `lose`;
 * the legacy spelling of `briliant` is intentional and load-bearing — it is the key used in
 * campaign JSON, save data and [org.osada.scenario.Campaign.loadNextScenario]).
 *
 * [nextScenario] is the route the campaign took (`CampaignTransitions`), captured for the record.
 * It is null when the campaign ended (goto 254/255).
 *
 * [visit] is which entry into [scenarioFile] this was ([ScenarioVisit]); 1 for a save written
 * before visits, which kept only the first result of each scenario.
 */
internal data class ScenarioOutcomeRecord(
    val scenarioFile: String,
    val outcome: String,
    val nextScenario: String?,
    val visit: Int = 1,
)

/**
 * One committed briefing answer: [choiceId] given to line [lineId] of [scenarioFile]'s briefing.
 *
 * Scoped by scenario because line ids repeat across a campaign -- every Greek (`camp6bn4`) and
 * `camp6bn9` mission calls its choice line `decision`, `rhu` reuses `landler-question` -- and a
 * map keyed by the line alone let the first mission's answer block every later one, effects and
 * route included (found in review, 2026-10-14). [scenarioFile] is null for an answer restored from
 * a save written before that scoping; such a record only answers a line that offers its
 * [choiceId] (choice ids are unique within each shipped campaign), which is also where it was given.
 */
internal data class ChoiceRecord(
    val scenarioFile: String?,
    val lineId: String,
    val choiceId: String,
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
    private val choices = mutableListOf<ChoiceRecord>()
    private val flagSet = mutableSetOf<String>()

    /** Optional objectives resolved from real gameplay. */
    val actions = ScenarioActionLog()

    /** Applied-effect ids and queued next-scenario effects. */
    val effects = CampaignEffectLedger()

    /** A player-committed `CampaignEffect.Route`, if any, awaiting `continueCampaign`. */
    val route = RouteOverride()

    /** Which visit of its scenario the battle in play is. */
    val visits = ScenarioVisits()

    /** Completed scenarios in play order. */
    val scenarioOutcomes: List<ScenarioOutcomeRecord> get() = outcomes.toList()

    /** Committed dialogue choices in commit order. */
    val choiceRecords: List<ChoiceRecord> get() = choices.toList()

    val flags: Set<String> get() = flagSet.toSet()

    val isEmpty: Boolean
        get() =
            outcomes.isEmpty() &&
                choices.isEmpty() &&
                flagSet.isEmpty() &&
                actions.isEmpty &&
                effects.isEmpty &&
                route.isEmpty &&
                visits.current == null

    // ---------------------------------------------------------------- outcomes

    /**
     * Records a finished scenario. Idempotent per VISIT ([ScenarioOutcomeRecord.visit]): the first
     * result of a visit wins, so a double end-of-scenario detection (move-capture victory AND
     * end-turn defeat both firing) cannot append two records, while a replay of the scenario -- a
     * later visit -- records its own. Returns true when this call actually recorded something.
     */
    fun recordOutcome(record: ScenarioOutcomeRecord): Boolean {
        if (outcomes.any { it.scenarioFile == record.scenarioFile && it.visit == record.visit }) return false
        outcomes += record
        return true
    }

    /** The result of [scenarioFile]'s LATEST visit: a replay won after a loss reads as won. */
    fun outcomeOf(scenarioFile: String): String? = outcomes.lastOrNull { it.scenarioFile == scenarioFile }?.outcome

    /** The scenario played immediately before the current one, or null at campaign start. */
    fun previousOutcome(): ScenarioOutcomeRecord? = outcomes.lastOrNull()

    /** Counts every visit: a mission lost and then won counts one loss and one success. */
    fun countOutcomes(vararg wanted: String): Int = outcomes.count { it.outcome in wanted }

    // ----------------------------------------------------------------- choices

    /**
     * Commits [choiceId] for line [lineId] of [scenarioFile], which [offered] lists as its current
     * choices. The FIRST valid selection for a line is binding: re-entering the dialogue, navigating
     * Back into the line, or double-clicking a choice button cannot change or re-commit it. Returns
     * true only on the committing call, which is what gates effect application.
     *
     * A stored answer that matches none of [offered] (content changed since it was saved) does not
     * bind: it is replaced, so the line can be answered rather than stay owed forever.
     */
    fun recordChoice(
        scenarioFile: String,
        lineId: String,
        choiceId: String,
        offered: Collection<String>,
    ): Boolean {
        if (choiceId !in offered || answerTo(scenarioFile, lineId, offered) != null) return false
        choices.removeAll { it.scenarioFile == scenarioFile && it.lineId == lineId }
        choices += ChoiceRecord(scenarioFile, lineId, choiceId)
        return true
    }

    fun chose(choiceId: String): Boolean = choices.any { it.choiceId == choiceId }

    /**
     * The committed answer to line [lineId] of [scenarioFile], or null when it has none that is one
     * of [offered]: an answer is satisfied only by a choice the line still offers.
     */
    fun answerTo(
        scenarioFile: String,
        lineId: String,
        offered: Collection<String>,
    ): String? =
        choices
            .firstOrNull { record ->
                record.lineId == lineId &&
                    record.choiceId in offered &&
                    (record.scenarioFile == null || record.scenarioFile == scenarioFile)
            }?.choiceId

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
        choiceRecords: List<ChoiceRecord>,
        flagValues: Set<String>,
        actionMap: Map<String, Set<String>>,
        applied: Set<String>,
        queued: List<PendingEffect>,
        route: Int?,
    ) {
        outcomes.clear()
        outcomes += outcomeRecords
        choices.clear()
        choices += choiceRecords
        flagSet.clear()
        flagSet += flagValues
        actions.restore(actionMap)
        effects.restore(applied, queued)
        this.route.restore(route)
    }
}
