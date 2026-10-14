package org.osada.scenario

import org.osada.campaign.BriefingDynamic

/**
 * Where a finished campaign scenario leads: the one answer the engine (`Game.continueCampaign`),
 * the narrative's next-scenario lookups and the campaign map (`CampaignGraphBuilder`) all take.
 * Before it each of them read `goto` on its own, and they disagreed at the edges: a negative
 * target made `Campaign.loadNextScenario` set a negative scenario index while the peek and the map
 * called it the campaign end, and any target past the list quietly ended the campaign.
 */
internal sealed interface CampaignTransition {
    /** Play the record at [index] next. It may be the scenario just played: a replay. */
    data class NextScenario(
        val index: Int,
    ) : CampaignTransition

    /**
     * The campaign is over: a defeat for a `lose` grade, a finished campaign otherwise. The grade
     * decides it, not which of the 254/255 sentinels the JSON used (`camp6`'s `rcampx18` ends a
     * LOSS with 254), exactly as `Game.continueCampaign` has always shown it.
     */
    data class CampaignEnd(
        val defeat: Boolean,
    ) : CampaignTransition

    /**
     * A route decision this scenario's briefing offers was never answered, and a winning grade must
     * not be routed past it (`GameOwedRouteDecision`). [withoutDecision] is where the authored
     * branch would lead, for a caller that cannot ask.
     */
    data class NeedsDecision(
        val withoutDecision: CampaignTransition,
    ) : CampaignTransition

    /** The data cannot be routed. Nothing may be committed on it: the run would be corrupted. */
    data class InvalidData(
        val problem: CampaignTransitionProblem,
    ) : CampaignTransition
}

/** Why a transition cannot be taken. [target] is the offending index, when there is one. */
internal data class CampaignTransitionProblem(
    val kind: Kind,
    val scenarioIndex: Int,
    val outcome: String,
    val target: Int? = null,
    val fromRoute: Boolean = false,
) {
    enum class Kind {
        /** The campaign is not standing at any of its records. */
        NO_CURRENT_SCENARIO,

        /** The grade has no branch, or the branch has no integer `goto`. */
        NO_BRANCH,

        /** A negative scenario index. */
        NEGATIVE_TARGET,

        /** Past the record list and not one of the campaign-end sentinels. */
        UNKNOWN_TARGET,
    }

    /** One line for a log or a report. */
    fun describe(): String =
        "record $scenarioIndex, outcome '$outcome': " +
            when (kind) {
                Kind.NO_CURRENT_SCENARIO -> "no such campaign record"
                Kind.NO_BRANCH -> "no outcome branch with an integer goto"
                Kind.NEGATIVE_TARGET, Kind.UNKNOWN_TARGET ->
                    "${if (fromRoute) "route choice" else "goto"} $target is not a scenario of this campaign " +
                        "(0..last record, or ${CampaignTransitions.VICTORY_END}/${CampaignTransitions.DEFEAT_END} to end it)"
            }
}

internal object CampaignTransitions {
    /** OG's campaign-end sentinels. Only these two may stand past the record list. */
    const val VICTORY_END = 254
    const val DEFEAT_END = 255

    /** What a raw target index names in a campaign of [scenarioCount] records. */
    enum class Target { SCENARIO, CAMPAIGN_END, NEGATIVE, UNKNOWN }

    fun classify(
        target: Int,
        scenarioCount: Int,
    ): Target =
        when {
            target < 0 -> Target.NEGATIVE
            target < scenarioCount -> Target.SCENARIO
            target == VICTORY_END || target == DEFEAT_END -> Target.CAMPAIGN_END
            else -> Target.UNKNOWN
        }

    /**
     * The transition after [outcome] from record [currentIndex] of a campaign of [scenarioCount]
     * records, whose [outcome] branch authors [authoredGoto] (null: no usable branch).
     *
     * [committedRoute] is a briefing route choice (`CampaignEffect.Route`); it replaces the winning
     * branches only (`CampaignRouting`). [decisionOwed] is true while a route decision of this
     * briefing is still unanswered.
     */
    @Suppress("LongParameterList")
    fun resolve(
        scenarioCount: Int,
        currentIndex: Int,
        outcome: String,
        authoredGoto: Int?,
        committedRoute: Int? = null,
        decisionOwed: Boolean = false,
    ): CampaignTransition {
        if (currentIndex !in 0 until scenarioCount) {
            return CampaignTransitionProblem.Kind.NO_CURRENT_SCENARIO.at(currentIndex, outcome)
        }
        val route = CampaignRouting.effectiveRoute(outcome, committedRoute)
        val target = route ?: authoredGoto
        val taken =
            if (target == null) {
                CampaignTransitionProblem.Kind.NO_BRANCH.at(currentIndex, outcome)
            } else {
                transitionTo(target, scenarioCount, currentIndex, outcome, fromRoute = route != null)
            }
        // Asked even when the authored branch is broken: the answer may well route somewhere valid.
        val asks = decisionOwed && route == null && CampaignRouting.routeApplies(outcome)
        return if (asks) CampaignTransition.NeedsDecision(taken) else taken
    }

