package org.osada

import org.osada.model.Equipment
import org.osada.model.getPlayers
import org.osada.model.resetEquipment
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [GameState] using a real save file as a fixture.
 */
class GameStateIntegrationTest {
    @Test
    fun loadBizerteSaveRestoresScenarioPlayersAndMap(): Promise<Unit> {
        Equipment.resetEquipment()
        Equipment.asyncLoad = false
        js(
            """
            if (typeof window.scenariolist === 'undefined') {
                window.scenariolist = [
                    ['Test Theater'],
                    ['bizerte.xml','Bizerte','Bizerte scenario',[],[],'eqp-adlerkorps']
                ];
            }
        """,
        )

        val game = Game()
        game.state = GameState(game)

        return Promise { resolve, reject ->
            val ok =
                game.state?.restoreFromString(BIZERTE_SAVE_JSON) {
                    try {
                        val scenario = game.scenario
                        assertNotNull(scenario)
                        assertEquals("Bizerte", scenario.name)
                        assertEquals("eqp-adlerkorps", scenario.eqp)
                        assertEquals(16, scenario.maxTurns)
                        assertEquals(1, scenario.map.turn)
                        assertEquals(24, scenario.map.rows)
                        assertEquals(28, scenario.map.cols)

                        val players = scenario.map.getPlayers()
                        assertEquals(2, players.size)
                        assertEquals(8684, players[0].prestige)
                        // eqp-adlerkorps idx 9 -> eqp-united id 9 (coincidentally unchanged)
                        assertEquals(9, players[0].country)
                        assertEquals(0, players[0].type.value)
                        assertEquals(2000, players[1].prestige)
                        // eqp-adlerkorps idx 7 -> eqp-united id 7 (coincidentally unchanged)
                        assertEquals(7, players[1].country)

                        assertTrue(scenario.reinforcements.isNotEmpty())
                        assertEquals(listOf(9, 12, 16), scenario.map.victoryTurns)
                        assertNotNull(scenario.map.currentPlayer)

                        resolve(Unit)
                    } catch (e: Throwable) {
                        reject(e)
                    }
                }
            if (ok != true) reject(Throwable("restoreFromString returned false"))
        }
    }

    @Test
    fun loadBizerteSaveRoundTrips(): Promise<Unit> {
        Equipment.resetEquipment()
        Equipment.asyncLoad = false
        js(
            """
            if (typeof window.scenariolist === 'undefined') {
                window.scenariolist = [
                    ['Test Theater'],
                    ['bizerte.xml','Bizerte','Bizerte scenario',[],[],'eqp-adlerkorps']
                ];
            }
        """,
        )

        val game = Game()
        game.state = GameState(game)

        return Promise { resolve, reject ->
            val ok =
                game.state?.restoreFromString(BIZERTE_SAVE_JSON) {
                    try {
                        val exported = game.state?.exportGameState()
                        assertNotNull(exported)
                        assertTrue(exported.contains("\"name\":\"Bizerte\""))
                        assertTrue(exported.contains("\"rows\":24"))
                        assertTrue(exported.contains("\"cols\":28"))

                        val secondGame = Game()
                        secondGame.state = GameState(secondGame)
                        val ok2 =
                            secondGame.state?.restoreFromString(exported) {
                                try {
                                    assertEquals("Bizerte", secondGame.scenario?.name)
                                    assertEquals(24, secondGame.scenario?.map?.rows)
                                    assertEquals(28, secondGame.scenario?.map?.cols)
                                    assertEquals(
                                        2,
                                        secondGame.scenario
                                            ?.map
                                            ?.getPlayers()
                                            ?.size,
                                    )
                                    resolve(Unit)
                                } catch (e: Throwable) {
                                    reject(e)
                                }
                            }
                        if (ok2 != true) reject(Throwable("second restoreFromString returned false"))
                    } catch (e: Throwable) {
                        reject(e)
                    }
                }
            if (ok != true) reject(Throwable("restoreFromString returned false"))
        }
    }

