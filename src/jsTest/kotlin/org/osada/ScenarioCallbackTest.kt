package org.osada

import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScenarioCallbackTest {
    private val previousGame = GameHolder.instance

    @AfterTest
    fun tearDown() {
        GameHolder.instance = previousGame
    }

    @Test
    fun oldCombatAndTurnCallbacksCannotReachRestartedMission() {
        val game =
            Game().apply {
                scenario = Scenario("forward12.xml")
                gameStarted = true
                waitUIAnimation = true
            }
        var completions = 0
        val oldCompletion = game.forCurrentScenario { completions++ }
        game.cleanup()
        assertFalse(game.gameStarted)
        assertFalse(game.waitUIAnimation)
        oldCompletion()
        assertEquals(0, completions, "Loading must not run old callbacks")

        game.scenario = Scenario("forward12.xml")
        game.gameStarted = true
        game.waitUIAnimation = true
        oldCompletion()
        assertEquals(0, completions, "Matching file names do not make this the same battle")
        assertEquals(true, game.waitUIAnimation, "Old completions must not release a new animation")
        game.forCurrentScenario { completions++ }()
        assertEquals(1, completions, "The restarted mission's own callbacks must still run")
    }
}
