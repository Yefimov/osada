package org.osada.ui.campaignmap

import org.osada.scenario.CampaignRouting
import org.osada.scenario.CampaignTransition
import org.osada.scenario.CampaignTransitions

/**
 * Turns flattened campaign records into the graph the operations map draws.
 *
 * It models what the ENGINE does, not what the JSON appears to say: every transition is resolved by
 * [CampaignTransitions], the resolver `Game.continueCampaign` commits with. So a committed briefing
 * route replaces exactly the grades [CampaignRouting] lets it replace, a 254/255 `goto` ends the
 * campaign (in victory or defeat according to the grade -- the sentinel itself is never read), a
 * target the engine refuses (negative, or past the list and not a sentinel) is not drawn at all,
 * and the imported `gotoplayed` pair is ignored because the engine ignores it.
 */
internal object CampaignGraphBuilder {
    /**
     * One raw transition before grouping: [grade] for an outcome branch, [choice] for a route
     * choice. [target] is null where the campaign ends.
     */
    private data class Transition(
        val target: Int?,
        val grade: OutcomeGrade?,
        val choice: ChoiceRoute?,
    )

    fun build(records: List<CampaignScenarioRecord>): CampaignGraph {
        if (records.isEmpty()) return CampaignGraph(emptyList(), emptyList(), emptyList())
        val count = records.size
        val nodes = mutableListOf<CampaignMapNode>()
        val edges = mutableListOf<CampaignMapEdge>()
        records.forEach { record ->
            val transitions = transitionsOf(record, count)
            val finale = CampaignGraph.finaleId(record.index)
            // A grade that ends the campaign is a defeat when it is the loss and an ending otherwise,
            // as `Game.continueCampaign` shows it.
            val drawn =
                transitions
                    .filter { it.target != record.index && !(it.target == null && it.grade == OutcomeGrade.LOSE) }
                    .map { (it.target ?: finale) to it }
            nodes +=
                CampaignMapNode(
                    id = record.index,
                    record = record,
                    defeatGrades =
                        transitions
                            .filter { it.target == null && it.grade == OutcomeGrade.LOSE }
                            .mapNotNullTo(mutableSetOf()) { it.grade },
                    retryGrades =
                        transitions.filter { it.target == record.index }.mapNotNullTo(
                            mutableSetOf(),
                        ) { it.grade },
                )
            if (drawn.any { it.first == finale }) {
                nodes +=
                    CampaignMapNode(id = finale, record = null, finaleOf = record.index)
            }
            edges += group(record, drawn)
        }
        return prune(nodes, edges, count)
    }

    /**
     * Every transition the engine can take out of [record]. Choices offered unconditionally replace
     * the outcome branches the routing rule lets them replace; a conditional choice may never be
     * offered, so the authored branches stay possible beside it. A choice is resolved as the
     * victory it replaces.
     */
    private fun transitionsOf(
        record: CampaignScenarioRecord,
        count: Int,
    ): List<Transition> {
        val routed = record.choices.isNotEmpty() && record.choices.none { it.conditional }
        val outcomes =
            OutcomeGrade.entries
                .filterNot { routed && CampaignRouting.routeApplies(it.key) }
                .mapNotNull { grade ->
                    val goto = record.gotos[grade] ?: return@mapNotNull null
                    targetOf(CampaignTransitions.resolve(count, record.index, grade.key, goto))
                        ?.let { Transition(it.target, grade, null) }
                }
        val choices =
            record.choices.mapNotNull { choice ->
                val resolved =
                    CampaignTransitions.resolve(
                        count,
                        record.index,
                        CampaignRouting.ROUTED_GRADE,
                        null,
                        choice.target,
                    )
                targetOf(resolved)?.let { Transition(it.target, null, choice) }
            }
        return outcomes + choices
    }

    /** Where a transition lands on the map: a record index, or null for the campaign end. */
    private data class Landing(
        val target: Int?,
    )

    /** Null when the engine refuses [transition]: no box is drawn for it at all. */
    private fun targetOf(transition: CampaignTransition): Landing? =
        when (transition) {
            is CampaignTransition.NextScenario -> Landing(transition.index)
            is CampaignTransition.CampaignEnd -> Landing(null)
            is CampaignTransition.NeedsDecision -> targetOf(transition.withoutDecision)
            is CampaignTransition.InvalidData -> null
        }

