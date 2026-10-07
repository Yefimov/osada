package org.osada.ui.campaignmap

import org.osada.scenario.CampaignRouting

/**
 * Turns flattened campaign records into the graph the operations map draws.
 *
 * It models what the ENGINE does, not what the JSON appears to say: a committed briefing route
 * replaces exactly the grades [CampaignRouting] lets it replace, a `goto` past the scenario list
 * ends the campaign (in victory or defeat according to the grade, as `Game.continueCampaign`
 * decides — the 254/255 sentinel itself is never read), and the imported `gotoplayed` pair is
 * ignored because `Campaign.loadNextScenario` ignores it.
 */
internal object CampaignGraphBuilder {
    /** One raw transition before grouping: [grade] for an outcome branch, [choice] for a route choice. */
    private data class Transition(
        val target: Int,
        val grade: OutcomeGrade?,
        val choice: ChoiceRoute?,
    )

    fun build(records: List<CampaignScenarioRecord>): CampaignGraph {
        if (records.isEmpty()) return CampaignGraph(emptyList(), emptyList(), emptyList())
        val count = records.size
        val nodes = mutableListOf<CampaignMapNode>()
        val edges = mutableListOf<CampaignMapEdge>()
        records.forEach { record ->
            val transitions = transitionsOf(record)
            val ends = transitions.filter { it.target !in 0 until count }
            val finale = CampaignGraph.finaleId(record.index)
            // A grade that ends the campaign is a defeat when it is the loss and an ending otherwise,
            // as `Game.continueCampaign` shows it -- whichever sentinel the JSON happened to use.
            val drawn =
                transitions
                    .filter {
                        it.target != record.index &&
                            !(it.target !in 0 until count && it.grade == OutcomeGrade.LOSE)
                    }.map { if (it.target in 0 until count) it else it.copy(target = finale) }
            nodes +=
                CampaignMapNode(
                    id = record.index,
                    record = record,
                    defeatGrades =
                        ends.mapNotNullTo(mutableSetOf()) {
                            it.grade?.takeIf { g ->
                                g == OutcomeGrade.LOSE
                            }
                        },
                    retryGrades =
                        transitions.filter { it.target == record.index }.mapNotNullTo(
                            mutableSetOf(),
                        ) { it.grade },
                )
            if (drawn.any { it.target == finale }) {
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
     * offered, so the authored branches stay possible beside it.
     */
    private fun transitionsOf(record: CampaignScenarioRecord): List<Transition> {
        val routed = record.choices.isNotEmpty() && record.choices.none { it.conditional }
        val outcomes =
            OutcomeGrade.entries
                .filterNot { routed && CampaignRouting.routeApplies(it.key) }
                .mapNotNull { grade -> record.gotos[grade]?.let { Transition(it, grade, null) } }
        return outcomes + record.choices.map { Transition(it.target, null, it) }
    }

    /** Outcome transitions sharing a target become one edge; every choice stays its own edge. */
    private fun group(
        record: CampaignScenarioRecord,
        transitions: List<Transition>,
    ): List<CampaignMapEdge> {
        val winsHere = OutcomeGrade.WINS.filter { grade -> transitions.any { it.grade == grade } }.toSet()
        val byTarget =
            transitions
                .filter { it.grade != null }
                .groupBy({ it.target }, { it.grade!! })
                .map { (target, grades) ->
                    val set = grades.toSet()
                    CampaignMapEdge(record.index, target, set, null, styleOf(set, winsHere), back = false)
                }
        val choices =
            transitions.mapNotNull { transition ->
                transition.choice?.let {
                    CampaignMapEdge(record.index, transition.target, emptySet(), it, EdgeStyle.CHOICE, back = false)
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
