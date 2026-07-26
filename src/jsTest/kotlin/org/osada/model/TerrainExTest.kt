package org.osada.model

import org.osada.terrainEntrenchment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-efile terrain entrenchment (DEFERRED.md §1.3). [TerrainEx.baseEntrenchment] must prefer the
 * imported per-efile value and fall back to PM's shared [terrainEntrenchment] baseline whenever
 * the active efile has no TerrainEx data for that terrain id.
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

        val parsed = TerrainEx.parseBaseEntrench(json)

        assertEquals(0, parsed[0])
        assertEquals(4, parsed[1])
        assertEquals(5, parsed[11])
    }

    @Test
    fun malformedOrEmptyJsonYieldsNoEntries() {
        assertEquals(emptyMap(), TerrainEx.parseBaseEntrench("""{"efile":"X"}"""))
        assertEquals(emptyMap(), TerrainEx.parseBaseEntrench("{}"))
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
}
