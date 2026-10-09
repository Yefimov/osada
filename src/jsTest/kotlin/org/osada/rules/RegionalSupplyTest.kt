package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.rules.ruleset.RuleKey
import org.osada.uiSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OSADA's regional supply (`rules/RegionalSupply`, schema 23): the route back to a source. */
class RegionalSupplyTest : OgRulesTestHarness() {
    private var fogWasOff = false

    @BeforeTest
    fun setup() {
        installTestWorld()
        fogWasOff = uiSettings.noFOW
        uiSettings.noFOW = false
    }

    @AfterTest
    fun teardown() {
        uiSettings.noFOW = fogWasOff
        clearTestWorld()
    }

    private fun city(
        map: GameMap,
        row: Int,
        col: Int,
        ownerId: Int = friendly.id,
    ) {
        map.map!![row][col].apply {
            terrain = TerrainType.CITY.value
            owner = ownerId
        }
    }

    private fun seeEverything(map: GameMap) = map.map!!.forEach { row -> row.forEach { it.setSpotted(0, true) } }

    @Test
    fun standingOnOrNearAFriendlyCityIsFullSupply() {
        val map = world()
        GameHolder.instance = holderFor(map)
        city(map, 0, 3)
        val onCity = place(map, sapperEqid, 0, 3, 0)
        assertEquals(RegionalSupply.Access(100, 0), RegionalSupply.access(map, onCity))
        val near = place(map, sapperEqid, 0, 0, 0)
        // Two clear hexes (2 each) and the city itself (1): within the free distance.
        assertEquals(5, RegionalSupply.routeCost(map, near))
        assertEquals(100, RegionalSupply.access(map, near).percent)
    }

    @Test
    fun aLongRouteLetsLessThrough() {
        val map = world()
        GameHolder.instance = holderFor(map)
        city(map, 0, 7)
        val far = place(map, sapperEqid, 0, 0, 0)
        val cost = RegionalSupply.routeCost(map, far)!!
        assertEquals(13, cost, "six clear hexes and the city")
        assertEquals(
            100 - (13 - RegionalSupply.FULL_ACCESS_COST) * RegionalSupply.FADE_PERCENT_PER_COST,
            RegionalSupply.percentFor(cost),
        )
    }

    @Test
    fun noFriendlySourceLeavesOnlyTheEmergencyFloor() {
        val map = world()
        GameHolder.instance = holderFor(map)
        city(map, 0, 2, ownerId = hostile.id)
        val unit = place(map, sapperEqid, 0, 0, 0)
        assertNull(RegionalSupply.routeCost(map, unit), "an enemy city feeds nobody here")
        assertEquals(RegionalSupply.EMERGENCY_PERCENT, RegionalSupply.access(map, unit).percent)
    }

    @Test
    fun aKnownEnemyMakesTheRouteDearerAndARingCutsIt() {
        val map = world()
        GameHolder.instance = holderFor(map)
        city(map, 4, 7)
        val unit = place(map, sapperEqid, 4, 3, 0)
        val clear = RegionalSupply.routeCost(map, unit)!!

        // Every shortest way from column 3 to column 7 crosses column 5 beside or on this hex.
        val raider = place(map, infantryEqid, 4, 5, 1)
        assertEquals(clear, RegionalSupply.routeCost(map, unit), "an enemy nobody has seen interdicts nothing")
        seeEverything(map)
        assertTrue(RegionalSupply.routeCost(map, unit)!! > clear, "a known enemy forces a dearer way round")

        map.map!![raider.getPos()!!.row][raider.getPos()!!.col].delUnit(raider)
        map.units.remove(raider)
        HexGeometry.getAdjacent(4, 3).forEach { place(map, infantryEqid, it.row, it.col, 1) }
        seeEverything(map)
        assertNull(RegionalSupply.routeCost(map, unit), "encircled")
    }

    @Test
    fun theRuleCapsGroundResupplyOnlyWhenSwitchedOn() {
        val map = world()
        GameHolder.instance = holderFor(map)
        city(map, 0, 7, ownerId = hostile.id)
        val unit = place(map, sapperEqid, 4, 3, 0).apply { ammo = 0 }

        ruleset(RuleKey.REGIONAL_SUPPLY to 0)
        val local = SupplyRules.getResupplyValue(map, unit).ammo
        ruleset(RuleKey.REGIONAL_SUPPLY to 1)
        val regional = SupplyRules.getResupplyValue(map, unit).ammo
        assertTrue(regional < local, "cut off: $regional against $local")
        assertEquals(RegionalSupply.EMERGENCY_PERCENT, SupplyContextRules.getSupplyContext(map, unit).regionalPercent)
    }
}
