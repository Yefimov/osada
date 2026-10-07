package org.osada.ui.campaignmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignMapSupportTest {
    private val forward = CampaignGraphBuilder.build(forwardRecords())

    private fun edgeIndex(
        from: Int,
        to: Int,
    ) = forward.edges.indexOfFirst { it.from == from && it.to == to }

    @Test
    fun aRunTraceFollowsTheRecordedOutcomesAndChoices() {
        val outcomes =
            listOf(
                PlayedOutcome("s7.xml", OutcomeGrade.TACTICAL, "s9.xml"),
                PlayedOutcome("s9.xml", OutcomeGrade.VICTORY, "s10.xml"),
                PlayedOutcome("s15.xml", OutcomeGrade.VICTORY, "s38.xml"),
            )
        val trace = CampaignRunTrace.of(forward, outcomes, current = 38)
        assertEquals(OutcomeGrade.TACTICAL, trace.played[7])
        assertTrue(edgeIndex(7, 9) in trace.travelled)
        assertTrue(edgeIndex(9, 10) in trace.travelled)
        assertTrue(edgeIndex(15, 38) in trace.travelled, "the front choice is the edge taken")
        assertEquals(38, trace.current)
        assertNull(trace.defeatedAt)
    }

    @Test
    fun aRunThatEndedMarksItsEnding() {
        val won =
            CampaignRunTrace.of(
                forward,
                listOf(PlayedOutcome("S8.XML", OutcomeGrade.VICTORY, null)),
                current = null,
            )
        assertTrue(edgeIndex(8, CampaignGraph.finaleId(8)) in won.travelled, "file names match case-insensitively")
        val lost =
            CampaignRunTrace.of(
                forward,
                listOf(PlayedOutcome("s0.xml", OutcomeGrade.LOSE, null)),
                current = null,
            )
        assertEquals(0, lost.defeatedAt)
        assertTrue(lost.travelled.isEmpty())
    }

    @Test
    fun aFinishedRunGetsItsLastBattleFromTheCompletedRow() {
        // The stored narrative stops before the last battle; the index row supplies it.
        val stored = listOf(PlayedOutcome("s7.xml", OutcomeGrade.VICTORY, "s8.xml"))
        val won = CampaignRunTrace.finalOutcome(forward, 8, "briliant")
        assertEquals(PlayedOutcome("s8.xml", OutcomeGrade.BRILLIANT, null), won)
        val wonTrace = CampaignRunTrace.of(forward, stored + listOfNotNull(won), current = null)
        assertEquals(OutcomeGrade.BRILLIANT, wonTrace.played[8])
        assertTrue(edgeIndex(8, CampaignGraph.finaleId(8)) in wonTrace.travelled)
        val lost = CampaignRunTrace.of(forward, listOfNotNull(CampaignRunTrace.finalOutcome(forward, 16, "lose")), null)
        assertEquals(16, lost.defeatedAt)
        assertNull(CampaignRunTrace.finalOutcome(forward, 8, ""), "a row without an outcome adds nothing")
        assertNull(CampaignRunTrace.finalOutcome(forward, 17, "victory"), "an unreachable scenario adds nothing")
    }

    @Test
    fun anUnknownScenarioOrCurrentIsIgnored() {
        val trace =
            CampaignRunTrace.of(
                forward,
                listOf(PlayedOutcome("elsewhere.xml", OutcomeGrade.VICTORY, "s1.xml")),
                17,
            )
        assertTrue(trace.isEmpty, "17 is unreachable, so it cannot be where the run stands")
    }

    @Test
    fun introTextGivesHeadingYearAndSummary() {
        val intro =
            "Khalkhin Gol<br><br>2 July 1939<br><br>" +
                "Comrade Komkor, the <b>Japanese</b> have crossed&nbsp;the river."
        assertEquals("Khalkhin Gol", CampaignIntroText.heading(intro))
        assertEquals(1939, CampaignIntroText.year(intro))
        assertEquals(
            "2 July 1939\nComrade Komkor, the Japanese have crossed the river.",
            CampaignIntroText.summary(intro, "Khalkhin Gol"),
        )
        assertEquals(-73, CampaignIntroText.year("The escape (73 BC)"))
        assertNull(CampaignIntroText.year("1500 men and 300 horses"))
    }

    @Test
    fun aLongSummaryIsCutAtAWord() {
        val body = (1..120).joinToString(" ") { "word$it" }
        val summary = CampaignIntroText.summary("Title<br>$body", "Title")
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length <= 362, "length ${summary.length}")
        assertTrue(!summary.contains("Title"))
    }

    @Test
    fun geometrySwapsAxesForAnUprightPhone() {
        val layout = CampaignMapLayout.of(forward)
        val wide = CampaignMapGeometry(layout, MapOrientation.HORIZONTAL)
        val tall = CampaignMapGeometry(layout, MapOrientation.VERTICAL)
        assertTrue(wide.width > wide.height)
        assertTrue(tall.height > tall.width)
        val second = layout.positions.getValue(1)
        assertTrue(wide.box(second, false).x > wide.box(layout.positions.getValue(0), false).x)
        assertTrue(tall.box(second, false).y > tall.box(layout.positions.getValue(0), false).y)
    }

    @Test
    fun aStraightRouteIsALineAndABendIsACurve() {
        val layout = CampaignMapLayout.of(forward)
        val geometry = CampaignMapGeometry(layout, MapOrientation.HORIZONTAL)
        val straight = geometry.routePoints(layout.routes.getValue(edgeIndex(0, 1)), sourceFinale = false)
        assertEquals(1, geometry.path(straight).count { it == 'L' })
        val bent = geometry.routePoints(layout.routes.getValue(edgeIndex(7, 8)), sourceFinale = false)
        if (bent.first().y != bent.last().y) assertTrue(geometry.path(bent).contains(" C "))
        val mid = geometry.labelPoint(straight)
        assertEquals((straight[0].x + straight[1].x) / 2, mid.x)
    }
}
