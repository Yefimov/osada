package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two of the three Open General OPTIONAL RULES added at schema 6 — Counterbattery (manual §9.4) and
 * Extended LOS (§9.5) — plus the `Dismount` and `No ZOC` attributes wired alongside them. The
 * third, Build and Repair (§9.3), has its own class in [OgEngineeringRulesTest]; the fixture both
 * build on is [OgRulesTestHarness].
 *
 * **The property locked hardest is the same one every rule in this family ships on: with the key
 * off, the mechanic does not exist.** All 502 shipped scenarios run with these three off, so a test
 * that only proved the rules WORK would say nothing about whether shipping them was safe. Each
 * section therefore asserts the off case first.
 */
class OgOptionalRulesTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun tearDown() = clearTestWorld()

    // ---- OG's Dismount toggle (attr bit 11) ----------------------------------------------------

    /**
     * The defect this whole item began from: `UnitCapabilities`' own documentation claimed OSADA
     * read all three of OG's paired toggles, and it read two. Infantry is the class default, so a
     * plain Infantry record dismounts and one carrying the bit does not — `classDefault xor bit`,
     * the same shape `hasSupportFire` and `canOverrun` have had since §I.
     */
    @Test
    fun theDismountToggleReversesTheInfantryDefault() {
        val plain = EquipmentData().apply { uclass = UnitClass.INFANTRY.value }
        val toggled =
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                attr = ATTR_DISMOUNT
            }
        assertTrue(UnitCapabilities.dismountsWhenAttacked(plain), "Infantry dismounts by default")
        assertFalse(UnitCapabilities.dismountsWhenAttacked(toggled), "the bit REVERSES that default")

        val tank = EquipmentData().apply { uclass = UnitClass.TANK.value }
        val toggledTank =
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                attr = ATTR_DISMOUNT
            }
        assertFalse(UnitCapabilities.dismountsWhenAttacked(tank))
        assertTrue(UnitCapabilities.dismountsWhenAttacked(toggledTank), "and reverses it the other way too")
    }

    /** Unlike the three optional rules below, this one is UNIVERSAL — it corrects an approximation
     *  rather than adding a rule, so it has no key to be off. */
    @Test
    fun theDismountToggleNeedsNoRulesetKey() {
        val toggled =
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                attr = ATTR_DISMOUNT
            }
        assertFalse(UnitCapabilities.dismountsWhenAttacked(toggled), "no key is consulted")
    }

    // ---- OG's No ZOC (attr2 bit 6) --------------------------------------------------------------

    /**
     * `OG_ABILITY_AUDIT.md` filed this as *"no — and not representable"*. The ruling is superseded,
     * and the proof is that the hex's own reference count never sees the unit at all.
     */
    @Test
    fun aNoZocUnitProjectsNoZoneOfControl() {
        val map = world()
        val ordinary = place(map, infantryEqid, 2, 2, side = 1)
        assertTrue(map.map!![2][3].isZOC(1), "an ordinary formation projects a ZOC onto its neighbours")

        Equipment.putEquipment(
            infantryEqid + 50,
            EquipmentData().apply {
                name = "Partisan Band"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                attr2 = ATTR2_NO_ZOC
            },
        )
        val ghost = place(map, infantryEqid + 50, 5, 5, side = 1)
        assertFalse(map.map!![5][4].isZOC(1), "a No ZOC record projects none")
        assertTrue(UnitCapabilities.projectsZoneOfControl(ordinary))
        assertFalse(UnitCapabilities.projectsZoneOfControl(ghost))
    }

    // ---- Counterbattery (OG 9.4) ----------------------------------------------------------------

    @Test
    fun counterbatteryDoesNothingWithTheKeyOff() {
        val map = world()
        val enemyGun = place(map, gunEqid, 2, 2, side = 1)
        val ourGun = place(map, gunEqid, 2, 4, side = 0)
        val victim = place(map, infantryEqid, 2, 3, side = 0)

        assertFalse(CounterBatteryFire.enabled())
        assertEquals(emptyList(), CounterBatteryFire.respondersTo(map, enemyGun, victim))
        assertFalse(ourGun.hasFired)
    }

    @Test
    fun aBatteryInRangeAnswersEnemyArtilleryAndSpendsItsShot() {
        ruleset(RuleKey.COUNTERBATTERY to 1)
        val map = world()
        val enemyGun = place(map, gunEqid, 2, 2, side = 1)
        val ourGun = place(map, gunEqid, 2, 4, side = 0)
        val victim = place(map, infantryEqid, 2, 3, side = 0)

        val responders = CounterBatteryFire.respondersTo(map, enemyGun, victim)
        assertEquals(listOf(ourGun.id), responders.map { it.id })

        val events = CounterBatteryFire.applyCounterBattery(map, enemyGun, responders)
        assertEquals(1, events.size)
        assertTrue(ourGun.hasFired, "OG's \"once per turn\" is the answering gun's own shot")
        assertTrue(events.single().losses >= 0)
    }

    /** OG's sentence names *enemy ARTILLERY units*, so nothing answers a rifle division. */
    @Test
    fun counterbatteryOnlyAnswersArtillery() {
        ruleset(RuleKey.COUNTERBATTERY to 1)
        val map = world()
        val enemyInfantry = place(map, infantryEqid, 2, 2, side = 1)
        place(map, gunEqid, 2, 4, side = 0)
        val victim = place(map, infantryEqid, 2, 3, side = 0)

        assertEquals(emptyList(), CounterBatteryFire.respondersTo(map, enemyInfantry, victim))
    }

    /** A battery that has already fired this turn has nothing left to answer with. */
    @Test
    fun aSpentBatteryDoesNotAnswer() {
        ruleset(RuleKey.COUNTERBATTERY to 1)
        val map = world()
        val enemyGun = place(map, gunEqid, 2, 2, side = 1)
        val ourGun = place(map, gunEqid, 2, 4, side = 0)
        val victim = place(map, infantryEqid, 2, 3, side = 0)
        ourGun.hasFired = true

        assertEquals(emptyList(), CounterBatteryFire.respondersTo(map, enemyGun, victim))
    }

    // ---- Extended LOS (OG 9.5) -------------------------------------------------------------------

    @Test
    fun extendedLosDoesNothingWithTheKeyOff() {
        val map = world()
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        assertFalse(ExtendedLos.enabled())
        assertTrue(
            ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4),
            "with the key off a mountain in the way blocks nothing, as it always did",
        )
    }

    @Test
    fun closedTerrainCutsTheLineOfSight() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        assertTrue(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4), "clear ground blocks nothing")
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        assertFalse(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4), "a mountain between them does")
    }

    /** Endpoints never block, so a unit standing in a forest can still see out of it, and an
     *  adjacent pair can always see each other. */
    @Test
    fun theEndpointsAndAdjacencyAreNeverBlocked() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        map.map!![2][2].terrain = TerrainType.FOREST.value
        map.map!![2][3].terrain = TerrainType.CITY.value
        assertTrue(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 3), "adjacent: nothing is in between")
        assertTrue(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 2), "a hex never blocks itself")
    }

    /** `Cut LOS` and `Allow LOF` are read on the line of FIRE, never on the spotting counters —
     *  the reference-count symmetry `ExtendedLos`' header explains. */
    @Test
    fun cutLosBlocksFireAndAllowLofLetsItThrough() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        val shooter = place(map, gunEqid, 2, 2, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)
        assertTrue(AttackEligibility.isInAttackRange(shooter, target), "an empty hex between them is clear")

        Equipment.putEquipment(
            infantryEqid + 60,
            EquipmentData().apply {
                name = "Smoke Company"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                attr2 = ATTR2_CUT_LOS
            },
        )
        place(map, infantryEqid + 60, 2, 3, side = 1)
        assertFalse(AttackEligibility.isInAttackRange(shooter, target), "a Cut LOS unit blocks the shot")

        Equipment.putEquipment(
            infantryEqid + 60,
            EquipmentData().apply {
                name = "Smoke Company"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                attr2 = ATTR2_CUT_LOS or ATTR2_ALLOW_LOF
            },
        )
        assertTrue(
            AttackEligibility.isInAttackRange(shooter, target),
            "Allow LOF beats Cut LOS where a record carries both",
        )
    }

    // ---- the four defects found in review, 2026-08-25 ------------------------------------------

    /**
     * An on-map upgrade must leave the ZOC **and SPOTTING** reference counts exactly as it found
     * them.
     *
     * `projectsZoneOfControl` reads the unit's real equipment, and `GameUnit.upgrade` replaces
     * `eqid` in place — so before `UnitDeployOperations.upgradeUnit` bracketed it, upgrading from
     * an ordinary record to a `No ZOC` one left a zone of control nobody projected, and the
     * reverse decremented counts the unit had never added. Both directions are asserted, because
     * only one of them shows up as a stuck count and the other as a hole.
     *
     * Spotting is bracketed by the same pair of calls and is asserted here for the same reason:
     * `spotrange` has been equipment-derived since the port began, so two records that disagree
     * about it strand the vision counter exactly as `No ZOC` strands the ZOC one — and a stranded
     * spotting add is the worse of the two, because `Hex.clearSpotted` exists precisely because an
     * unmatched add leaves the fog permanently lifted. Testing only ZOC would have left the older
     * and more damaging half of the bracket unlocked.
     */
    @Test
    fun upgradingOnTheMapLeavesTheZocAndSpottingCountsConsistent() {
        val map = world(prestige = 10_000)
        val ghostEqid = infantryEqid + 70
        Equipment.putEquipment(
            ghostEqid,
            EquipmentData().apply {
                name = "Partisan Band"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                cost = 10
                // No ZOC, and blind: the two counters this upgrade has to hand back intact.
                attr2 = ATTR2_NO_ZOC
                spotrange = 0
            },
        )
        val unit = place(map, infantryEqid, 3, 3, side = 0)
        // Two hexes out: inside the rifle division's spot range of 4 and outside a blind record's.
        val watched = map.map!![3][5]
        assertTrue(map.map!![3][4].isZOC(0), "an ordinary formation projects a ZOC to start with")
        assertTrue(watched.isSpotted(0), "and sees out to its spot range")

        map.unitDeployOperations.upgradeUnit(unit.id, ghostEqid, -1)
        assertFalse(map.map!![3][4].isZOC(0), "upgrading INTO No ZOC takes the old zone down")
        assertFalse(watched.isSpotted(0), "and the old vision goes down with it")

        map.unitDeployOperations.upgradeUnit(unit.id, infantryEqid, -1)
        assertTrue(map.map!![3][4].isZOC(0), "and upgrading back out puts exactly one back")
        assertTrue(watched.isSpotted(0), "along with exactly one spotting add")

        // The counts themselves, not just their truthiness: a stranded add reads as true here too.
        map.map!![3][4].setZOC(0, false)
        assertFalse(map.map!![3][4].isZOC(0), "one removal is enough, so exactly one add was live")
        watched.setSpotted(0, false)
        assertFalse(watched.isSpotted(0), "and the same for vision")
    }

    /**
     * `equipment_toggles` is a KEY because reading OG's toggles is not a free correction: it hands
     * phased movement to 4,998 shipped records and takes overrun off 211 tanks. With it off both
     * helpers must report the class answer, or the badge and the rule part company again.
     */
    @Test
    fun theEquipmentTogglesKeyGatesBothHelpers() {
        val reconWithBit =
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                attr = ATTR_RECON_SKILL
            }
        val infantryWithBit =
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                attr = ATTR_RECON_SKILL
            }
        val tankWithBit =
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                attrEx = ATTR_EX_OVERRUN
            }

        assertTrue(UnitCapabilities.hasPhasedMovement(reconWithBit), "key off: the class decides")
        assertFalse(UnitCapabilities.hasPhasedMovement(infantryWithBit))
        assertTrue(UnitCapabilities.canOverrun(tankWithBit))

        ruleset(RuleKey.EQUIPMENT_TOGGLES to 1)
        assertFalse(UnitCapabilities.hasPhasedMovement(reconWithBit), "key on: the record decides")
        assertTrue(UnitCapabilities.hasPhasedMovement(infantryWithBit))
        assertFalse(UnitCapabilities.canOverrun(tankWithBit))
    }
}
