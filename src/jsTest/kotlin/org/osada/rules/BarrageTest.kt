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
import org.osada.model.fireBarrage
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Open General's **Barrage** (manual §9.2) — shelling any hex in reach.
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
    private val bomberEqid = 972

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
        putBomber()
    }

    private fun putBomber() =
        Equipment.putEquipment(
            bomberEqid,
            EquipmentData().apply {
                name = "Strategic Bomber"
                uclass = UnitClass.LEVEL_BOMBER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
                gunrange = 0
                ammo = 4
                fuel = 40
                spotrange = 1
                bombsize = 500
            },
        )

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun theRuleIsOffUntilBothTheProfileAndTheScenarioAllowIt() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        assertFalse(Barrage.enabled(), "no ruleset resolved: the call-site default is off")
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

    /** Owner's decision, 2026-10-14: any hex in reach, seen or unseen, empty or occupied. */
    @Test
    fun anyHexWithinGunRangeIsATarget() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][4].setSpotted(0, true)
        map.map!![3][4].setSpotted(0, true)
        place(map, infantryHiddenEqid, 3, 4, side = 1)
        place(map, infantryEqid, 2, 3, side = 0)
        place(map, infantryHiddenEqid, 4, 2, side = 1)

        assertTrue(Barrage.canTarget(map, gun, Cell(2, 4)), "visible empty ground")
        assertTrue(Barrage.canTarget(map, gun, Cell(2, 5)), "unseen empty ground")
        assertTrue(Barrage.canTarget(map, gun, Cell(3, 4)), "a visible enemy")
        assertTrue(Barrage.canTarget(map, gun, Cell(4, 2)), "an unseen enemy")
        assertTrue(Barrage.canTarget(map, gun, Cell(2, 3)), "a hex a friendly unit holds")
        assertFalse(Barrage.canTarget(map, gun, Cell(2, 2)), "a gun does not shell its own hex")
        val ring = HexGeometry.getRing(2, 2, 4, map.rows, map.cols, true).map { it.row to it.col }.toSet()
        assertEquals(ring, Barrage.targets(map, gun).map { it.row to it.col }.toSet(), "the overlay is the whole reach")
    }

    /** A stored schema-17 grade still reads as on, and means the same thing. */
    @Test
    fun theOldAimedGradeTargetsExactlyWhatOnDoes() {
        val map = barrageWorld(RuleKey.BARRAGE to 2)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![3][4].setSpotted(0, true)
        place(map, infantryHiddenEqid, 3, 4, side = 1)

        assertTrue(Barrage.canTarget(map, gun, Cell(3, 4)))
    }

    @Test
    fun ammunitionAndTheSpentShotStillGateEveryTarget() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        gun.ammo = 0
        assertFalse(Barrage.canTarget(map, gun, Cell(2, 4)), "no ammunition, no barrage")
        assertTrue(Barrage.targets(map, gun).isEmpty())

        gun.ammo = 8
        gun.hasFired = true
        assertFalse(Barrage.canTarget(map, gun, Cell(2, 4)), "the shot is already spent")
    }

    @Test
    fun theOrderIsRevalidatedWhenItIsFired() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        val ammoBefore = gun.getAmmo()

        assertEquals(null, map.fireBarrage(gun, 7, 7), "out of reach")
        assertEquals(ammoBefore, gun.getAmmo(), "a refused order costs nothing")
        assertFalse(gun.hasFired)
    }

    @Test
    fun aVisibleEnemyIsHitLikeAHiddenOne() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        val victim = place(map, infantryHiddenEqid, 2, 4, side = 1)
        map.map!![2][4].setSpotted(0, true)
        val strengthBefore = victim.strength

        fireUntilItLands(map, gun, Cell(2, 4))

        assertEquals(strengthBefore - Barrage.STRENGTH_DAMAGE, victim.strength)
    }

    @Test
    fun aFriendlyUnitInTheTargetHexIsNeverHit() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        val friend = place(map, infantryHiddenEqid, 2, 4, side = 0)
        friend.entrenchment = 3
        val strengthBefore = friend.strength
        val ammoBefore = friend.ammo

        val result = fireUntilItLands(map, gun, Cell(2, 4))

        assertEquals(0, result.strengthLost)
        assertEquals(strengthBefore, friend.strength)
        assertEquals(ammoBefore, friend.ammo)
        assertEquals(3, friend.entrenchment)
    }

    @Test
    fun aBomberBarragesTheHexBeneathIt() {
        val map = barrageWorld()
        val bomber = place(map, bomberEqid, 2, 2, side = 0)
        map.map!![2][2].setSpotted(0, true)

        assertTrue(Barrage.canTarget(map, bomber, Cell(2, 2)), "aircraft bomb the hex they fly over")
        assertFalse(Barrage.canTarget(map, bomber, Cell(2, 3)), "and nothing else, whatever its gun range")
        assertEquals(listOf(2 to 2), Barrage.targets(map, bomber).map { it.row to it.col })
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

    /** The outcome OG's own barrage does not have: shelling interdicts a railway
     *  (`rules/EngineeringWork.BLOW_RAIL` carries the sourcing). */
    @Test
    fun aSuccessfulBarrageCutsARailwayLine() {
        val map = barrageWorld(RuleKey.RAIL_DEMOLITION to 1)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].rail = PARTIAL_ROAD_MASK
        map.map!![2][5].station = true

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.cutRail)
        assertEquals(RoadType.NONE.value, map.map!![2][5].rail, "no train and no rail route through it")
        assertEquals(PARTIAL_ROAD_MASK, map.map!![2][5].blownRail, "Repair relays the track that fell")
        assertFalse(map.map!![2][5].station, "the depot goes with the rails")
    }

    /** With OSADA's own rail rule off, a shelled railway is untouched — which is OG's behaviour and
     *  what Open General Fidelity must keep. */
    @Test
    fun aRailwayIsUntouchedWhileTheRailRuleIsOff() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].rail = PARTIAL_ROAD_MASK

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertFalse(result.cutRail)
        assertEquals(PARTIAL_ROAD_MASK, map.map!![2][5].rail, "the line is map data again")
        assertEquals(0, map.map!![2][5].blownRail)
    }

    /** Track first, terrain second: the city is still there for the next barrage, and the more
     *  useful thing goes first. */
    @Test
    fun aRailwayThroughACityLosesItsTrackBeforeTheCity() {
        val map = barrageWorld(RuleKey.RAIL_DEMOLITION to 1)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.CITY.value
        map.map!![2][5].rail = PARTIAL_ROAD_MASK

        val first = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(first.cutRail)
        assertEquals(TerrainType.CITY.value, map.map!![2][5].terrain, "the city stands until the next shot")

        val second = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(second.wreckedTerrain, "and with the rails down, the shells reach it")
    }

    @Test
    fun clearGroundIsNotTurnedIntoRubble() {
        val map = barrageWorld(RuleKey.CRATERS to 0)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertFalse(result.leftRubble, "OG destroys facilities and roads, not open ground")
        assertFalse(map.map!![2][5].rubble)
        assertFalse(map.map!![2][5].crater, "and with the crater rule off, nothing at all")
        assertEquals(TerrainType.CLEAR.value, map.map!![2][5].terrain)
    }

    /**
     * Perekop, 2026-10-14: `eqp-atomic` sets `blow_any_terrain`, which lets a SAPPER raze clear
     * ground into rubble. Barrage read that set before trying to crater, so every landed shell on
     * an ATOMIC field left invisible ruins and never a crater.
     */
    @Test
    fun blowAnyTerrainDoesNotTurnShelledOpenGroundIntoRuins() {
        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))
        val map = barrageWorld(RuleKey.CRATERS to 0)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][4].terrain = TerrainType.SAND.value

        listOf(Cell(2, 5), Cell(2, 4)).forEach { cell ->
            val hex = map.map!![cell.row][cell.col]
            val terrain = hex.terrain
            val result = fireUntilItLands(map, gun, cell)
            assertFalse(result.wreckedTerrain || result.leftRubble, "nothing fabricated on $terrain")
            assertFalse(hex.rubble)
            assertEquals(-1, hex.razedTerrain)
            assertEquals(terrain, hex.terrain)
        }
        assertTrue(
            TerrainType.CLEAR.value in EngineeringWork.razeableTerrain(),
            "a sapper's demolition set is engineering's, and stays as the efile authored it",
        )
    }

    @Test
    fun blowAnyTerrainOpenGroundIsCrateredWhenCratersAreOn() {
        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))
        val map = barrageWorld(RuleKey.CRATERS to 1)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.leftCrater)
        assertTrue(map.map!![2][5].crater)
        assertFalse(map.map!![2][5].rubble)
    }

    @Test
    fun blowAnyTerrainStillWrecksRealFacilities() {
        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))
        val map = barrageWorld(RuleKey.CRATERS to 1)
        val gun = place(map, howitzerEqid, 2, 2, side = 0)
        map.map!![2][5].terrain = TerrainType.AIRFIELD.value

        val result = fireUntilItLands(map, gun, Cell(2, 5))

        assertTrue(result.wreckedTerrain)
        assertEquals(TerrainType.AIRFIELD.value, map.map!![2][5].razedTerrain)
        assertFalse(map.map!![2][5].crater)
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

    /** The odds are what a player decides on, so the chip states them -- from the same constant
     *  the roll uses. */
    @Test
    fun theBarrageChipStatesTheHitChance() {
        val map = barrageWorld()
        val gun = place(map, howitzerEqid, 2, 2, side = 0)

        val chip = UnitActionAvailability.forAction(UnitActionId.BARRAGE, UnitActionContext(map, gun, friendly))

        assertEquals(60, Barrage.HIT_CHANCE_PERCENT)
        assertEquals(
            Barrage.HIT_CHANCE_PERCENT,
            chip.effects.first { it.kind == ActionEffectKind.BARRAGE_HIT_CHANCE }.amount,
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

    /** The rule on, plus a map with nothing spotted. [overrides] adds the OSADA-owned keys a test
     *  is about. */
    private fun barrageWorld(vararg overrides: Pair<RuleKey, Int>): GameMap {
        ruleset(RuleKey.BARRAGE to 1, *overrides)
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
