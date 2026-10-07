package org.osada.ui.campaignmap

import org.osada.scenario.CampaignRouting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A campaign record with outcome gotos brilliant/victory/tactical/lose, as campaign JSON writes them. */
internal fun record(
    index: Int,
    gotos: String,
    choices: List<ChoiceRoute> = emptyList(),
): CampaignScenarioRecord {
    val g = gotos.split('/').map { it.toInt() }
    return CampaignScenarioRecord(
        index = index,
        scenarioFile = "s$index.xml",
        title = "Scenario $index",
        year = null,
        summary = "",
        gotos =
            mapOf(
                OutcomeGrade.BRILLIANT to g[0],
                OutcomeGrade.VICTORY to g[1],
                OutcomeGrade.TACTICAL to g[2],
                OutcomeGrade.LOSE to g[3],
            ),
        choices = choices,
    )
}

internal fun choice(
    id: String,
    target: Int,
    conditional: Boolean = false,
) = ChoiceRoute(id, id, "", target, conditional)

/**
 * `forward.json`'s transition table, outcome for outcome, with the two briefing front choices of
 * Kharkov (15) and Prokhorovka (16). Regenerate it from the JSON if the campaign's routing changes.
 */
internal fun forwardRecords(): List<CampaignScenarioRecord> {
    val table =
        (
            "1/1/1/255 2/2/2/255 3/3/3/3 4/4/4/4 5/5/5/255 6/6/6/6 7/7/7/7 8/10/9/9 254/254/254/10 " +
                "10/10/10/255 11/11/11/255 12/12/12/12 13/13/13/255 14/14/14/255 15/15/15/255 23/23/23/16 " +
                "23/23/23/255 41/41/41/255 19/19/19/255 20/20/20/255 21/21/21/255 22/22/22/255 " +
                "254/254/254/255 24/24/24/255 25/25/25/255 26/26/26/255 27/33/28/255 254/254/254/255 " +
                "29/29/29/255 30/30/30/255 31/31/31/255 32/32/32/255 254/254/254/255 34/34/34/255 " +
                "35/35/35/255 36/36/36/255 37/37/37/255 254/254/254/255 39/39/39/255 40/40/40/255 " +
                "41/41/41/255 42/42/42/255 43/43/43/255 22/22/22/255"
        ).split(' ')
    val fronts = { prefix: String -> listOf(choice("$prefix.0", 23), choice("$prefix.1", 38), choice("$prefix.2", 18)) }
    return table.mapIndexed { index, gotos ->
        val choices =
            when (index) {
                15 -> fronts("forward15")
                16 -> fronts("forward16")
                else -> emptyList()
            }
        record(index, gotos, choices)
    }
}

class CampaignGraphTest {
    private fun CampaignGraph.edge(
        from: Int,
        to: Int,
    ) = edges.single { it.from == from && it.to == to }

    @Test
    fun aCommittedRouteNeverOverridesALoss() {
        assertNull(CampaignRouting.effectiveRoute("lose", 23))
        assertEquals(23, CampaignRouting.effectiveRoute("victory", 23))
        assertEquals(23, CampaignRouting.effectiveRoute("briliant", 23))
        assertNull(CampaignRouting.effectiveRoute("tactical", null))
    }

    @Test
    fun forwardHidesTheReplacedChoiceScenarioAndKeepsProkhorovka() {
        val graph = CampaignGraphBuilder.build(forwardRecords())
        assertEquals(listOf(17), graph.unreachable, "the OG 'Choice of Front' scenario is replaced by the dialogue")
        assertTrue(graph.node(16) != null, "losing Kharkov still leads to Prokhorovka")
        assertEquals(EdgeStyle.LOSE, graph.edge(15, 16).style)
        assertEquals(setOf(OutcomeGrade.LOSE), graph.edge(15, 16).grades)
    }