    /** [resolve] over the campaign JSON's own record list (`Campaign.getCampaignData`). */
    fun resolve(
        records: Array<dynamic>,
        currentIndex: Int,
        outcome: String,
        committedRoute: Int? = null,
        decisionOwed: Boolean = false,
    ): CampaignTransition =
        resolve(
            records.size,
            currentIndex,
            outcome,
            authoredGoto(records, currentIndex, outcome),
            committedRoute,
            decisionOwed,
        )

    /** The record [transition] continues the campaign with; null when it does not continue it. */
    fun recordOf(
        records: Array<dynamic>,
        transition: CampaignTransition,
    ): dynamic = (transition as? CampaignTransition.NextScenario)?.let { records.getOrNull(it.index) }

    /**
     * Every transition [records] can be asked to take that cannot be taken: each grade's branch and
     * each route choice its briefing offers. Run when a campaign loads, and by
     * `scripts/verify/check-campaign-transitions.mjs` over the shipped campaigns.
     */
    fun validate(records: Array<dynamic>): List<CampaignTransitionProblem> =
        records.indices.flatMap { index ->
            val branches =
                CampaignRouting.GRADES.mapNotNull { grade ->
                    (resolve(records, index, grade) as? CampaignTransition.InvalidData)?.problem
                }
            val routes =
                routeTargets(records[index]?.briefing).mapNotNull { target ->
                    val routed = resolve(records.size, index, CampaignRouting.ROUTED_GRADE, null, target)
                    (routed as? CampaignTransition.InvalidData)?.problem
                }
            branches + routes
        }

    private fun transitionTo(
        target: Int,
        scenarioCount: Int,
        currentIndex: Int,
        outcome: String,
        fromRoute: Boolean,
    ): CampaignTransition =
        when (classify(target, scenarioCount)) {
            Target.SCENARIO -> CampaignTransition.NextScenario(target)
            Target.CAMPAIGN_END -> CampaignTransition.CampaignEnd(defeat = outcome == CampaignRouting.LOSE)
            Target.NEGATIVE ->
                CampaignTransitionProblem.Kind.NEGATIVE_TARGET.at(
                    currentIndex,
                    outcome,
                    target,
                    fromRoute,
                )
            Target.UNKNOWN -> CampaignTransitionProblem.Kind.UNKNOWN_TARGET.at(currentIndex, outcome, target, fromRoute)
        }

    private fun CampaignTransitionProblem.Kind.at(
        index: Int,
        outcome: String,
        target: Int? = null,
        fromRoute: Boolean = false,
    ) = CampaignTransition.InvalidData(CampaignTransitionProblem(this, index, outcome, target, fromRoute))

    /** Bracket access on the plain JS objects: a Kotlin `.get(...)` call throws at runtime. */
    private fun authoredGoto(
        records: Array<dynamic>,
        index: Int,
        outcome: String,
    ): Int? {
        val outcomes: dynamic = records.getOrNull(index)?.outcome
        val branch: dynamic = if (outcomes == null) null else outcomes[outcome]
        return if (branch == null) null else BriefingDynamic.int(branch.goto)
    }

    /** `scenarioIndex` of every `route` effect anywhere in a raw briefing block. */
    private fun routeTargets(value: dynamic): List<Int> {
        val found = mutableListOf<Int>()
        val pending = ArrayDeque<dynamic>().apply { if (value != null) addLast(value) }
        while (pending.isNotEmpty()) {
            val item: dynamic = pending.removeFirst()
            when {
                BriefingDynamic.isArray(item) -> item.unsafeCast<Array<dynamic>>().forEach { pending.addLast(it) }
                // `typeof`, not `BriefingDynamic.isObject`: that is true for a string too, and
                // `Object.values("a")` is `["a"]` -- the walk never ended on the first text field.
                item != null && jsTypeOf(item) == "object" -> {
                    if (item.type == "route") BriefingDynamic.int(item.scenarioIndex)?.let { found += it }
                    js("Object.values")(item).unsafeCast<Array<dynamic>>().forEach { pending.addLast(it) }
                }
            }
        }
        return found
    }
}
