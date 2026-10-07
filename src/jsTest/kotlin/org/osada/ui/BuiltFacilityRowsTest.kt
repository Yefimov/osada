package org.osada.ui

import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.TerrainType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.Hex
import org.osada.model.beginEngineering
import org.osada.model.resetEquipment
import org.osada.restoreEngineering
import org.osada.rules.Airbase
import org.osada.rules.Engineering
import org.osada.rules.EngineeringWork
import org.osada.rules.OgRulesTestHarness
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The airfield's three looks (2026-10-07): nothing while the work is early, the bare runway on its
 * last countdown turn, the runway with its tower once complete -- all read from the hex's real
 * construction state, and none of them an Airbase unit.
 */
class BuiltFacilityRowsTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
        EfileConfig.setForTest(emptyMap())
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun anAirfieldShowsTheRunwayOnItsLastTurnAndTheTowerWhenDone() {
        val map = world(prestige = 200)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        val unitsBefore = map.units.size
        map.beginEngineering(sapper, EngineeringWork.AIRFIELD)

        val turns = hex.constructionTurns
        assertTrue(turns > 1, "the default airfield takes several turns")
        assertEquals(emptyList(), BuiltFacilityRows.of(hex, 0), "early work: only the construction outline")

        repeat(turns - 1) { Engineering.advanceTurn(map.map, 0, builderOwner()) }
        assertEquals(1, hex.constructionTurns)
        assertEquals(listOf(BuiltFacilityRows.RUNWAY_WORKS), BuiltFacilityRows.of(hex, 0))
        assertEquals(emptyList(), BuiltFacilityRows.of(hex, 1), "the enemy does not see the site")
        assertEquals(TerrainType.CLEAR.value, hex.terrain, "not an airfield until the work completes")

        // A save taken on the last turn draws the same.
        val restored = Hex(2, 2)
        restoreEngineering(restored, JSON.parse(JSON.stringify(GameStateSerializer.serializeHex(hex))))
        assertEquals(listOf(BuiltFacilityRows.RUNWAY_WORKS), BuiltFacilityRows.of(restored, 0))

        Engineering.advanceTurn(map.map, 0, builderOwner())
        assertEquals(TerrainType.AIRFIELD.value, hex.terrain)
        assertEquals(listOf(BuiltFacilityRows.AIRFIELD), BuiltFacilityRows.of(hex, 0))
        assertEquals(listOf(BuiltFacilityRows.AIRFIELD), BuiltFacilityRows.of(hex, 1), "terrain: everyone sees it")
        assertEquals(unitsBefore, map.units.size, "building an airfield creates no Airbase unit")
        assertTrue(map.units.none { Airbase.isAirbase(it.unitData(true)) })
    }

    @Test
    fun theRunwayAirbaseRecordsShowOgsHangars() {
        Equipment.resetEquipment()
        listOf(465, 600, 9575).forEach { id ->
            val data =
                org.osada.model
                    .EquipmentData()
                    .apply { icon = "resources/units/images/lxf/jkv72.png" }
            Airbase.applyDisplayName(id, data)
            assertEquals("resources/units/images/jkj61.png", data.icon, "record $id")
        }
        val other =
            org.osada.model
                .EquipmentData()
                .apply { icon = "resources/units/images/lxf/jkv72.png" }
        Airbase.applyDisplayName(663, other)
        assertEquals("resources/units/images/lxf/jkv72.png", other.icon, "only the listed records change")
    }
}
