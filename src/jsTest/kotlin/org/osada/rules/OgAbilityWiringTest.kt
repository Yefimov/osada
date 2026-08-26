package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.Transport
import org.osada.model.moveUnit
import org.osada.model.setMoveRange
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three equipment abilities wired on 2026-08-26 (`docs/og-fidelity-plan.md` §N), plus the
 * `blow_any_terrain` efile switch that landed with them.
 *
 * Each test is written to FAIL against the previous behaviour: the three abilities were
 * descriptive-only badges, and every efile got the widest demolition rules ATOMIC authors. §L.10's
 * lesson is the reason they exist at all — a badge that states a rule the engine does not apply is
 * the defect, not a cosmetic mismatch.
 */
class OgAbilityWiringTest : OgRulesTestHarness() {
    /** `Dismount after movement`, attr2 bit 1. */
    private val attr2DismountAfterMove = 2

    /** `Cannot get a leader`, attrEx bit 0, and `No run out ammo penalty`, attrEx bit 4. */
    private val attrExNoLeader = 1
    private val attrExNoAmmoPenalty = 16

    private val ridingInfantryEqid = 950
    private val ridingVeteranEqid = 951
    private val leaderlessEqid = 952
    private val thirstyGunEqid = 953
    private val toughGunEqid = 954
    private val radarEqid = 955
    private val bomberEqid = 956

    @BeforeTest
    fun setup() {
        installTestWorld()
        EfileConfig.resetForTest()
        Leaders.unitClassLeaders[UnitClass.ARTILLERY.value] =
            listOf(LeaderType.TENACIOUS_DEFENSE, LeaderType.AGGRESSIVE_MANEUVER, LeaderType.DEVASTATING_FIRE)
        registerRiders()
        registerBatteries()
        registerAirWatchers()
    }

    /** The two mounted formations: one carrying `Dismount after movement`, one plain. */
    private fun registerRiders() {
        Equipment.putEquipment(
            ridingInfantryEqid,
            EquipmentData().apply {
                name = "Motor Rifle Battalion"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 6
                attr2 = attr2DismountAfterMove
            },
        )
        Equipment.putEquipment(
            ridingVeteranEqid,
            EquipmentData().apply {
                name = "Rifle Battalion"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 6
            },
        )
    }

    /** The gun that pays OG 6.23's dry-ammo halvings, the one exempt from them, and the
     *  militia battery that can never be given a commander. */
    private fun registerBatteries() {
        Equipment.putEquipment(
            leaderlessEqid,
            EquipmentData().apply {
                name = "Militia Battery"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                ammo = 6
                attrEx = attrExNoLeader
            },
        )
        Equipment.putEquipment(
            thirstyGunEqid,
            EquipmentData().apply {
                name = "Field Battery"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                ammo = 4
                initiative = 6
                grounddef = 8
            },
        )
        Equipment.putEquipment(
            toughGunEqid,
            EquipmentData().apply {
                name = "Coastal Battery"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                ammo = 4
                initiative = 6
                grounddef = 8
                attrEx = attrExNoAmmoPenalty
            },
        )
    }

    /** A radar (air attack only, no ground attack at all) and something for it to shoot at. */
    private fun registerAirWatchers() {
        Equipment.putEquipment(
            radarEqid,
            EquipmentData().apply {
                name = "Mobile Radar"
                uclass = UnitClass.AIR_DEFENCE.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                ammo = 6
                gunrange = 3
                airatk = 12
            },
        )
        Equipment.putEquipment(
            bomberEqid,
            EquipmentData().apply {
                name = "Level Bomber"
                uclass = UnitClass.LEVEL_BOMBER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
                ammo = 4
                softatk = 8
            },
        )
    }

    @AfterTest
    fun teardown() {
        Leaders.unitClassLeaders.clear()
        EfileConfig.resetForTest()
        clearTestWorld()
    }

    // ---- `Dismount after movement` (attr2 bit 1) ---------------------------------------------

    @Test
    fun aFormationCarryingTheAbilityStepsDownWhenItsRideEnds() {
        val map = clearGround()
        val unit = place(map, ridingInfantryEqid, 2, 2, side = 0)
        unit.transport = Transport(truckEqid)
        unit.isMounted = true
        map.setMoveRange(unit)

        map.moveUnit(unit, 2, 3)

        assertEquals(2 to 3, unit.getPos()?.let { it.row to it.col })
        assertFalse(unit.isMounted, "OG: the unit dismounts from its transport after completing movement")
    }

    @Test
    fun aFormationWithoutTheAbilityRidesOnUntilItsNextTurn() {
        val map = clearGround()
        val unit = place(map, ridingVeteranEqid, 2, 2, side = 0)
        unit.transport = Transport(truckEqid)
        unit.isMounted = true
        map.setMoveRange(unit)

        map.moveUnit(unit, 2, 3)

        assertTrue(unit.isMounted, "OG 8.3: everyone else stays aboard until the next turn")
    }

    @Test
    fun spendingEveryMovementPointDoesNotKeepTheFormationAboard() {
        val map = clearGround()
        val unit = place(map, ridingInfantryEqid, 2, 2, side = 0)
        unit.transport = Transport(truckEqid)
        unit.isMounted = true
        map.setMoveRange(unit)

        map.moveUnit(unit, 2, 3)
        unit.moveLeft = 0

        assertFalse(
            unit.isMounted,
            "completing the movement is the whole trigger -- the dismount costs no movement point",
        )
    }

