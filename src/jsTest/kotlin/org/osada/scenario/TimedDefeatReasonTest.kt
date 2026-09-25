package org.osada.scenario

import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `forward4` (Odessa): the counterattack needed all three Romanian objectives and the player took
 * two. The defeat now says so, instead of "out of turns" followed by the author's "Odessa is lost".
 */
class TimedDefeatReasonTest {
    @BeforeTest
    fun setup() = installEnglishUiBundleForTests()

    @Test
    fun aCaptureMissionNamesTheObjectivesStillNotTaken() {
        val scenario = scenario()
        scenario.map.sidesVictoryHexes[SOVIET].add(Cell(3, 1))

        assertEquals(
            "You ran out of turns. Victory needed every objective of this mission taken; " +
                "objectives still not taken: 1.<br>",
            TimedDefeatReason.text(scenario, SOVIET),
        )
    }

    @Test
    fun aHoldMissionNamesWhatWasHeldAndWhatWasNeeded() {
        val scenario = scenario()
        scenario.victoryHoldCountsSide1 = listOf(3, 2, 1)
        // Nothing owned: 0 held against a tactical threshold of 1.

        assertEquals(
            "You ran out of turns. You held 0 objectives; victory needed at least 1.<br>",
            TimedDefeatReason.text(scenario, SOVIET),
        )
    }

    private fun scenario(): Scenario {
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
            }
        map.addPlayer(
            Player().apply {
                id = 0
                side = SOVIET
            },
        )
        return Scenario(null).apply { this.map = map }
    }

    private companion object {
        const val SOVIET = 1
    }
}
