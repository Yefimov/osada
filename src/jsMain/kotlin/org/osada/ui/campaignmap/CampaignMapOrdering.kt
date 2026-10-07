package org.osada.ui.campaignmap

/**
 * Orders the vertices inside each rank so edges cross as little as possible: a depth-first seed
 * that follows the main line first (so the trunk starts on one lane), then alternating barycenter
 * sweeps, keeping whichever ordering had the fewest crossings.
 */
internal object CampaignMapOrdering {
    private const val SWEEPS = 12

    fun order(
        layered: LayeredGraph,
        graph: CampaignGraph,
    ): List<List<Int>> {
        var layers = seed(layered, graph)
        var best = layers
        var bestCrossings = crossings(layered, best)
        repeat(SWEEPS) { sweep ->
            val downward = sweep % 2 == 0
            layers = sweepOnce(layered, layers, downward)
            val count = crossings(layered, layers)
            if (count < bestCrossings) {
                best = layers
                bestCrossings = count
            }
        }
        return best
    }

    /** Depth-first discovery order from the start, main-line edges first, then by grade and target. */
    private fun seed(
        layered: LayeredGraph,
        graph: CampaignGraph,
    ): List<List<Int>> {
        val below = layered.segments.groupBy { it.upper }
        val discovered = LinkedHashSet<Int>()
        val stack = ArrayDeque(listOf(CampaignGraph.START))
        while (stack.isNotEmpty()) {
            val vertex = stack.removeLast()
            if (!discovered.add(vertex)) continue
            below[vertex]
                .orEmpty()
                .sortedWith(compareBy({ graph.edges[it.edge].style.ordinal }, { graph.edges[it.edge].to }))
                .asReversed()
                .forEach { stack.addLast(it.lower) }
        }
        layered.rankOf.keys
            .filterNot { it in discovered }
            .forEach { discovered += it }
        val rank = layered.rankOf
        return (0 until layered.rankCount).map { r -> discovered.filter { rank[it] == r } }
    }

    private fun sweepOnce(
        layered: LayeredGraph,
        layers: List<List<Int>>,
        downward: Boolean,
    ): List<List<Int>> {
        val result = layers.map { it.toMutableList() }
        val ranks = if (downward) 1 until result.size else (result.size - 2 downTo 0)
        for (rank in ranks) {
            val neighbourRank = if (downward) rank - 1 else rank + 1
            val position = result[neighbourRank].withIndex().associate { it.value to it.index.toDouble() }
            val current = result[rank].withIndex().associate { it.value to it.index.toDouble() }
            val key =
                result[rank].associateWith { vertex ->
                    val neighbours =
                        layered.segments.mapNotNull { segment ->
                            when {
                                downward && segment.lower == vertex -> position[segment.upper]
                                !downward && segment.upper == vertex -> position[segment.lower]
                                else -> null
                            }
                        }
                    if (neighbours.isEmpty()) current.getValue(vertex) else neighbours.average()
                }
            // Stable sort: equal barycenters keep their current relative order.
            result[rank].sortWith(compareBy { key.getValue(it) })
        }
        return result
    }

    fun crossings(
        layered: LayeredGraph,
        layers: List<List<Int>>,
    ): Int {
        val position = layers.flatMap { layer -> layer.withIndex().map { it.value to it.index } }.toMap()
        val byRank = layered.segments.groupBy { layered.rankOf.getValue(it.upper) }
        return byRank.values.sumOf { segments ->
            var count = 0
            for (i in segments.indices) {
                for (j in i + 1 until segments.size) {
                    val a = segments[i]
                    val b = segments[j]
                    val upper = position.getValue(a.upper) - position.getValue(b.upper)
                    val lower = position.getValue(a.lower) - position.getValue(b.lower)
                    if (upper * lower < 0) count++
                }
            }
            count
        }
    }
}
