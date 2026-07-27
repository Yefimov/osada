package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.rules.SupplyRules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SupplyContextTest {
    @AfterTest
    fun cleanup() {
        TerrainEx.resetForTest()
    }

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                name = "Infantry"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
            },
        )
    }

    @Test
    fun cityFieldAndEnemyPressureAreExplainedWithActualPercentages() {
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
            }
        val friendly =
            Player().apply {
                id = 0
                side = 0
            }
        val enemy =
            Player().apply {
                id = 1
                side = 1
            }
        map.addPlayer(friendly)
        map.addPlayer(enemy)
        val unit = unit(friendly)
        map.map!![1][1].setUnit(unit)

        map.map!![1][1].terrain = TerrainType.CITY.value
        assertEquals(100, SupplyRules.getSupplyContext(map, unit).efficiencyPercent)

        map.map!![1][1].terrain = TerrainType.CLEAR.value
        assertEquals(77, SupplyRules.getSupplyContext(map, unit).efficiencyPercent)

        map.map!![1][2].setUnit(unit(enemy))
        assertEquals(51, SupplyRules.getSupplyContext(map, unit).efficiencyPercent)
    }

    /** DEFERRED.md §1.2/§7.21: a per-efile TerrainEx supply factor (BASEKORP-shaped: clear 70%,
     *  not PM's flat 77%) must actually change the resupply/reinforce efficiency, not just sit
     *  unread in the imported JSON. */
    @Test
    fun perEfileTerrainSupplyFactorReplacesThePmFlatOffCityRate() {
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
            }
        val friendly =
            Player().apply {
                id = 0
                side = 0
            }
        map.addPlayer(friendly)
        val unit = unit(friendly)
        map.map!![1][1].setUnit(unit)
        map.map!![1][1].terrain = TerrainType.CLEAR.value

        TerrainEx.setForTest(emptyMap(), supplyFactorMap = mapOf(TerrainType.CLEAR.value to 70))

        assertEquals(70, SupplyRules.getSupplyContext(map, unit).efficiencyPercent)
    }

    private fun unit(player: Player): GameUnit =
        GameUnit(1).apply {
            owner = player.id
            this.player = player
        }
}
