package org.osada.model

import org.osada.GroundCondition
import org.osada.MovMethod
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.movTableDry
import org.osada.movTableFrozen
import org.osada.movTableMud
import org.osada.terrainEntrenchment
import org.osada.terrainInitiative
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-efile terrain entrenchment, initiative cap and movement cost (DEFERRED.md §1.3).
 * [TerrainEx.baseEntrenchment], [TerrainEx.initiativeCap] and [TerrainEx.movementCostTable] must
 * each prefer the imported per-efile value and fall back to PM's shared [terrainEntrenchment] /
 * [terrainInitiative] / [movTableDry] baseline whenever the active efile has no TerrainEx data for
 * that terrain id or movement method.
 */
class TerrainExTest {
    @AfterTest
    fun cleanup() {
        TerrainEx.resetForTest()
    }

    @Test
    fun parsesBaseEntrenchByTerrainId() {
        val json =
            """
            {"efile":"ATOMIC","terrain":{
              "0":{"name":"clear","base_entrench":0},
              "1":{"name":"town","base_entrench":4},
              "11":{"name":"fortification","base_entrench":5}
            }}
            """.trimIndent()

        val parsed = TerrainEx.parseField(json, "base_entrench")

        assertEquals(0, parsed[0])
        assertEquals(4, parsed[1])
        assertEquals(5, parsed[11])
    }

    @Test
    fun parsesInitiativeCapByTerrainId() {
        val json =
            """
            {"efile":"ATOMIC","terrain":{
              "0":{"name":"clear","initiative_cap":99},
              "1":{"name":"town","initiative_cap":1},
              "5":{"name":"mountain","initiative_cap":1}
            }}
            """.trimIndent()

        val parsed = TerrainEx.parseField(json, "initiative_cap")

        assertEquals(99, parsed[0])
        assertEquals(1, parsed[1])
        assertEquals(1, parsed[5])
    }

    @Test
    fun malformedOrEmptyJsonYieldsNoEntries() {
        assertEquals(emptyMap(), TerrainEx.parseField("""{"efile":"X"}""", "base_entrench"))
        assertEquals(emptyMap(), TerrainEx.parseField("{}", "base_entrench"))
    }

    @Test
    fun perEfileValueWinsOverPmBaseline() {
        // PM's own baseline says town (terrain 1) entrenches to 3; ATOMIC's TerrainEx.txt says 4.
        assertEquals(3, terrainEntrenchment[1], "test assumption: baseline differs from the efile value")
        TerrainEx.setForTest(mapOf(1 to 4))

        assertEquals(4, TerrainEx.baseEntrenchment(1))
    }

    @Test
    fun terrainIdMissingFromTheEfileFallsBackToPmBaseline() {
        // ATOMIC's data has no entry for terrain 2 (airfield) in this fixture.
        TerrainEx.setForTest(mapOf(1 to 4))

        assertEquals(terrainEntrenchment[2], TerrainEx.baseEntrenchment(2))
    }

    @Test
    fun efileWithNoTerrainExDataFallsBackEntirelyToPmBaseline() {
        // GCE/OLGCW/OLGWW2-shaped case: nothing was ever loaded for this efile.
        TerrainEx.setForTest(emptyMap())

        terrainEntrenchment.indices.forEach { terrain ->
            assertEquals(terrainEntrenchment[terrain], TerrainEx.baseEntrenchment(terrain))
        }
    }

    @Test
    fun perEfileInitiativeCapWinsOverPmBaseline() {
        // PM's own baseline caps town (terrain 1) at 1; give this efile a fictitious 2 to prove it wins.
        assertEquals(1, terrainInitiative[1], "test assumption: baseline value for this terrain")
        TerrainEx.setForTest(emptyMap(), initiativeCapMap = mapOf(1 to 2))

        assertEquals(2, TerrainEx.initiativeCap(1))
    }

    @Test
    fun terrainIdMissingItsInitiativeCapFallsBackToPmBaseline() {
        TerrainEx.setForTest(emptyMap(), initiativeCapMap = mapOf(1 to 2))

        assertEquals(terrainInitiative[5], TerrainEx.initiativeCap(5))
    }

    @Test
    fun efileWithoutTerrainExUsesThePmInitiativeTable() {
        // GCE/OLGCW/OLGWW2-shaped case: nothing was ever loaded for this efile.
        TerrainEx.setForTest(emptyMap())

        terrainInitiative.indices.forEach { terrain ->
            assertEquals(terrainInitiative[terrain], TerrainEx.initiativeCap(terrain))
        }
    }