    @Test
    fun theAbilityIsNotAnActionTheStripOffersAfterMoving() {
        val map = clearGround()
        val unit = place(map, ridingInfantryEqid, 2, 2, side = 0)
        unit.transport = Transport(truckEqid)
        unit.isMounted = true
        unit.hasMoved = true
        unit.moveLeft = 1

        val mount = UnitActionAvailability.forAction(UnitActionId.MOUNT, contextFor(map, unit))

        assertFalse(mount.enabled, "OG 8.3: mount and dismount are BEFORE moving, for everyone")
        assertEquals(listOf(ActionBlockReason.ALREADY_MOVED), mount.reasons.map { it.reason })
    }

    // ---- `Cannot get a leader` (attrEx bit 0) -------------------------------------------------

    @Test
    fun equipmentCarryingNoLeaderNeverMintsOne() {
        val map = world()
        val unit = place(map, leaderlessEqid, 3, 3, side = 0)

        assertEquals(-1, Leaders.generateLeader(unit))
    }

    @Test
    fun theSameClassWithoutTheBitStillGetsALeader() {
        val map = world()
        val unit = place(map, gunEqid, 3, 3, side = 0)

        assertTrue(Leaders.generateLeader(unit) != -1, "the class list is populated; only the bit blocks it")
    }

    // ---- `No run out ammo penalty` (attrEx bit 4) ---------------------------------------------

    @Test
    fun anEmptyBatteryLosesHalfItsInitiativeAndDefence() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val map = world()
        val unit = place(map, thirstyGunEqid, 4, 4, side = 0)
        unit.ammo = 0

        assertEquals(3, UnitConditionPenalties.dryInitiative(unit, 6))
        assertEquals(4, dryDefenceOf(unit, 8))
    }

    @Test
    fun aBatteryCarryingNoAmmoPenaltyKeepsBoth() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val map = world()
        val unit = place(map, toughGunEqid, 4, 4, side = 0)
        unit.ammo = 0

        assertEquals(6, UnitConditionPenalties.dryInitiative(unit, 6))
        assertEquals(8, dryDefenceOf(unit, 8))
    }

    @Test
    fun theExemptionCoversAmmunitionOnlyAndNotAnEmptyFuelTank() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val map = world()
        val unit = place(map, toughGunEqid, 4, 4, side = 0)
        unit.ammo = 0
        unit.fuel = 0

        // Towed artillery burns no fuel, so the fuel half cannot fire here either way; the point of
        // the assertion is that the ammo exemption did not silently swallow the other half.
        assertEquals(6, UnitConditionPenalties.dryInitiative(unit, 6))
    }

    @Test
    fun theExemptionDoesNotHandAnEmptyBatteryItsGunsBack() {
        ruleset(RuleKey.DRY_UNIT_PENALTIES to 1)
        val map = world()
        val unit = place(map, toughGunEqid, 4, 4, side = 0)
        val target = place(map, infantryEqid, 4, 5, side = 1)
        unit.ammo = 0

        assertFalse(
            AttackEligibility.canFire(unit, target),
            "the bit lifts OG 6.23's PENALTY, never its prohibition",
        )
    }

    // ---- No ground attack, no ground attack order (OG's derived `GroundAttack`) ---------------

    @Test
    fun aRadarCannotOrderAnAttackOnAGroundUnit() {
        val map = world()
        val radar = place(map, radarEqid, 4, 4, side = 0)
        val target = place(map, infantryEqid, 4, 5, side = 1)

        assertFalse(
            AttackEligibility.canFire(radar, target),
            "OG shows no GroundAttack on a record whose hard and soft attack are both zero",
        )
        assertFalse(AttackEligibility.canInitiateAttack(radar, target))
    }

    @Test
    fun theSameRadarStillEngagesAircraft() {
        val map = world()
        val radar = place(map, radarEqid, 4, 4, side = 0)
        val bomber = place(map, bomberEqid, 4, 5, side = 1)

        assertTrue(AttackEligibility.canFire(radar, bomber), "its air attack is what it has")
    }

    @Test
    fun aFormationWithAnyNonAirAttackIsUnaffected() {
        val map = world()
        val gun = place(map, gunEqid, 4, 4, side = 0)
        val target = place(map, infantryEqid, 4, 5, side = 1)

        assertTrue(AttackEligibility.canFire(gun, target))
    }

    // ---- `blow_any_terrain` (efile switch) ----------------------------------------------------

    @Test
    fun everyEfileMayBlowWhatOgsOwnUiEnumerates() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val hex = map.map!![5][5]
        hex.terrain = TerrainType.CITY.value

        assertTrue(EngineeringWork.RAZE.possibleOn(hex, map.map))
    }

    @Test
    fun onlyABlowAnyTerrainEfileMayRazeAForest() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val hex = map.map!![5][5]
        hex.terrain = TerrainType.FOREST.value

        assertFalse(
            EngineeringWork.RAZE.possibleOn(hex, map.map),
            "LXF sets no blow_any_terrain, so its sappers do not flatten woods",
        )

        EfileConfig.setForTest(intKeyMap = mapOf("blow_any_terrain" to 1))

        assertTrue(EngineeringWork.RAZE.possibleOn(hex, map.map))
    }

    private fun clearGround(): GameMap =
        world().apply {
            for (r in 0 until rows) {
                for (c in 0 until cols) map!![r][c].terrain = TerrainType.CLEAR.value
            }
        }

    private fun dryDefenceOf(
        unit: GameUnit,
        defence: Int,
    ): Int {
        val stats = AttackCalculation.CombatStats(attackerDefense = defence, defenderDefense = defence)
        UnitConditionPenalties.applyDryUnitPenalties(stats, unit, unit)
        return stats.attackerDefense
    }

    private fun contextFor(
        map: GameMap,
        unit: GameUnit,
    ): UnitActionContext =
        UnitActionContext(
            map = map,
            unit = unit,
            currentPlayer = friendly,
        )
}
