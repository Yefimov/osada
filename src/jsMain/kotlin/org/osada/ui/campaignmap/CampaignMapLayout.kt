package org.osada.ui.campaignmap

/** A position in layout space: [rank] is the time axis, [lane] the across-axis, both unitless. */
internal data class LayoutPoint(
    val rank: Int,
    val lane: Double,
)

/**
 * Where every operation and every drawn edge goes, independent of screen orientation: the view
 * turns ranks into columns on a wide screen and into rows on a phone held upright.
 */
internal class CampaignMapLayout(
    val positions: Map<Int, LayoutPoint>,
    /** Edge index -> its route, source first and target last; back edges have none. */
    val routes: Map<Int, List<LayoutPoint>>,
    val rankCount: Int,
    val laneSpan: Double,
) {
    companion object {
        fun of(graph: CampaignGraph): CampaignMapLayout {
            val layered = CampaignMapRanks.layer(graph)
            val layers = CampaignMapOrdering.order(layered, graph)
            val lanes = CampaignMapCoordinates.assign(layered, layers)
            val point = { vertex: Int -> LayoutPoint(layered.rankOf.getValue(vertex), lanes.getValue(vertex)) }
            return CampaignMapLayout(
                positions = graph.nodes.associate { it.id to point(it.id) },
                routes = layered.chains.mapValues { (_, chain) -> chain.map(point) },
                rankCount = layered.rankCount,
                laneSpan = lanes.values.maxOrNull() ?: 0.0,
            )
        }
    }
}
