package org.osada.scenario

import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The objectives rail's progress model
 * (`docs/design/action-affordances-and-objectives.md` §§8, 9).
 *
 * The rules that matter are the two the panel can break silently: a flag-less victory hex must not
 * leak into normal play in any form, and the tier strip must describe the evaluator that will
 * actually grade the scenario rather than the one that reads better.
 */
class ObjectiveReportTest {
    private fun scenario(
        rows: Int = 6,
        cols: Int = 6,
    ): Scenario {
        val scenario = Scenario(null)
        scenario.map.rows = rows
        scenario.map.cols = cols
        scenario.map.allocMap()
        scenario.map.maxTurns = 12
        scenario.map.addPlayer(
            Player().apply {
                id = 0
                side = 0
            },
        )
        scenario.map.addPlayer(
            Player().apply {
                id = 1
                side = 1
            },
        )
        return scenario
    }

    private fun hex(
        scenario: Scenario,
        row: Int,
        col: Int,
        name: String = "",
        flag: Int = -1,
        owner: Int = -1,
        victorySide: Int = -1,
    ) {
        scenario.map.map!![row][col].apply {
            this.name = name
            this.flag = flag
            this.owner = owner
            this.victorySide = victorySide
        }
    }

    @Test
    fun aFlaggedVictoryHexIsRequiredAndCountedAsHeldByItsOwnersSide() {
        val scenario = scenario()
        hex(scenario, 1, 1, name = "Kalach", flag = 7, owner = 0, victorySide = 1)
        hex(scenario, 2, 2, name = "Sirki", flag = 7, owner = 1, victorySide = 1)

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertEquals(2, report.victoryTotal)
        assertEquals(1, report.victoryHeld)
        assertEquals(listOf("Kalach", "Sirki"), report.victory.map { it.name })
        assertTrue(report.victory.first { it.name == "Kalach" }.held)
        assertFalse(report.victory.first { it.name == "Sirki" }.held)
    }

    @Test
    fun anOptionalCapturePointIsListedSeparatelyAndNeverCountedTowardVictory() {
        val scenario = scenario()
        hex(scenario, 1, 1, name = "Kalach", flag = 7, owner = 0, victorySide = 1)
        hex(scenario, 3, 3, name = "Depot", flag = 7, owner = 0, victorySide = -1)

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertEquals(1, report.victoryTotal, "the optional point must not inflate the requirement")
        assertEquals(listOf("Depot"), report.optional.map { it.name })
        assertEquals(ObjectiveKind.OPTIONAL_CAPTURE, report.optional.single().kind)
    }

    @Test
    fun aHiddenVictoryHexLeaksNothingWithoutObserverMode() {
        val scenario = scenario()
        hex(scenario, 1, 1, name = "Kalach", flag = 7, owner = 0, victorySide = 1)
        hex(scenario, 4, 4, name = "Secret Ridge", flag = -1, owner = 1, victorySide = 1)

        val normal = scenario.objectiveReport(side = 0, revealHidden = false)

        assertTrue(normal.hidden.isEmpty(), "no row")
        assertEquals(1, normal.rows.size, "no count")
        assertFalse(normal.rows.any { it.name == "Secret Ridge" }, "no name")
        assertFalse(normal.rows.any { it.row == 4 && it.col == 4 }, "no coordinates")
    }

    @Test
    fun observerModeMayListTheHiddenObjective() {
        val scenario = scenario()
        hex(scenario, 4, 4, name = "Secret Ridge", flag = -1, owner = 1, victorySide = 1)

        val observer = scenario.objectiveReport(side = 0, revealHidden = true)

        assertEquals(1, observer.hidden.size)
        assertEquals(ObjectiveKind.HIDDEN_VICTORY, observer.hidden.single().kind)
        assertEquals(0, observer.victoryTotal, "a hidden hex is still not part of the visible count")
    }

    @Test
    fun anOwnerlessFlaggedHexIsNotListedAtAll() {
        // Same `flag != -1 && owner != -1` gate the map tooltips use; the rail must not become the
        // one surface that shows an authored hex nobody owns.
        val scenario = scenario()
        hex(scenario, 2, 2, name = "Unassigned", flag = 7, owner = -1, victorySide = -1)

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertTrue(report.rows.isEmpty(), "${report.rows}")
    }

    @Test
    fun theTurnTierStripComesFromTheScenariosOwnVictoryTurns() {
        val scenario = scenario()
        scenario.map.victoryTurns.addAll(listOf(4, 6, 8))
        scenario.map.turn = 5

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertFalse(report.gradedByHoldCount)
        assertEquals(
            listOf(VictoryTier.BRILLIANT to 4, VictoryTier.VICTORY to 6, VictoryTier.TACTICAL to 8),
            report.deadlines.map { it.tier to it.byTurn },
        )
        assertTrue(report.missed(report.deadlines[0]), "turn 5 is past the turn-4 deadline")
        assertFalse(report.missed(report.deadlines[1]))
    }

    @Test
    fun anAuthoredHoldCountScenarioReportsThresholdsInsteadOfDeadlines() {
        val scenario = scenario()
        scenario.map.victoryTurns.addAll(listOf(4, 6, 8))
        scenario.victoryHoldCounts = listOf(10, 9, 8)

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertTrue(report.gradedByHoldCount)
        assertEquals(
            listOf(VictoryTier.BRILLIANT to 10, VictoryTier.VICTORY to 9, VictoryTier.TACTICAL to 8),
            report.holdThresholds.map { it.tier to it.count },
        )
    }

    @Test
    fun anIncompleteHoldCountListFallsBackToTheLegacyRule() {
        // `checkTimedOutcome` itself requires all three tiers before it will use them, so a partial
        // list must not make the rail claim a hold rule the engine will ignore.
        val scenario = scenario()
        scenario.map.victoryTurns.addAll(listOf(4, 6, 8))
        scenario.victoryHoldCounts = listOf(10, 9)

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertFalse(report.gradedByHoldCount)
        assertTrue(report.holdThresholds.isEmpty())
    }

    @Test
    fun ownershipIsResolvedThroughThePlayerIdNotComparedToTheSideDirectly() {
        // A side with a support country owns hexes under more than one player id; comparing
        // `hex.owner` to the side is what used to misreport those as captured.
        val scenario = scenario()
        scenario.map.addPlayer(
            Player().apply {
                id = 2
                side = 0
            },
        )
        hex(scenario, 1, 1, name = "Support-held", flag = 7, owner = 2, victorySide = 1)

        val report = scenario.objectiveReport(side = 0, revealHidden = false)

        assertTrue(report.victory.single().held)
    }
}
