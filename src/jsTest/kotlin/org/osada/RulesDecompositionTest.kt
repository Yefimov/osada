package org.osada

import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.ExtendedCell
import org.osada.model.GameUnit
import org.osada.model.resetEquipment
import org.osada.rules.CostCalculator
import org.osada.rules.MovementRules
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct unit tests for the rule objects extracted from the former `GameRules`
 * god-object. These cover logic that previously had no dedicated test:
 * [CostCalculator] (buy/upgrade/sell prestige) and [MovementRules.getShortestPath]
 * (A* routing over a precomputed move range).
 */
class RulesDecompositionTest {
    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(1, EquipmentData().apply { cost = 10 }) // unit
        Equipment.putEquipment(2, EquipmentData().apply { cost = 20 }) // transport
        Equipment.putEquipment(3, EquipmentData().apply { cost = 40 }) // upgrade target
    }

    @Test
    fun unitCostUsesCurrencyMultiplierAndSkipsNegativeTransport() {
        assertEquals(10 * CURRENCY_MULTIPLIER, CostCalculator.calculateUnitCosts(1, -1), "unit only")
        assertEquals(
            (10 + 20) * CURRENCY_MULTIPLIER,
            CostCalculator.calculateUnitCosts(1, 2),
            "unit + transport",
        )
    }

    @Test
    fun costPerStrengthIsTenthOfUnitCost() {
        val unit = GameUnit(1)
        assertEquals(10 * CURRENCY_MULTIPLIER / 10, CostCalculator.calculateUnitCostPerStrength(unit))
    }

    @Test
    fun upgradeToSameEquipmentIsFree() {
        // Upgrading a unit to its own eqid (no transport) costs nothing: new == old.
        val unit = GameUnit(1).apply { strength = 10 }
        assertEquals(0, CostCalculator.calculateUpgradeCosts(unit, 1, -1), "same eqid upgrade is free")
    }

    @Test
    fun upgradeToDifferentEquipmentCostsPenalisedDifference() {
        val unit = GameUnit(1).apply { strength = 10 }
        val expected = ((40 * CURRENCY_MULTIPLIER) * UPGRADE_PENALTY).toInt() - 10 * CURRENCY_MULTIPLIER
        assertEquals(expected, CostCalculator.calculateUpgradeCosts(unit, 3, -1), "penalised upgrade delta")
    }

    @Test
    fun shortestPathReturnsAdjacentStep() {
        val start = Cell(1, 1)
        val end = Cell(1, 2) // a hex neighbour of (1,1)
        val moveRange = listOf(ExtendedCell(1, 2).apply { cost = 1 })
        val path = MovementRules.getShortestPath(start, end, moveRange).map { it.row to it.col }
        assertEquals(listOf(1 to 1, 1 to 2), path)
    }

    @Test
    fun shortestPathRoutesThroughIntermediateHex() {
        // (1,1) and (1,3) are distance 2 (not adjacent); the path must pass through (1,2).
        val start = Cell(1, 1)
        val end = Cell(1, 3)
        val moveRange =
            listOf(
                ExtendedCell(1, 2).apply { cost = 1 },
                ExtendedCell(1, 3).apply { cost = 1 },
            )
        val path = MovementRules.getShortestPath(start, end, moveRange).map { it.row to it.col }
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3), path)
    }

    @Test
    fun shortestPathReturnsEmptyWhenUnreachable() {
        val path = MovementRules.getShortestPath(Cell(1, 1), Cell(5, 5), emptyList())
        assertTrue(path.isEmpty(), "no path when destination is not in the move range")
    }
}
