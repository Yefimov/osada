package org.osada.rules

import org.osada.GameHolder
import org.osada.model.firstPlayableTurn
import org.osada.model.isInitialDeploymentWindow
import org.osada.skipOpeningTurnIfAuthored
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * OG's `skip_first_turn` (`Scenario.skipFirstTurn`): the second player plays turn 1, the first
 * player's first turn is turn 2, and the skipped turn counts (`OG_REGISTER` §5 Q7.1).
 */
class SkipFirstTurnTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    private fun game(skip: Boolean?) =
        holderFor(world()).also {
            it.scenario!!.skipFirstTurn = skip
            GameHolder.instance = it
        }

    @Test
    fun theSecondPlayerOpensAndTheFirstStartsOnTurnTwo() {
        val game = game(skip = true)
        val map = game.scenario!!.map

        game.skipOpeningTurnIfAuthored()
        assertSame(hostile, map.currentPlayer, "player 2 plays turn 1")
        assertEquals(1, map.turn)

        map.endTurn()
        assertSame(friendly, map.currentPlayer)
        assertEquals(2, map.turn, "the skipped turn is consumed, not replayed")
    }

    @Test
    fun theFirstPlayersSetupWindowOpensOnTheirFirstRealTurn() {
        val game = game(skip = true)
        val map = game.scenario!!.map

        assertEquals(2, map.firstPlayableTurn(friendly))
        assertEquals(1, map.firstPlayableTurn(hostile))
        assertFalse(map.isInitialDeploymentWindow(friendly), "turn 1 is not the first player's")

        game.skipOpeningTurnIfAuthored()
        map.endTurn()
        assertTrue(map.isInitialDeploymentWindow(friendly))
    }

    @Test
    fun withoutTheOptionNothingChanges() {
        listOf(false, null).forEach { option ->
            val game = game(skip = option)
            val map = game.scenario!!.map

            game.skipOpeningTurnIfAuthored()

            assertSame(friendly, map.currentPlayer, "option $option")
            assertTrue(map.isInitialDeploymentWindow(friendly))
        }
    }

    @Test
    fun aSkippedPlayerWithNoPrestigeIsPaidTheTurnOneIncome() {
        val game = game(skip = true)
        friendly.prestige = 0
        friendly.prestigePerTurn = mutableListOf(0, 150, 100)

        game.skipOpeningTurnIfAuthored()

        assertEquals(150, friendly.prestige)
    }

    @Test
    fun aSkippedPlayerWithPrestigeGetsNothingExtra() {
        val game = game(skip = true)
        friendly.prestige = 40
        friendly.prestigePerTurn = mutableListOf(0, 150, 100)

        game.skipOpeningTurnIfAuthored()

        assertEquals(40, friendly.prestige)
    }

    @Test
    fun itNeverFiresTwice() {
        val game = game(skip = true)
        val map = game.scenario!!.map
        game.skipOpeningTurnIfAuthored()
        map.endTurn()

        game.skipOpeningTurnIfAuthored()

        assertSame(friendly, map.currentPlayer, "turn 2 is not an opening")
    }
}