    @Test
    fun parsesSupplyFactorByTerrainId() {
        val json =
            """
            {"efile":"BASEKORP","terrain":{
              "0":{"name":"clear","supply_factor_pct":70},
              "10":{"name":"river","supply_factor_pct":10}
            }}
            """.trimIndent()

        val parsed = TerrainEx.parseField(json, "supply_factor_pct")

        assertEquals(70, parsed[0])
        assertEquals(10, parsed[10])
    }

    @Test
    fun parsesSupplyModifiersMap() {
        val json =
            """
            {"efile":"BASEKORP","terrain":{},
             "supply_modifiers":{"road":20,"rail":20,"frozen":-30,"mud":-30}}
            """.trimIndent()

        val parsed = TerrainEx.parseSupplyModifiers(json)

        assertEquals(20, parsed["road"])
        assertEquals(20, parsed["rail"])
        assertEquals(-30, parsed["frozen"])
        assertEquals(-30, parsed["mud"])
    }

    @Test
    fun perEfileSupplyFactorWinsOverThePmFlatFallback() {
        // BASEKORP-shaped: clear terrain factor is 70%, nothing like PM's flat 77% off-city rate.
        TerrainEx.setForTest(emptyMap(), supplyFactorMap = mapOf(TerrainType.CLEAR.value to 70))

        assertEquals(
            70,
            TerrainEx.supplyFactor(
                TerrainType.CLEAR.value,
                RoadType.NONE.value,
                RoadType.NONE.value,
                GroundCondition.DRY.value,
            ),
        )
    }

    @Test
    fun terrainIdMissingItsSupplyFactorFallsBackToPmFlatRule() {
        TerrainEx.setForTest(emptyMap(), supplyFactorMap = mapOf(TerrainType.CLEAR.value to 70))

        assertEquals(
            100,
            TerrainEx.supplyFactor(
                TerrainType.CITY.value,
                RoadType.NONE.value,
                RoadType.NONE.value,
                GroundCondition.DRY.value,
            ),
            "city keeps PM's uncapped 100% when the efile has no entry for it",
        )
        assertEquals(
            77,
            TerrainEx.supplyFactor(
                TerrainType.FOREST.value,
                RoadType.NONE.value,
                RoadType.NONE.value,
                GroundCondition.DRY.value,
            ),
            "any other terrain the efile omits keeps PM's flat off-city 77%",
        )
    }

    @Test
    fun efileWithNoTerrainExDataFallsBackEntirelyForSupplyFactor() {
        // GCE/OLGCW/OLGWW2-shaped case: nothing was ever loaded for this efile.
        TerrainEx.setForTest(emptyMap())

        assertEquals(
            100,
            TerrainEx.supplyFactor(
                TerrainType.CITY.value,
                RoadType.NONE.value,
                RoadType.NONE.value,
                GroundCondition.DRY.value,
            ),
        )
        assertEquals(
            77,
            TerrainEx.supplyFactor(
                TerrainType.CLEAR.value,
                RoadType.NONE.value,
                RoadType.NONE.value,
                GroundCondition.DRY.value,
            ),
        )
    }

    @Test
    fun roadAndRailBothPresentDoNotStack() {
        TerrainEx.setForTest(
            emptyMap(),
            supplyFactorMap = mapOf(TerrainType.CLEAR.value to 70),
            supplyModifierMap = mapOf("road" to 20, "rail" to 20),
        )

        val roadOnly =
            TerrainEx.supplyFactor(
                TerrainType.CLEAR.value,
                RoadType.NORTH.value,
                RoadType.NONE.value,
                GroundCondition.DRY.value,
            )
        val both =
            TerrainEx.supplyFactor(
                TerrainType.CLEAR.value,
                RoadType.NORTH.value,
                RoadType.NORTH.value,
                GroundCondition.DRY.value,
            )

        assertEquals(90, roadOnly, "70 base + 20 road")
        assertEquals(90, both, "road and rail present together must not add to 110")
    }

