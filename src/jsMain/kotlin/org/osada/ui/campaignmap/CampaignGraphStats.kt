package org.osada.ui.campaignmap

/** The headline numbers the campaign screen and the map header show for one campaign route. */
internal data class CampaignGraphStats(
    val operations: Int,
    /** Fewest / most operations played on a way to a campaign ending; null when no ending is reachable. */
    val shortest: Int?,
    val longest: Int?,
    /** Scenarios whose victories (or briefing choice) can lead to more than one place. */
    val forks: List<Int>,
    val finales: Int,
    /** Scenarios whose defeat sends the campaign to ANOTHER operation. A defeat that only replays
     *  the same one is not listed: every defeat can be replayed from its checkpoint anyway. */
    val survivableDefeats: List<Int>,
) {
    companion object {
        /**
         * Path lengths are measured over the forward edges only: a cycle (a replay, or a defeat that
         * returns to an earlier operation) can repeat any number of times, so it has no "longest".
         */
        fun of(graph: CampaignGraph): CampaignGraphStats {
            val scenarios = graph.nodes.filterNot { it.isFinale }
            val (shortest, longest) = pathLengths(graph)
            return CampaignGraphStats(
                operations = scenarios.size,
                shortest = shortest,
                longest = longest,
                forks = scenarios.map { it.id }.filter { isFork(graph, it) },
                finales = graph.nodes.count { it.isFinale },
                survivableDefeats =
                    scenarios
                        .filter { node -> graph.outgoing(node.id).any { OutcomeGrade.LOSE in it.grades } }
                        .map { it.id },
            )
        }

        private fun isFork(
            graph: CampaignGraph,
            id: Int,
        ): Boolean =
            graph
                .outgoing(id)
                .filter { it.choice != null || (it.grades - OutcomeGrade.LOSE).isNotEmpty() }
                .map { it.to }
                .distinct()
                .size > 1

        private fun pathLengths(graph: CampaignGraph): Pair<Int?, Int?> {
            val forward = graph.edges.filterNot { it.back }
            val order = CampaignMapRanks.topologicalOrder(graph.nodes.map { it.id }, forward)
            val shortest = mutableMapOf(CampaignGraph.START to 1)
            val longest = mutableMapOf(CampaignGraph.START to 1)
            order.forEach { id ->
                val low = shortest[id] ?: return@forEach
                val high = longest.getValue(id)
                forward.filter { it.from == id }.forEach { edge ->
                    val step = if (graph.node(edge.to)?.isFinale == true) 0 else 1
                    shortest[edge.to] = minOf(shortest[edge.to] ?: Int.MAX_VALUE, low + step)
                    longest[edge.to] = maxOf(longest[edge.to] ?: 0, high + step)
                }
            }
            val finales = graph.nodes.filter { it.isFinale }.map { it.id }
            return finales.mapNotNull { shortest[it] }.minOrNull() to finales.mapNotNull { longest[it] }.maxOrNull()
        }
    }
}
