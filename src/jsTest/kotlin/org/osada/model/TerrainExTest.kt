package org.osada.model

import org.osada.GroundCondition
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.terrainEntrenchment
import org.osada.terrainInitiative
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-efile terrain entrenchment and initiative cap (DEFERRED.md §1.3). [TerrainEx.baseEntrenchment]
 * and [TerrainEx.initiativeCap] must each prefer the imported per-efile value and fall back to PM's
 * shared [terrainEntrenchment] / [terrainInitiative] baseline whenever the active efile has no
 * TerrainEx data for that terrain id.
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
}