    @Test
    fun loadOperationUranusSaveRestoresScenarioPlayersAndMap(): Promise<Unit> {
        Equipment.resetEquipment()
        Equipment.asyncLoad = false
        js(
            """
            if (typeof window.scenariolist === 'undefined') {
                window.scenariolist = [
                    ['Test Theater'],
                    ['ruscam00.xml','Operation Uranus','Operation Uranus scenario',[],[],'eqp-adlerkorps']
                ];
            }
        """,
        )

        val game = Game()
        game.state = GameState(game)

        return Promise { resolve, reject ->
            val ok =
                game.state?.restoreFromString(OPERATION_URANUS_SAVE_JSON) {
                    try {
                        val scenario = game.scenario
                        assertNotNull(scenario)
                        assertEquals("Operation Uranus", scenario.name)
                        assertEquals("eqp-adlerkorps", scenario.eqp)
                        assertEquals(17, scenario.maxTurns)
                        assertEquals(2, scenario.map.turn)
                        assertEquals(39, scenario.map.rows)
                        assertEquals(44, scenario.map.cols)

                        val players = scenario.map.getPlayers()
                        assertEquals(2, players.size)
                        assertEquals(2061, players[0].prestige)
                        // eqp-adlerkorps idx 19 -> eqp-united (see tools/eqp-merge/out/country-map.json)
                        assertEquals(61, players[0].country)
                        assertEquals(1, players[0].side)
                        assertEquals(0, players[0].type.value)
                        assertEquals(1376, players[1].prestige)
                        assertEquals(13, players[1].country) // eqp-adlerkorps idx 17 -> eqp-united
                        assertEquals(0, players[1].side)
                        assertEquals(2, players[1].type.value)

                        assertTrue(scenario.reinforcements.isNotEmpty())
                        assertEquals(listOf(10, 13, 17), scenario.map.victoryTurns)
                        assertNotNull(scenario.map.currentPlayer)

                        resolve(Unit)
                    } catch (e: Throwable) {
                        reject(e)
                    }
                }
            if (ok != true) reject(Throwable("restoreFromString returned false"))
        }
    }

    @Test
    fun loadOperationUranusSaveRoundTrips(): Promise<Unit> {
        Equipment.resetEquipment()
        Equipment.asyncLoad = false
        js(
            """
            if (typeof window.scenariolist === 'undefined') {
                window.scenariolist = [
                    ['Test Theater'],
                    ['ruscam00.xml','Operation Uranus','Operation Uranus scenario',[],[],'eqp-adlerkorps']
                ];
            }
        """,
        )

        val game = Game()
        game.state = GameState(game)

        return Promise { resolve, reject ->
            val ok =
                game.state?.restoreFromString(OPERATION_URANUS_SAVE_JSON) {
                    try {
                        val exported = game.state?.exportGameState()
                        assertNotNull(exported)
                        assertTrue(exported.contains("\"name\":\"Operation Uranus\""))
                        assertTrue(exported.contains("\"rows\":39"))
                        assertTrue(exported.contains("\"cols\":44"))

                        val secondGame = Game()
                        secondGame.state = GameState(secondGame)
                        val ok2 =
                            secondGame.state?.restoreFromString(exported) {
                                try {
                                    assertEquals("Operation Uranus", secondGame.scenario?.name)
                                    assertEquals(39, secondGame.scenario?.map?.rows)
                                    assertEquals(44, secondGame.scenario?.map?.cols)
                                    assertEquals(
                                        2,
                                        secondGame.scenario
                                            ?.map
                                            ?.getPlayers()
                                            ?.size,
                                    )
                                    resolve(Unit)
                                } catch (e: Throwable) {
                                    reject(e)
                                }
                            }
                        if (ok2 != true) reject(Throwable("second restoreFromString returned false"))
                    } catch (e: Throwable) {
                        reject(e)
                    }
                }
            if (ok != true) reject(Throwable("restoreFromString returned false"))
        }
    }
}
