package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.beginEngineering
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG 9.3.6's railroad station — the fifth facility, built 2026-08-27
 * (`docs/og-fidelity-plan.md` §U).
 *
 * > *"To build a railroad station, a unit with the Sapper ability must be in a rail hex and hasn't
 * > done any action... The construction of a railroad station costs 18 PP."*
 *
 * The two conditions OG states exactly are the two asserted hardest here: the hex must carry rail,
 * and 18 prestige is the manual's own number rather than one chosen to match the other four.
 */
class RailStationTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun railWorld(prestige: Int = 100): GameMap {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = prestige)
        GameHolder.instance = holderFor(map)
        return map
    }

    @Test
    fun aSapperOnARailHexMayBuildAStation() {
        val map = railWorld()
        map.map!![2][2].rail = 1
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertTrue(EngineeringWork.STATION in Engineering.availableWork(sapper))
    }

    @Test
    fun thereIsNothingToBuildOffTheTrack() {
        val map = railWorld()
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(
            EngineeringWork.STATION in Engineering.availableWork(sapper),
            "OG's condition is quoted: the unit must be in a RAIL hex",
        )
    }

    @Test
    fun aHexThatAlreadyHasAStationOffersNothing() {
        val map = railWorld()
        map.map!![2][2].rail = 1
        map.map!![2][2].station = true
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(EngineeringWork.STATION in Engineering.availableWork(sapper))
    }

    @Test
    fun theStationCostsTheManualsOwnEighteenPrestige() {
        assertEquals(18, EngineeringWork.STATION.cost, "quoted from OG 9.3.6, not chosen")
    }

    @Test
    fun finishingTheWorkRaisesAStationAndLeavesTheTrackAlone() {
        val map = railWorld()
        map.map!![2][2].rail = 1
        map.map!![2][2].terrain = TerrainType.CLEAR.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        map.beginEngineering(sapper, EngineeringWork.STATION)

        repeat(EngineeringWork.STATION.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }

        assertTrue(map.map!![2][2].station, "the station is up")
        assertEquals(1, map.map!![2][2].rail, "and the track it was built on is still there")
        assertEquals(
            TerrainType.CLEAR.value,
            map.map!![2][2].terrain,
            "a station is a feature of the hex, not a terrain that replaces it",
        )
    }

    @Test
    fun theStationSurvivesASaveAndReload() {
        val map = railWorld()
        map.map!![2][2].rail = 1
        map.map!![2][2].station = true

        val reloaded = reparse(org.osada.GameStateSerializer.serializeHex(map.map!![2][2]))

        assertEquals(1, reloaded.station, "18 prestige must not be lost to a reload")
    }
}
