package org.osada.ui.campaignmap

/** One step of a drawn edge between two adjacent ranks. [edge] indexes `CampaignGraph.edges`. */
internal data class LayerSegment(
    val upper: Int,
    val lower: Int,
    val edge: Int,
)

/**
 * The graph cut into ranks, with every edge longer than one rank broken into a chain of virtual
 * vertices (negative ids). Those virtual vertices are what keep a long edge in a lane of its own
 * instead of being drawn straight through the operations it passes.
 */
internal class LayeredGraph(
    val rankOf: Map<Int, Int>,
    val segments: List<LayerSegment>,
    /** Edge index -> the vertices it passes through, source first, target last. */
    val chains: Map<Int, List<Int>>,
) {
    val rankCount: Int = (rankOf.values.maxOrNull() ?: -1) + 1

    fun isVirtual(vertex: Int): Boolean = vertex < 0
}

/** Longest-path ranking: every forward edge points to a later rank, so time reads left to right. */
internal object CampaignMapRanks {
    /** Kahn's order over [edges]; vertices on no path from the rest keep their input order at the end. */
    fun topologicalOrder(
        ids: List<Int>,
        edges: List<CampaignMapEdge>,
    ): List<Int> {
        val indegree = ids.associateWithTo(mutableMapOf()) { 0 }
        edges.forEach { edge -> indegree[edge.to]?.let { indegree[edge.to] = it + 1 } }
        val queue = ArrayDeque(ids.filter { indegree[it] == 0 })
        val order = mutableListOf<Int>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order += id
            edges.filter { it.from == id }.forEach { edge ->
                val left = (indegree[edge.to] ?: return@forEach) - 1
                indegree[edge.to] = left
                if (left == 0) queue.addLast(edge.to)
            }
        }
        return order + ids.filterNot { it in order }
    }

    fun layer(graph: CampaignGraph): LayeredGraph {
        val forward = graph.edges.withIndex().filterNot { it.value.back }
        val ranks = mutableMapOf<Int, Int>()
        topologicalOrder(graph.nodes.map { it.id }, forward.map { it.value }).forEach { id ->
            val rank = ranks.getOrPut(id) { 0 }
            forward.filter { it.value.from == id }.forEach { (_, edge) ->
                ranks[edge.to] = maxOf(ranks[edge.to] ?: 0, rank + 1)
            }
        }
        val segments = mutableListOf<LayerSegment>()
        val chains = mutableMapOf<Int, List<Int>>()
        var nextVirtual = -1
        forward.forEach { (index, edge) ->
            val chain = mutableListOf(edge.from)
            for (rank in ranks.getValue(edge.from) + 1 until ranks.getValue(edge.to)) {
                val virtual = nextVirtual--
                ranks[virtual] = rank
                chain += virtual
            }
            chain += edge.to
            chain.zipWithNext().forEach { (upper, lower) -> segments += LayerSegment(upper, lower, index) }
            chains[index] = chain
        }
        return LayeredGraph(ranks, segments, chains)
    }
}