    @Test
    fun frozenGroundModifiesTheFactorAndClampsToZero() {
        TerrainEx.setForTest(
            emptyMap(),
            supplyFactorMap = mapOf(TerrainType.OCEAN.value to 0),
            supplyModifierMap = mapOf("frozen" to -30),
        )

        assertEquals(
            0,
            TerrainEx.supplyFactor(
                TerrainType.OCEAN.value,
                RoadType.NONE.value,
                RoadType.NONE.value,
                GroundCondition.FROZEN.value,
            ),
            "0 - 30 clamps to 0, never negative",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Per-efile movement costs ([TerrainEx.movementCostTable])
    // ---------------------------------------------------------------------------------------

    /** BASEKORP's real Coastal row, dry: ocean/river/port/impassable-river/shallow passable, the
     *  rest 255. 19 columns, exactly as `[terrain-cost]` writes them. */
    private val basekorpCoastalDry =
        listOf(255, 255, 255, 255, 255, 255, 255, 255, 255, 1, 1, 255, 1, 255, 255, 1, 255, 255, 1)

    /** Pins the efile's move costs without touching the network: [TerrainEx.setForTest] stops
     *  `loadIfNeeded` from fetching, then [TerrainMovementCost] gets the rows directly. */
    private fun loadEfileCosts(
        costs: Map<Int, Map<String, List<Int>>>,
        roads: Map<String, List<Int>> = emptyMap(),
    ) {
        TerrainEx.setForTest(emptyMap())
        TerrainMovementCost.setForTest(terrainCostMap = costs, roadsCostMap = roads)
    }

    @Test
    fun parsesTerrainCostByMovementMethodAndGround() {
        val json =
            """
            {"efile":"BASEKORP","terrain":{},"terrain_cost":{
              "7":{"dry":[255,255,255,255,255,255,255,255,255,1,1,255,1,255,255,1,255,255,1],
                   "frozen":[255,255,255,255,255,255,255,255,255,1,2,255,1,255,255,2,255,255,1]},
              "10":{"dry":[255,255,255,255,255,255,255,255,255,1,255,255,1,255,255,1,255,255,1]}
            }}
            """.trimIndent()

        val parsed = TerrainMovementCost.parseTerrainCost(json)

        assertEquals(basekorpCoastalDry, parsed[MovMethod.COASTAL.value]?.get("dry"))
        assertEquals(2, parsed[MovMethod.COASTAL.value]?.get("frozen")?.get(TerrainType.IMPASSABLE_RIVER.value))
        assertEquals(1, parsed[MovMethod.NAVAL.value]?.get("dry")?.get(TerrainType.IMPASSABLE_RIVER.value))
        assertEquals(null, parsed[MovMethod.NAVAL.value]?.get("frozen"), "not in the fixture")
    }

    @Test
    fun parsesRoadsCostByGround() {
        val json =
            """
            {"efile":"BASEKORP","terrain":{},
             "roads_cost":{"dry":[1,1,1,1,1,1,255,255,1,1,255,1,255,1,1]}}
            """.trimIndent()

        val parsed = TerrainMovementCost.parseGroundRows(json, "roads_cost")

        assertEquals(1, parsed["dry"]?.get(MovMethod.TRACKED.value))
        assertEquals(255, parsed["dry"]?.get(MovMethod.COASTAL.value))
        assertEquals(null, parsed["frozen"])
    }

    /**
     * The reported bug. PM's shared table marks IMPASSABLE_RIVER 255 for every movement method, so
     * `Falciu 1`'s Shtorm TB could not cross the terrain-15 stretches of the very river it sailed;
     * BASEKORP's own Coastal row puts that cell at 1.
     */
    @Test
    fun theEfileMakesAnImpassableRiverNavigableForACoastalShip() {
        assertEquals(
            255,
            movTableDry[MovMethod.COASTAL.value][TerrainType.IMPASSABLE_RIVER.value],
            "test assumption: PM's own table forbids it",
        )
        loadEfileCosts(mapOf(MovMethod.COASTAL.value to mapOf("dry" to basekorpCoastalDry)))

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(1, table[MovMethod.COASTAL.value][TerrainType.IMPASSABLE_RIVER.value])
    }

    /** A movement method the efile says nothing about keeps PM's row untouched. */
    @Test
    fun movementMethodsAbsentFromTheEfileKeepThePmRow() {
        loadEfileCosts(mapOf(MovMethod.COASTAL.value to mapOf("dry" to basekorpCoastalDry)))

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(movTableDry[MovMethod.TRACKED.value], table[MovMethod.TRACKED.value])
        assertEquals(movTableDry[MovMethod.LEG.value], table[MovMethod.LEG.value])
    }

    /**
     * AIR is deliberately excluded from the overlay: OSADA resolves air movement outside this table
     * entirely, and AG's row 5 is `ocean 255, river 254, impassable 255` on an efile whose FIGHTER
     * records all declare movmethod 5 -- taking it would ground AG's air force in every path that
     * DOES read the table (reinforcement placement) while move range flew on regardless.
     */
    @Test
    fun theAirRowIsNeverOverlaid() {
        val agAirDry = listOf(1, 1, 1, 2, 2, 1, 1, 2, 2, 255, 254, 1, 1, 1, 255, 255, 1, 1, 1)
        loadEfileCosts(mapOf(MovMethod.AIR.value to mapOf("dry" to agAirDry)))

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(movTableDry[MovMethod.AIR.value], table[MovMethod.AIR.value])
        assertEquals(1, table[MovMethod.AIR.value][TerrainType.OCEAN.value], "aircraft still cross water")
    }

    /** RAIL's row is OSADA's own all-255 sentinel; the real gate is isTrain + `hex.rail`. OG's Train
     *  row would put a train in a port hex (254) through the table instead. */
    @Test
    fun theRailRowIsNeverOverlaid() {
        val ogTrainDry = List(19) { if (it == TerrainType.PORT.value) 254 else 255 }
        loadEfileCosts(mapOf(MovMethod.RAIL.value to mapOf("dry" to ogTrainDry)))

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(movTableDry[MovMethod.RAIL.value], table[MovMethod.RAIL.value])
        assertTrue(table[MovMethod.RAIL.value].all { it == 255 }, "the sentinel row stays all-255")
    }

    /** OG keeps the road cost in its own `[roads-cost]` section; PM keeps it at index 17 of each
     *  movement row. The overlay must move it into that slot and not read terrain column 17. */
    @Test
    fun theRoadColumnComesFromRoadsCostNotTerrainColumn17() {
        val ogCustomColumn = 17
        assertEquals(255, basekorpCoastalDry[ogCustomColumn], "OG column 17 is `custom` terrain, not road")
        loadEfileCosts(
            mapOf(MovMethod.TRACKED.value to mapOf("dry" to basekorpCoastalDry)),
            roads = mapOf("dry" to List(15) { 3 }),
        )

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(3, table[MovMethod.TRACKED.value][ROAD_COLUMN], "taken from roads_cost")
    }

    /** No `roads_cost` for this ground condition -> PM's own road entry survives. */
    @Test
    fun aMissingRoadsCostRowKeepsThePmRoadEntry() {
        loadEfileCosts(mapOf(MovMethod.COASTAL.value to mapOf("dry" to basekorpCoastalDry)))

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(movTableDry[MovMethod.COASTAL.value][ROAD_COLUMN], table[MovMethod.COASTAL.value][ROAD_COLUMN])
    }

    @Test
    fun frozenAndMudSelectTheirOwnBaselineAndTheirOwnEfileRow() {
        loadEfileCosts(
            mapOf(
                MovMethod.COASTAL.value to
                    mapOf(
                        "dry" to basekorpCoastalDry,
                        "mud" to basekorpCoastalDry.toMutableList().also { it[TerrainType.OCEAN.value] = 2 },
                    ),
            ),
        )

        val frozen = TerrainEx.movementCostTable(GroundCondition.FROZEN.value)
        val mud = TerrainEx.movementCostTable(GroundCondition.MUD.value)

        assertEquals(
            movTableFrozen[MovMethod.COASTAL.value],
            frozen[MovMethod.COASTAL.value],
            "the efile has no frozen row here, so PM's frozen table stands",
        )
        assertEquals(2, mud[MovMethod.COASTAL.value][TerrainType.OCEAN.value], "mud row taken from the efile")
        assertEquals(movTableMud[MovMethod.TRACKED.value], mud[MovMethod.TRACKED.value], "mud baseline for the rest")
    }

    /** GCE/OLGCW/OLGWW2 ship no TerrainEx at all — they must see PM's tables byte for byte. */
    @Test
    fun anEfileWithoutTerrainCostDataGetsThePmTablesUnchanged() {
        TerrainEx.setForTest(emptyMap())

        assertEquals(movTableDry, TerrainEx.movementCostTable(GroundCondition.DRY.value))
        assertEquals(movTableFrozen, TerrainEx.movementCostTable(GroundCondition.FROZEN.value))
        assertEquals(movTableMud, TerrainEx.movementCostTable(GroundCondition.MUD.value))
    }

    /** The overlay must not change the table's shape — every consumer indexes it positionally. */
    @Test
    fun theOverlaidTableKeepsPmsDimensions() {
        loadEfileCosts(
            mapOf(MovMethod.COASTAL.value to mapOf("dry" to basekorpCoastalDry)),
            roads = mapOf("dry" to List(15) { 1 }),
        )

        val table = TerrainEx.movementCostTable(GroundCondition.DRY.value)

        assertEquals(movTableDry.size, table.size)
        table.forEach { row -> assertEquals(movTableDry[0].size, row.size) }
    }

    private companion object {
        /** Index 17 of a movement row: PM's road column. Mirrors `TerrainEx`'s own private const. */
        const val ROAD_COLUMN = 17
    }
}
