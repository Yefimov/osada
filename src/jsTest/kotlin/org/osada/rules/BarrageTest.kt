package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Open General's **Barrage** (manual §9.2, `docs/og-fidelity-plan.md` §R) — shelling a hex nobody
 * can see.
 *
 * The three outcomes asserted here are the three the game's own `tips1.txt` names: strength, fuel
 * and ammunition off a hidden enemy; a city, airfield, port or bridge reduced to rubble until
 * repaired; and an empty hex left harder to move through.
 *
 * The success roll goes through [GameRandomSource] — OG says only *"sometimes"* — so the tests fire
 * until a shell lands rather than asserting on one roll. That shared stream is also what keeps two
 * multiplayer peers agreeing about which barrages landed.
 */
class BarrageTest : OgRulesTestHarness() {
    private val howitzerEqid = 970
    private val infantryHiddenEqid = 971

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            howitzerEqid,
            EquipmentData().apply {
                name = "Heavy Howitzer"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                gunrange = 4
                ammo = 8
                softatk = 10
                spotrange = 1
                // OG's Bomber Size: the `'='` mark, and the whole per-record gate.
                bombsize = 150
            },
        )
        Equipment.putEquipment(
            infantryHiddenEqid,
            EquipmentData().apply {
                name = "Concealed Rifles"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 3
                ammo = 6
                fuel = 4
                spotrange = 1
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun theRuleIsOffUntilBothTheProfileAndTheScenarioAllowIt() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        assertFalse(Barrage.enabled(), "off in every profile but Open General Fidelity")
        assertFalse(Barrage.canBarrage(gun))

        ruleset(RuleKey.BARRAGE to 1)
        assertTrue(Barrage.canBarrage(gun), "an unimported scenario follows the key alone")

        GameHolder.instance?.scenario?.barrageAllowed = false
        assertFalse(Barrage.canBarrage(gun), "OG: the designer must enable it")
    }

    @Test
    fun onlyARecordWithBomberSizeMayBarrage() {
        val map = barrageWorld()
        val gun = place(map, gunEqid, 2, 2, side = 0)

        assertFalse(Barrage.canBarrage(gun), "the fixture battery has no Bomber Size")
    }

    @Test
    fun aSpottedHexIsNotABarrageTarget() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][4].setSpotted(0, true)

        assertFalse(
            Barrage.canTarget(map, gun, Cell(2, 4)),
            "a hex the firer can see is one it can attack properly",
        )
        assertTrue(Barrage.canTarget(map, gun, Cell(2, 5)), "an unspotted hex in range is the mechanic")
    }

    @Test
    fun aHexBeyondGunRangeIsNotATarget() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        assertFalse(Barrage.canTarget(map, gun, Cell(7, 7)))
    }

    @Test
    fun aSuccessfulBarrageOnAHiddenEnemyTakesStrengthAmmoAndFuel() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        val victim = place(map, infantryHiddenEqid, 2, 5, side = 1)
        val strengthBefore = victim.strength
        val ammoBefore = victim.ammo
        val fuelBefore = victim.fuel
        GameRandomSource.start(1)

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.hit)
        assertEquals(strengthBefore - Barrage.STRENGTH_DAMAGE, victim.strength)
        assertEquals(ammoBefore - Barrage.SUPPLY_DAMAGE, victim.ammo)
        assertEquals(fuelBefore - Barrage.SUPPLY_DAMAGE, victim.fuel)
        assertFalse(victim.getHex()!!.isSpotted(0), "being shelled is not being spotted")
    }

    @Test
    fun aSuccessfulBarrageReducesACityToRubbleTheEngineersCanRepair() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.CITY.value

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.wreckedTerrain)
        assertEquals(TerrainType.CLEAR.value, map.map!![2][5].terrain)
        assertEquals(
            TerrainType.CITY.value,
            map.map!![2][5].razedTerrain,
            "the same record Repair reads, so 'unusable until Repaired' is literally true",
        )
    }

    @Test
    fun aSuccessfulBarrageDropsABridge() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.RIVER.value
        map.map!![2][5].road = PARTIAL_ROAD_MASK

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.blewBridge)
        assertEquals(RoadType.NONE.value, map.map!![2][5].road)
        assertEquals(PARTIAL_ROAD_MASK, map.map!![2][5].blownRoad, "Repair puts back the mask that fell")
    }

    @Test
    fun clearGroundIsNotTurnedIntoACrater() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.CLEAR.value

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertFalse(result.leftRubble, "OG destroys facilities and roads, not open ground")
        assertFalse(map.map!![2][5].rubble)
        assertEquals(TerrainType.CLEAR.value, map.map!![2][5].terrain)
    }

    @Test
    fun woodsAreOnlyChurnedWhereTheEfileAllowsIt() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.FOREST.value

        assertFalse(fireUntilItLands(map, gun, Cell(2, 5)).wreckedTerrain, "LXF sets no blow_any_terrain")

        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))
        assertTrue(
            fireUntilItLands(map, gun, Cell(2, 5)).wreckedTerrain,
            "ATOMIC and BASEKORP authorise it, and barrage reads the same switch Can Blow does",
        )
    }

    @Test
    fun wreckageCostsMovementAndRepairClearsIt() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.CITY.value
        fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(map.map!![2][5].rubble, "a wrecked city is harder to cross than the ground under it")

        val walker = place(map, infantryEqid, 4, 4, side = 0)
        val clearCost = costTo(map, walker, 4, 5)
        map.map!![4][5].rubble = true
        assertEquals(clearCost + Barrage.RUBBLE_MOVE_SURCHARGE, costTo(map, walker, 4, 5))
    }

    @Test
    fun aBarrageTakesEntrenchmentOffTheUnitItLandsOn() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        val victim = place(map, infantryHiddenEqid, 2, 5, side = 1)
        victim.entrenchment = 3

        fireUntilItLands(map, gun, Cell(2, 5))

        assertEquals(
            3 - Barrage.ENTRENCHMENT_DAMAGE,
            victim.entrenchment,
            "Open General School theme 5 states the figure: barrage costs 2 entrenchment",
        )
    }

    @Test
    fun theShotIsSpentWhetherOrNotItLands() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        val ammoBefore = gun.getAmmo()

        Barrage.resolve(map, gun, Cell(2, 5))

        assertEquals(ammoBefore - Barrage.AMMO_COST, gun.getAmmo())
        assertTrue(gun.hasFired, "a miss must not be a free retry -- that would be a hidden-unit detector")
        assertFalse(Barrage.ready(gun))
    }

    /** The rule plus a map with nothing spotted: the state a barrage is actually ordered from. */
    private fun barrageWorld(): GameMap {
        ruleset(RuleKey.BARRAGE to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        return map
    }

    /** Fires until the roll lands. `resolve` spends ammunition and the shot each time, so the gun's
     *  turn state is reset between attempts — the test is about the OUTCOME, not the odds. */
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
