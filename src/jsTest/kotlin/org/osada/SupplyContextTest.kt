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
import org.osada.rules.SupplyContextRules
import org.osada.rules.SupplySource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertEquals(100, SupplyContextRules.getSupplyContext(map, unit).efficiencyPercent)

        map.map!![1][1].terrain = TerrainType.CLEAR.value
        assertEquals(77, SupplyContextRules.getSupplyContext(map, unit).efficiencyPercent)

        map.map!![1][2].setUnit(unit(enemy))
        assertEquals(51, SupplyContextRules.getSupplyContext(map, unit).efficiencyPercent)
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

        assertEquals(70, SupplyContextRules.getSupplyContext(map, unit).efficiencyPercent)
    }

    /** The tooltip may only name factors that actually participated
     *  (`docs/design/action-affordances-and-objectives.md` §4). A BASEKORP-shaped efile with a
     *  70% clear factor and a +20 road modifier must report both terms and their exact sum, not a
     *  PM city/field sentence. */
    @Test
    fun theStructuredContextNamesEveryFactorThatParticipated() {
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
        map.map!![1][1].road = RoadType.NORTH.value
        TerrainEx.setForTest(
            emptyMap(),
            supplyFactorMap = mapOf(TerrainType.CLEAR.value to 70),
            supplyModifierMap = mapOf("road" to 20, "rail" to 20),
        )

        val context = SupplyContextRules.getSupplyContext(map, unit)
        val factor = context.terrainFactor

        assertEquals(SupplySource.GROUND, context.source)
        assertNotNull(factor)
        assertEquals(70, factor.basePercent)
        assertEquals(TerrainEx.SupplyRoadKind.ROAD, factor.roadKind)
        assertEquals(20, factor.roadPercent)
        assertEquals(0, factor.groundPercent)
        assertEquals(90, factor.totalPercent)
        assertEquals(90, context.efficiencyPercent)
    }

    /** Road and rail are alternatives, not a stacking pair: OG adds one modifier to the terrain
     *  percentage (`docs/design/terrain-supply-and-initiative.md` §3.3). */
    @Test
    fun roadAndRailDoNotStack() {
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
        map.map!![1][1].road = RoadType.NORTH.value
        map.map!![1][1].rail = RoadType.NORTH.value
        TerrainEx.setForTest(
            emptyMap(),
            supplyFactorMap = mapOf(TerrainType.CLEAR.value to 60),
            supplyModifierMap = mapOf("road" to 20, "rail" to 20),
        )

        val factor = SupplyContextRules.getSupplyContext(map, unit).terrainFactor

        assertNotNull(factor)
        assertEquals(20, factor.roadPercent)
        assertEquals(80, factor.totalPercent)
    }

    /** Enemy pressure is reported as its own divisor rather than folded into the terrain term. */
    @Test
    fun adjacentEnemyPressureIsReportedSeparatelyFromTerrain() {
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
        map.map!![1][2].setUnit(unit(enemy))

        val context = SupplyContextRules.getSupplyContext(map, unit)

        assertEquals(1, context.adjacentEnemies)
        assertEquals(100, context.terrainFactor?.totalPercent)
        assertEquals(67, context.efficiencyPercent)
    }

    /** Air and naval supply have no terrain term at all, so the tooltip must not invent one. */
    @Test
    fun airSupplyReportsItsOwnSourceWithNoTerrainFactor() {
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                name = "Fighter"
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
            },
        )
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
        val plane =
            GameUnit(2).apply {
                owner = friendly.id
                player = friendly
            }
        map.map!![1][1].setUnit(plane)

        val context = SupplyContextRules.getSupplyContext(map, plane)

        assertEquals(SupplySource.AIRFIELD_CARRIER, context.source)
        assertNull(context.terrainFactor)
        assertEquals(100, context.efficiencyPercent)
    }

    private fun unit(player: Player): GameUnit =
        GameUnit(1).apply {
            owner = player.id
            this.player = player
        }
}
