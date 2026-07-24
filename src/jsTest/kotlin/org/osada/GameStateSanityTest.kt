package org.osada

import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.setHex
import org.osada.scenario.Scenario
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Sanity tests for Game and GameState bootstrap without requiring loaded scenario/equipment data.
 */
class GameStateSanityTest {
    @BeforeTest
    fun setup() {
        // Scenario constructor reads the global scenariolist array.
        // Provide an empty one so tests can instantiate Scenario without loading JS resources.
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
    }

    @Test
    fun gameCanBeInstantiated() {
        val game = Game()
        assertNotNull(game)
        assertEquals(Game.defaultScenario, game.scenario?.file ?: Game.defaultScenario)
    }

    @Test
    fun gameStateCanBeCreated() {
        val game = Game()
        val state = GameState(game)
        assertNotNull(state)
    }

    @Test
    fun majorVersionIsTwoComponents() {
        // GameState uses VERSION.split(".").take(2).joinToString(".")
        val major = VERSION.split(".").take(2).joinToString(".")
        assertEquals("3.3", major)
    }

    @Test
    fun scenarioCtorStoresFileName() {
        val scenario = Scenario("tutorial.xml")
        assertEquals("tutorial.xml", scenario.file)
    }

    /** Regression: GameMap.setHex(row, col, hex) must STORE the hex in the grid. It used to only
     *  register the hex's units, so a restored save kept allocMap()'s blank hexes — the renderer
     *  walks the grid, and the restored game came up with terrain but NO units (2026-07-14). */
    @Test
    fun setHexWithExplicitHexReplacesGridCell() {
        val map = org.osada.model.GameMap()
        map.rows = 3
        map.cols = 3
        map.allocMap()
        map.addPlayer(
            org.osada.model.Player().apply {
                id = 0
                side = 0
                country = 0
            },
        )
        val hex = org.osada.model.Hex(1, 2)
        hex.terrain = TerrainType.CITY.value
        map.setHex(1, 2, hex)
        val stored = map.map?.get(1)?.get(2)
        assertEquals(true, stored === hex, "setHex must place the provided hex into the grid")
        assertEquals(TerrainType.CITY.value, stored?.terrain)
    }
}
