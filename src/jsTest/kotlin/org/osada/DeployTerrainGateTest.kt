package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.TerrainMovementCost
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.canDeployOnTerrain
import org.osada.model.deployPlayerUnit
import org.osada.model.getPlayer
import org.osada.model.resetEquipment
import org.osada.rules.GameRules
import org.osada.rules.isAir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deploy-zone terrain gate, and the fact that the map renderer and the click handler now ask
 * the SAME question `deployPlayerUnit` answers.
 *
 * Reported on `Falciu 1` (Soviet Black Sea Fleet, BASEKORP): the author marks 7 deploy hexes -- 3
 * town, 1 mountain, 1 clear and 2 river -- and the Shtorm TB may only take the two river ones,
 * (24,17) and (24,18). OSADA highlighted all 7 and then had `deployPlayerUnit` refuse 5 of them
 * with no message, because the highlight (`HexCellRenderer.drawDeployHighlight`) tested only
 * occupancy while the terrain rule lived privately inside `UnitDeployOperations`. There was no
 * test for the rule at all.
 */
class DeployTerrainGateTest {
    private val coastalEqid = 300
    private val legEqid = 301
    private val fighterEqid = 302
    private val trainEqid = 303

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        // `movTable` is a mutable global that scenario loading rewrites; pin it so a sibling test
        // cannot leave it on frozen/mud, where these costs differ.
        movTable = movTableDry
        TerrainEx.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            coastalEqid,
            EquipmentData().apply {
                uclass = UnitClass.DESTROYER.value
                movmethod = MovMethod.COASTAL.value
                movpoints = 6
                fuel = 60
                ammo = 8
            },
        )
        Equipment.putEquipment(
            legEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                movpoints = 3
                fuel = 0
                ammo = 6
            },
        )
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
                fuel = 40
                ammo = 4
            },
        )
        Equipment.putEquipment(
            trainEqid,
            EquipmentData().apply {
                // e.g. eqp-kaiser/eqp-united's Bronevagon (armoured train) rolling stock.
                uclass = UnitClass.ARTILLERY.value
                movmethod = MovMethod.RAIL.value
                movpoints = 6
                fuel = 0
                ammo = 8
            },
        )
    }

    @AfterTest
    fun cleanup() {
        movTable = movTableDry
        TerrainEx.resetForTest()
    }

    private fun buildMap(): GameMap {
        val map =
            GameMap().apply {
                rows = 3
                cols = 3
                allocMap()
            }
        map.addPlayer(
            Player().apply {
                id = 0
                side = 0
                country = 0
            },
        )
        return map
    }

    private fun hexAt(
        map: GameMap,
        row: Int,
        col: Int,
        terrain: Int,
    ) = map.map!![row][col].apply { this.terrain = terrain }

    private fun reserve(
        map: GameMap,
        eqid: Int,
    ): GameUnit =
        GameUnit(eqid).apply {
            owner = 0
            player = map.getPlayer(0)
            strength = 10
        }

    private fun accepts(
        map: GameMap,
        eqid: Int,
        terrain: Int,
    ): Boolean {
        val unit = reserve(map, eqid)
        return canDeployOnTerrain(unit, hexAt(map, 1, 1, terrain), GameRules.isAir(unit))
    }

    @Test
    fun aRiverGunboatIsRefusedTheTownAndMountainDeployHexes() {
        val map = buildMap()

        assertFalse(accepts(map, coastalEqid, TerrainType.CITY.value), "Cantemir, (23,17) in Falciu 1")
        assertFalse(accepts(map, coastalEqid, TerrainType.MOUNTAIN.value))
        assertFalse(accepts(map, coastalEqid, TerrainType.CLEAR.value))
    }

    @Test
    fun aRiverGunboatTakesTheRiverDeployHexes() {
        val map = buildMap()

        assertTrue(accepts(map, coastalEqid, TerrainType.RIVER.value), "(24,17) and (24,18) in Falciu 1")
    }

    /**
     * The two fixes meet here. `Falciu 1`'s Prut is drawn as an unbroken mix of RIVER(10) and
     * IMPASSABLE_RIVER(15); PM's shared table calls 15 impassable for every movement method, but
     * BASEKORP's own Coastal row costs it 1. With the efile's costs loaded, a terrain-15 hex is a
     * legal deploy hex for a coastal ship -- and a reachable one.
     */
    @Test
    fun theEfileTerrainCostsOpenImpassableRiverHexesToACoastalShip() {
        val map = buildMap()
        assertFalse(
            accepts(map, coastalEqid, TerrainType.IMPASSABLE_RIVER.value),
            "test assumption: PM's shared table refuses it",
        )

        TerrainEx.setForTest(emptyMap())
        TerrainMovementCost.setForTest(
            terrainCostMap = mapOf(MovMethod.COASTAL.value to mapOf("dry" to BASEKORP_COASTAL_DRY)),
        )
        movTable = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertTrue(accepts(map, coastalEqid, TerrainType.IMPASSABLE_RIVER.value))
    }

    /** `254` is "costs the whole move allowance", not "forbidden" — Leg on a mountain stays legal,
     *  which is what makes `Falciu 1`'s mountain deploy hex usable by infantry. */
    @Test
    fun aLegUnitStillDeploysOntoAMountainAtFullMoveCost() {
        val map = buildMap()
        assertEquals(
            254,
            movTableDry[MovMethod.LEG.value][TerrainType.MOUNTAIN.value],
            "test assumption: costly, not impassable",
        )

        assertTrue(accepts(map, legEqid, TerrainType.MOUNTAIN.value))
    }

    /** Air units are exempt: they occupy the air layer, and OSADA does not resolve air movement
     *  through this table at all. */
    @Test
    fun anAircraftIsExemptFromTheTerrainGate() {
        val map = buildMap()

        assertTrue(accepts(map, fighterEqid, TerrainType.OCEAN.value))
        assertTrue(accepts(map, fighterEqid, TerrainType.IMPASSABLE_RIVER.value))
        assertTrue(accepts(map, fighterEqid, TerrainType.MOUNTAIN.value))
    }

    /** Ocean keeps its amphibious exception: a ground unit placed there embarks rather than being
     *  refused, even though Leg's ocean cost is 255. */
    @Test
    fun aGroundUnitOnOceanEmbarksInsteadOfBeingRefused() {
        val map = buildMap()
        assertEquals(255, movTableDry[MovMethod.LEG.value][TerrainType.OCEAN.value])

        assertTrue(accepts(map, legEqid, TerrainType.OCEAN.value))
    }

    /** `movTable[RAIL]` is intentionally all-255 (Constants.kt: real rail movement is resolved by
     *  `hex.rail`, not this table) -- `canDeployOnTerrain` must resolve a train through WHEELED
     *  the same way [org.osada.rules.getReinforcementDeployPositions] already does for scripted
     *  reinforcements, or every hex reads as impassable and a train can never be deployed at all
     *  (2026-08-19 user report: "Reserves of Trains are not working ... click on them and nothing
     *  happens"). */
    @Test
    fun aTrainDeploysOnOrdinaryTerrainInsteadOfEveryHexReadingImpassable() {
        val map = buildMap()
        assertEquals(
            255,
            movTableDry[MovMethod.RAIL.value][TerrainType.CLEAR.value],
            "test assumption: RAIL's own row is all-255",
        )

        assertTrue(accepts(map, trainEqid, TerrainType.CLEAR.value))
    }

    @Test
    fun deployPlayerUnitPlacesATrainOnAnOrdinaryClearHex() {
        val map = buildMap()
        val clear = hexAt(map, 1, 1, TerrainType.CLEAR.value)
        val unit = reserve(map, trainEqid)

        val placed = map.deployPlayerUnit(map.getPlayer(0), unit, 1, 1)

        assertTrue(placed)
        assertEquals(unit.id, clear.unit?.id)
        assertTrue(unit.isDeployed)
    }

    @Test
    fun deployPlayerUnitRefusesTheHexAndLeavesItEmpty() {
        val map = buildMap()
        val town = hexAt(map, 1, 1, TerrainType.CITY.value)
        val unit = reserve(map, coastalEqid)

        val placed = map.deployPlayerUnit(map.getPlayer(0), unit, 1, 1)

        assertFalse(placed)
        assertNull(town.unit, "a refused deployment must not half-place the unit")
        assertFalse(unit.isDeployed)
    }

    @Test
    fun deployPlayerUnitAcceptsAPassableHexAndPlacesTheUnit() {
        val map = buildMap()
        val river = hexAt(map, 1, 1, TerrainType.RIVER.value)
        val unit = reserve(map, coastalEqid)

        val placed = map.deployPlayerUnit(map.getPlayer(0), unit, 1, 1)

        assertTrue(placed)
        assertEquals(unit.id, river.unit?.id)
        assertTrue(unit.isDeployed)
    }

    private companion object {
        /** BASEKORP's real Coastal row, dry, all 19 of OG's terrain columns: ocean/river/port/
         *  impassable-river/shallow passable, everything else 255. */
        val BASEKORP_COASTAL_DRY =
            listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 1, 255, 1, 255, 255, 1, 255, 255, 1)
    }
}