    @Test
    fun aBriefingChoiceReplacesEveryVictoryButNotTheLoss() {
        val graph = CampaignGraphBuilder.build(forwardRecords())
        val fromKharkov = graph.outgoing(15)
        assertEquals(setOf(23, 38, 18, 16), fromKharkov.map { it.to }.toSet())
        assertEquals(3, fromKharkov.count { it.style == EdgeStyle.CHOICE })
        // Prokhorovka's own loss ends the campaign: no edge, a defeat marker instead.
        assertEquals(setOf(OutcomeGrade.LOSE), graph.node(16)!!.defeatGrades)
        assertTrue(graph.outgoing(16).all { it.style == EdgeStyle.CHOICE })
    }

    @Test
    fun differentVictoriesGetTheirOwnLabelledEdges() {
        val graph = CampaignGraphBuilder.build(forwardRecords())
        assertEquals(EdgeStyle.BRILLIANT, graph.edge(7, 8).style)
        assertEquals(EdgeStyle.VICTORY, graph.edge(7, 10).style)
        val moscow = graph.edge(7, 9)
        assertEquals(EdgeStyle.TACTICAL, moscow.style)
        assertEquals(setOf(OutcomeGrade.TACTICAL, OutcomeGrade.LOSE), moscow.grades)
        assertEquals(EdgeStyle.MAIN, graph.edge(0, 1).style)
        assertEquals(EdgeStyle.MAIN, graph.edge(2, 3).style, "a loss that follows the victories is still the main line")
    }

    @Test
    fun aVictoryPastTheListIsAnEndingAndALossPastItIsADefeat() {
        val graph = CampaignGraphBuilder.build(forwardRecords())
        val finale = CampaignGraph.finaleId(8)
        assertEquals(8, graph.node(finale)!!.finaleOf)
        assertEquals(EdgeStyle.MAIN, graph.edge(8, finale).style)
        assertEquals(EdgeStyle.LOSE, graph.edge(8, 10).style)
        assertTrue(graph.node(0)!!.defeatGrades == setOf(OutcomeGrade.LOSE))
        assertTrue(graph.node(2)!!.defeatGrades.isEmpty())
    }

    @Test
    fun forwardStatsMatchItsRoutes() {
        val stats = CampaignGraphStats.of(CampaignGraphBuilder.build(forwardRecords()))
        assertEquals(43, stats.operations)
        assertEquals(9, stats.shortest, "a brilliant Tula ends the war after Closing the Ring")
        assertEquals(25, stats.longest)
        assertEquals(listOf(7, 15, 16, 26), stats.forks)
        assertEquals(5, stats.finales)
        assertEquals(listOf(2, 3, 5, 6, 7, 8, 11, 15), stats.survivableDefeats)
    }

    @Test
    fun aConditionalChoiceLeavesTheAuthoredVictoriesPossible() {
        val records =
            listOf(
                record(0, "1/1/1/255", listOf(choice("c", 2, conditional = true))),
                record(1, "254/254/254/255"),
                record(2, "254/254/254/255"),
            )
        val graph = CampaignGraphBuilder.build(records)
        assertEquals(setOf(1, 2), graph.outgoing(0).map { it.to }.toSet())
    }

    @Test
    fun replaysAndReturnsAreMarkersNotForwardEdges() {
        val records =
            listOf(
                record(0, "1/1/1/0"),
                record(1, "2/2/2/0"),
                record(2, "254/254/254/255"),
            )
        val graph = CampaignGraphBuilder.build(records)
        assertEquals(setOf(OutcomeGrade.LOSE), graph.node(0)!!.retryGrades)
        val back = graph.edge(1, 0)
        assertTrue(back.back)
        assertFalse(graph.edge(0, 1).back)
        val stats = CampaignGraphStats.of(graph)
        assertEquals(3, stats.shortest)
        assertEquals(3, stats.longest)
        assertEquals(listOf(1), stats.survivableDefeats, "a defeat that only replays is not listed")
    }

    @Test
    fun aLinearCampaignHasNoForks() {
        val graph = CampaignGraphBuilder.build(listOf(record(0, "1/1/1/255"), record(1, "254/254/254/255")))
        val stats = CampaignGraphStats.of(graph)
        assertTrue(stats.forks.isEmpty())
        assertEquals(1, stats.finales)
        assertEquals(2, stats.shortest)
    }
}
