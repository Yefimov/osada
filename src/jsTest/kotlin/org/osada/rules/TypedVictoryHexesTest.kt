package org.osada.rules

import org.osada.Game
import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.model.ALL_VICTORY_TIERS
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.model.moveUnit
import org.osada.model.setHex
import org.osada.model.setMoveRange
import org.osada.restoreTypedVictoryHexes
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypedVictoryHexesTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
        ruleset()
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    /** Taking the BV subset ends the battle even while an ordinary all-VH objective remains. */
    @Test
    fun capturingACompletedTypedTierReportsVictoryImmediately() {
        val map = world()
        val scenario = publishTypedScenario(map)
        objective(map, 4, 4, tiers0 = TypedVictoryHexes.BRILLIANT)
        objective(map, 6, 6, tiers0 = TypedVictoryHexes.VICTORY)
        val unit = place(map, infantryEqid, 4, 3, 0)

        map.setMoveRange(unit)
        val result = map.moveUnit(unit, 4, 4)

        assertEquals(0, result.isVictorySide, "typed completion must use the normal victory hand-off")
        assertEquals(1, map.sidesVictoryHexes[0].size, "the ordinary all-objectives route is not done")
        assertEquals(0, TypedVictoryHexes.completedTier(scenario, map, 0))
    }

    /** A zero mask means this side has no objective here; it must not fall back to mask 7. */
    @Test
    fun tierMasksAreIndependentPerSideAndZeroMeansNone() {
        val map = world()
        val scenario = publishTypedScenario(map)
        objective(map, 4, 4, owner = hostile.id, tiers0 = 0, tiers1 = TypedVictoryHexes.BRILLIANT)

        assertNull(TypedVictoryHexes.completedTier(scenario, map, 0))
        assertEquals(0, TypedVictoryHexes.completedTier(scenario, map, 1))
    }

    /** Both side masks survive a save, including meaningful zero. */
    @Test
    fun sideSpecificMasksRoundTripThroughHexSaveData() {
        val source =
            Hex(1, 1).apply {
                victoryTiersSide0 = 0
                victoryTiersSide1 = TypedVictoryHexes.TACTICAL
            }
        val payload = reparse(GameStateSerializer.serializeHex(source))
        val restored = Hex(1, 1)

        restoreTypedVictoryHexes(restored, payload)

        assertEquals(0, restored.victoryTiersSide0)
        assertEquals(TypedVictoryHexes.TACTICAL, restored.victoryTiersSide1)
        val legacy = Hex(1, 1)
        restoreTypedVictoryHexes(legacy, js("({})"))
        assertEquals(ALL_VICTORY_TIERS, legacy.victoryTiersSide0)
        assertEquals(ALL_VICTORY_TIERS, legacy.victoryTiersSide1)
    }

    private fun publishTypedScenario(map: GameMap): Scenario =
        Scenario(null).apply {
            this.map = map
            typedVictoryHexes = true
            map.victoryTurns.addAll(listOf(5, 8, 10))
            GameHolder.instance = Game().also { game -> game.scenario = this }
        }

    private fun objective(
        map: GameMap,
        row: Int,
        col: Int,
        owner: Int = hostile.id,
        tiers0: Int = ALL_VICTORY_TIERS,
        tiers1: Int = ALL_VICTORY_TIERS,
    ) {
        map.map!![row][col].apply {
            this.owner = owner
            flag = hostile.country
            victorySide = 0
            victoryTiersSide0 = tiers0
            victoryTiersSide1 = tiers1
        }
        map.setHex(row, col)
    }
}
