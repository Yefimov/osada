package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.model.setHex
import org.osada.scenario.AuthoredOptionsBackfill
import org.osada.scenario.Scenario
import org.osada.scenario.recordCaptureGoalSides
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A defender that lets the enemy take one of its flags and then retakes it must not win on the spot.
 *
 * Shaped on `forward2.xml` (Pocket of Minsk): player 0 is the Soviet side 1 and owns all four
 * objectives, each authored `victory="0"` — only the Germans (player 1, side 0) are sent to capture
 * anything. The Soviet list in `sidesVictoryHexes` is therefore empty from turn 1, and PM's
 * `updateVictorySides` scored "that list is empty again" after a recapture as a capture victory.
 */
class VictoryRecaptureTest {
    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            INFANTRY,
            EquipmentData().apply {
                name = "Rifles"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
            },
        )
    }

    @Test
    fun retakingALostObjectiveIsNotAVictoryForTheDefender() {
        val (map, soviet, german) = minsk()

        val lost = map.combatApplication.captureHex(map.map!![0][0], unit(german))
        assertEquals(false, lost["isWin"], "the Germans took one of four")

        val retaken = map.combatApplication.captureHex(map.map!![0][0], unit(soviet))

        assertEquals(false, retaken["isWin"], "retaking your own flag is not a capture victory")
        assertEquals(OBJECTIVES.size, map.sidesVictoryHexes[GERMAN_SIDE].size, "the Germans need all four again")
        assertTrue(map.sidesVictoryHexes[SOVIET_SIDE].isEmpty())
    }

    @Test
    fun theAttackerStillWinsByTakingEveryObjective() {
        val (map, _, german) = minsk()

        val results = OBJECTIVES.map { (r, c) -> map.combatApplication.captureHex(map.map!![r][c], unit(german)) }

        assertTrue(results.dropLast(1).none { it["isWin"] == true })
        assertEquals(true, results.last()["isWin"], "the last objective ends the battle for the side sent to take it")
    }

    @Test
    fun theAttackerStillWinsAfterTheDefenderRetookOne() {
        val (map, soviet, german) = minsk()
        map.combatApplication.captureHex(map.map!![0][0], unit(german))
        map.combatApplication.captureHex(map.map!![0][0], unit(soviet))

        val results = OBJECTIVES.map { (r, c) -> map.combatApplication.captureHex(map.map!![r][c], unit(german)) }

        assertEquals(true, results.last()["isWin"])
    }

    /** A save written before `captureGoalSides` existed recovers it from the STARTING objectives. */
    @Test
    fun legacySavesRecoverTheCaptureGoalFromTheScenarioXml() {
        val (map, _, _) = minsk()
        val scenario = Scenario(null).apply { this.map = map }
        val xml =
            """<scenario><map/>
              <hex row="0" col="0" owner="0" flag="89" victory="0"/>
              <hex row="0" col="1" owner="0" flag="89" victory="0"/>
              <hex row="1" col="1" owner="0" flag="89"/>
            </scenario>"""
        val doc = DOMParser().parseFromString(xml, "application/xml")

        assertEquals(listOf(GERMAN_SIDE), AuthoredOptionsBackfill.captureGoalSides(scenario, doc))
    }

    /** A map nobody recorded keeps the inherited rule rather than guessing. */
    @Test
    fun anUnrecordedMapKeepsThePreviousRule() {
        val (map, soviet, german) = minsk()
        map.captureGoalSides = null
        map.combatApplication.captureHex(map.map!![0][0], unit(german))

        val retaken = map.combatApplication.captureHex(map.map!![0][0], unit(soviet))

        assertNull(map.captureGoalSides)
        assertEquals(true, retaken["isWin"], "unknown falls back to PM's any-empty-list rule")
    }

    // ------------------------------------------------------------------ fixtures

    private companion object {
        const val INFANTRY = 501
        const val SOVIET_SIDE = 1
        const val GERMAN_SIDE = 0
        const val SOVIET_COUNTRY = 89
        const val GERMAN_COUNTRY = 7
        val OBJECTIVES = listOf(0 to 0, 0 to 2, 2 to 0, 2 to 2)
    }

    private fun minsk(): Triple<GameMap, Player, Player> {
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
            }
        val soviet =
            Player().apply {
                id = 0
                side = SOVIET_SIDE
                country = SOVIET_COUNTRY
            }
        val german =
            Player().apply {
                id = 1
                side = GERMAN_SIDE
                country = GERMAN_COUNTRY
            }
        map.addPlayer(soviet)
        map.addPlayer(german)
        OBJECTIVES.forEach { (r, c) ->
            map.map!![r][c].apply {
                owner = soviet.id
                flag = SOVIET_COUNTRY
                victorySide = GERMAN_SIDE
            }
            map.setHex(r, c)
        }
        map.recordCaptureGoalSides()
        assertEquals(listOf(GERMAN_SIDE), map.captureGoalSides, "only the Germans are sent to capture")
        return Triple(map, soviet, german)
    }

    private fun unit(player: Player): GameUnit =
        GameUnit(INFANTRY).apply {
            owner = player.id
            this.player = player
        }
}