    /**
     * Outcome transitions sharing a target become one edge; every choice stays its own edge. Each
     * of [drawn] is paired with the node it is drawn to (the finale box for a campaign end).
     */
    private fun group(
        record: CampaignScenarioRecord,
        drawn: List<Pair<Int, Transition>>,
    ): List<CampaignMapEdge> {
        val winsHere = OutcomeGrade.WINS.filter { grade -> drawn.any { it.second.grade == grade } }.toSet()
        val byTarget =
            drawn
                .filter { it.second.grade != null }
                .groupBy({ it.first }, { it.second.grade!! })
                .map { (target, grades) ->
                    val set = grades.toSet()
                    CampaignMapEdge(record.index, target, set, null, styleOf(set, winsHere), back = false)
                }
        val choices =
            drawn.mapNotNull { (target, transition) ->
                transition.choice?.let {
                    CampaignMapEdge(record.index, target, emptySet(), it, EdgeStyle.CHOICE, back = false)
                }
            }
        return byTarget + choices
    }

    /** [winsHere]: every victory grade the scenario routes by outcome (none when a choice decides). */
    private fun styleOf(
        grades: Set<OutcomeGrade>,
        winsHere: Set<OutcomeGrade>,
    ): EdgeStyle {
        val wins = grades - OutcomeGrade.LOSE
        return when {
            wins.isEmpty() -> EdgeStyle.LOSE
            wins == winsHere -> EdgeStyle.MAIN
            OutcomeGrade.BRILLIANT in wins -> EdgeStyle.BRILLIANT
            OutcomeGrade.VICTORY in wins -> EdgeStyle.VICTORY
            else -> EdgeStyle.TACTICAL
        }
    }

    /** Drops what no transition reaches from the first scenario, then marks the cycle-closing edges. */
    private fun prune(
        nodes: List<CampaignMapNode>,
        edges: List<CampaignMapEdge>,
        count: Int,
    ): CampaignGraph {
        val reached = reachable(edges)
        val keptNodes = nodes.filter { it.id in reached }
        val keptEdges = edges.filter { it.from in reached }
        val back = backEdges(keptEdges)
        return CampaignGraph(
            nodes = keptNodes,
            edges = keptEdges.mapIndexed { index, edge -> if (index in back) edge.copy(back = true) else edge },
            unreachable = (0 until count).filter { it !in reached },
        )
    }

    private fun reachable(edges: List<CampaignMapEdge>): Set<Int> {
        val seen = mutableSetOf(CampaignGraph.START)
        val queue = ArrayDeque(listOf(CampaignGraph.START))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            edges.filter { it.from == id && seen.add(it.to) }.forEach { queue.addLast(it.to) }
        }
        return seen
    }

    /** Indexes of edges that close a cycle in a depth-first walk from the start (iterative: no recursion limit). */
    private fun backEdges(edges: List<CampaignMapEdge>): Set<Int> {
        val outgoing = edges.withIndex().groupBy { it.value.from }
        val onStack = mutableSetOf<Int>()
        val done = mutableSetOf<Int>()
        val back = mutableSetOf<Int>()
        val stack = ArrayDeque<Pair<Int, Iterator<IndexedValue<CampaignMapEdge>>>>()
        stack.addLast(
            CampaignGraph.START to outgoing[CampaignGraph.START].orEmpty().sortedBy { it.value.to }.iterator(),
        )
        onStack += CampaignGraph.START
        while (stack.isNotEmpty()) {
            val (id, iterator) = stack.last()
            if (!iterator.hasNext()) {
                stack.removeLast()
                onStack -= id
                done += id
                continue
            }
            val (index, edge) = iterator.next()
            when (edge.to) {
                in onStack -> back += index
                !in done -> {
                    onStack += edge.to
                    stack.addLast(edge.to to outgoing[edge.to].orEmpty().sortedBy { it.value.to }.iterator())
                }
            }
        }
        return back
    }
}
