package org.osada.ui.campaignmap

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampaignMapLayoutTest {
    private val forward = CampaignGraphBuilder.build(forwardRecords())

    @Test
    fun everyForwardEdgeMovesToALaterRank() {
        val layout = CampaignMapLayout.of(forward)
        forward.edges.filterNot { it.back }.forEach { edge ->
            val from = layout.positions.getValue(edge.from).rank
            val to = layout.positions.getValue(edge.to).rank
            assertTrue(to > from, "edge ${edge.from}->${edge.to} must point forward in time ($from -> $to)")
        }
        assertEquals(0, layout.positions.getValue(0).rank)
    }

    @Test
    fun longEdgesAreRoutedThroughEveryRankTheyCross() {
        val layout = CampaignMapLayout.of(forward)
        forward.edges.withIndex().filterNot { it.value.back }.forEach { (index, edge) ->
            val route = layout.routes.getValue(index)
            val span = layout.positions.getValue(edge.to).rank - layout.positions.getValue(edge.from).rank
            assertEquals(span + 1, route.size, "edge ${edge.from}->${edge.to}")
            route.zipWithNext().forEach { (a, b) -> assertEquals(a.rank + 1, b.rank) }
        }
    }

    @Test
    fun operationsInOneRankNeverOverlap() {
        val layout = CampaignMapLayout.of(forward)
        layout.positions.values.groupBy { it.rank }.values.forEach { column ->
            val lanes = column.map { it.lane }.sorted()
            lanes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 0.999, "lanes $a and $b overlap") }
        }
        assertTrue(layout.positions.values.all { it.lane >= 0.0 })
    }

    @Test
    fun aLinearCampaignIsOneStraightLane() {
        val graph =
            CampaignGraphBuilder.build(
                listOf(record(0, "1/1/1/255"), record(1, "2/2/2/255"), record(2, "254/254/254/255")),
            )
        val layout = CampaignMapLayout.of(graph)
        val lanes =
            layout.positions.values
                .map { it.lane }
                .distinct()
        assertEquals(1, lanes.size, "lanes: $lanes")
        assertEquals(4, layout.rankCount, "three operations and the ending")
    }

    @Test
    fun theOpeningOfForwardIsStraight() {
        val layout = CampaignMapLayout.of(forward)
        val opening = (0..6).map { layout.positions.getValue(it).lane }
        assertTrue(opening.all { abs(it - opening.first()) < 1e-6 }, "opening lanes: $opening")
    }

    @Test
    fun backEdgesAreNotRouted() {
        val graph = CampaignGraphBuilder.build(listOf(record(0, "1/1/1/255"), record(1, "254/254/254/0")))
        val layout = CampaignMapLayout.of(graph)
        val backIndex = graph.edges.indexOfFirst { it.back }
        assertTrue(backIndex >= 0)
        assertTrue(backIndex !in layout.routes)
    }

    @Test
    fun poolingKeepsOrderWithTheClosestPositions() {
        assertEquals(listOf(1.0, 1.0, 3.0), CampaignMapCoordinates.poolAdjacentViolators(listOf(2.0, 0.0, 3.0)))
        assertEquals(listOf(0.0, 1.0, 2.0), CampaignMapCoordinates.poolAdjacentViolators(listOf(0.0, 1.0, 2.0)))
    }

    @Test
    fun orderingRemovesAvoidableCrossings() {
        val layered = CampaignMapRanks.layer(forward)
        val layers = CampaignMapOrdering.order(layered, forward)
        assertTrue(
            CampaignMapOrdering.crossings(layered, layers) <= 2,
            "crossings: ${CampaignMapOrdering.crossings(layered, layers)}",
        )
    }
}
