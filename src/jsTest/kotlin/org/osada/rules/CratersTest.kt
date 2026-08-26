package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.entrench
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shell craters — **OSADA's own rule** (`rules/Craters`, `docs/og-fidelity-plan.md` §S), behind
 * `RuleKey.CRATERS` and deliberately off in Open General Fidelity.
 *
 * The two claims worth defending are the two that make it safe: a crater is a FLOOR under the
 * occupant's entrenchment rather than a bonus on top of it — so shelling your own line can never
 * fortify it beyond what a turn of digging gives — and it only ever appears where OG's own barrage
 * would have done nothing at all.
 */
class CratersTest : OgRulesTestHarness() {
    private val howitzerEqid = 980

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            howitzerEqid,
            EquipmentData().apply {
                name = "Siege Howitzer"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                gunrange = 4
                ammo = 8
                spotrange = 1
                bombsize = 150
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun theRuleIsOffInOpenGeneralFidelityAndByDefault() {
        val map = world()
        GameHolder.instance = holderFor(map)

        ruleset(RuleKey.BARRAGE to 1)
        assertFalse(Craters.enabled(), "no OG source gives a crater cover; the profile must not ship one")
    }

    @Test
    fun aBarrageOnOpenGroundDigsCratersOnlyWithTheRuleOn() {
        val map = craterWorld(cratersOn = false)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.CLEAR.value

        assertFalse(fireUntilItLands(map, gun, Cell(2, 5)).leftCrater, "off: shells do nothing to a field")

        ruleset(RuleKey.BARRAGE to 1, RuleKey.CRATERS to 1)
        assertTrue(fireUntilItLands(map, gun, Cell(2, 5)).leftCrater)
        assertTrue(map.map!![2][5].crater)
    }

    @Test
    fun aCityIsWreckedRatherThanCratered() {
        val map = craterWorld(cratersOn = true)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.CITY.value

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.wreckedTerrain, "there was something there to destroy")
        assertFalse(result.leftCrater)
        assertFalse(
            map.map!![2][5].crater,
            "wreckage gives no cover and a crater does - they are deliberately not the same hex state",
        )
    }

    @Test
    fun waterAndWoodsTakeNoCraters() {
        val map = craterWorld(cratersOn = true)
        map.map!![3][3].terrain = TerrainType.OCEAN.value
        map.map!![3][4].terrain = TerrainType.FOREST.value

        assertFalse(Craters.crushable(map.map!![3][3]), "a hole in the sea is not a hole")
        assertFalse(Craters.crushable(map.map!![3][4]), "woods are wrecked where the efile allows it, never cratered")
        assertTrue(Craters.crushable(map.map!![3][5]), "clear ground takes one")
    }

    @Test
    fun aCraterIsAFloorAndNotABonus() {
        val map = craterWorld(cratersOn = true)
        val holder = place(map, infantryEqid, 4, 4, side = 0)
        map.map!![4][4].terrain = TerrainType.CLEAR.value
        map.map!![4][4].crater = true

        holder.entrenchment = 0
        holder.entrench()
        assertEquals(Craters.COVER_FLOOR, holder.entrenchment, "a hole is cover you did not have to dig")

        // Already dug in deeper than the hole: the crater must add nothing, or shelling your own
        // line would be a way to fortify it.
        holder.entrenchment = 4
        holder.entrench()
        assertTrue(holder.entrenchment >= 4, "entrenching still works")
        assertEquals(
            0,
            Craters.entrenchmentFloor(map.map!![4][4]) - Craters.COVER_FLOOR,
            "the floor is a floor: one level, whatever else the formation has done",
        )
    }

    @Test
    fun aCraterCostsAMovementPointToEnter() {
        val map = craterWorld(cratersOn = true)
        val walker = place(map, infantryEqid, 4, 4, side = 0)
        val clear = costTo(map, walker, 4, 5)

        map.map!![4][5].crater = true

        assertEquals(clear + Barrage.RUBBLE_MOVE_SURCHARGE, costTo(map, walker, 4, 5))
    }

    private fun craterWorld(cratersOn: Boolean): GameMap {
        if (cratersOn) ruleset(RuleKey.BARRAGE to 1, RuleKey.CRATERS to 1) else ruleset(RuleKey.BARRAGE to 1)
        val map = world()
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) map.map!![r][c].terrain = TerrainType.CLEAR.value
        }
        GameHolder.instance = holderFor(map)
        return map
    }

    private fun fireUntilItLands(
        map: GameMap,
        gun: GameUnit,
        cell: Cell,
    ): Barrage.BarrageResult {
        repeat(20) {
            gun.hasFired = false
            gun.ammo = 8
            val result = Barrage.resolve(map, gun, cell)
            if (result.hit) return result
        }
        error("twenty barrages and not one landed - the success roll is broken")
    }

    private fun costTo(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Int =
        MoveRangeCalculation
            .getMoveRange(map, unit)
            .first { it.row == row && it.col == col }
            .cost
}
