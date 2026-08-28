package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.attackUnit
import org.osada.model.getUnits
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three independent equipment abilities wired on 2026-08-27 (`docs/og-fidelity-plan.md` §U):
 * `Cannot use dirt airfields`, `Rocket bomber` and `SingleFireSup.`
 *
 * Each is built as the narrowest reading of OG's own sentence, so the tests that matter most are
 * the ones asserting what each ability does NOT do — a rocket bomber still blocked by a unit in the
 * way, a jet still refuelling on a map's own airfield, an ordinary battery still supporting twice.
 */
class IndependentAbilitiesTest : OgRulesTestHarness() {
    /** `No Dirt Airfields` (attr2 bit 2), `Rocket Bomber` (attr2 bit 3), `SingleFireSup.`
     *  (attrEx bit 15). */
    private val attr2NoDirt = 4
    private val attr2RocketBomber = 8
    private val attrExSingleFireSup = 32768

    private val jetEqid = 990
    private val propPlaneEqid = 991
    private val rocketPlaneEqid = 992
    private val plainAttackerEqid = 993
    private val oneShotBatteryEqid = 994
    private val ordinaryBatteryEqid = 995

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(jetEqid, plane("Me 262", attr2NoDirt))
        Equipment.putEquipment(propPlaneEqid, plane("Bf 109", bits = 0))
        Equipment.putEquipment(rocketPlaneEqid, groundAttacker("Il-2m3", attr2RocketBomber))
        Equipment.putEquipment(plainAttackerEqid, groundAttacker("Assault Gun", bits = 0))
        Equipment.putEquipment(oneShotBatteryEqid, battery("Rocket Battery", attrExSingleFireSup))
        Equipment.putEquipment(ordinaryBatteryEqid, battery("Field Battery", bits = 0))
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun plane(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.FIGHTER.value
        target = UnitType.AIR.value
        movmethod = MovMethod.AIR.value
        movpoints = 10
        ammo = 6
        fuel = 8
        airatk = 10
        airdef = 8
        attr2 = bits
    }

    /** A RANGED ground attacker, which is what §6.18's terrain check applies to. */
    private fun groundAttacker(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.TANK.value
        target = UnitType.HARD.value
        movmethod = MovMethod.TRACKED.value
        movpoints = 5
        gunrange = 4
        ammo = 8
        softatk = 10
        hardatk = 10
        grounddef = 7
        attr2 = bits
    }

    private fun battery(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.ARTILLERY.value
        target = UnitType.SOFT.value
        movmethod = MovMethod.TOWED.value
        movpoints = 2
        gunrange = 3
        ammo = 8
        softatk = 12
        hardatk = 8
        grounddef = 3
        attrEx = bits
    }

    // ---- Cannot use dirt airfields ------------------------------------------------------------

    private fun airfieldWorld(sapperBuilt: Boolean): GameMap {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world()
        map.map!![2][2].terrain = TerrainType.AIRFIELD.value
        map.map!![2][2].flag = friendly.country
        map.map!![2][2].sapperBuilt = sapperBuilt
        GameHolder.instance = holderFor(map)
        return map
    }

    @Test
    fun aJetRefuelsOnTheMapsOwnAirfield() {
        val map = airfieldWorld(sapperBuilt = false)
        val jet = place(map, jetEqid, 2, 2, side = 0)

        assertTrue(MovementRules.hasAirfield(map, jet))
    }

    @Test
    fun aJetWillNotRefuelOnAStripTheSappersBuilt() {
        val map = airfieldWorld(sapperBuilt = true)
        val jet = place(map, jetEqid, 2, 2, side = 0)

        assertFalse(MovementRules.hasAirfield(map, jet))
    }

    @Test
    fun anOrdinaryAircraftUsesTheSappersStripHappily() {
        val map = airfieldWorld(sapperBuilt = true)
        val prop = place(map, propPlaneEqid, 2, 2, side = 0)

        assertTrue(MovementRules.hasAirfield(map, prop))
    }

    @Test
    fun theAbilityIsInertWithoutTheRuleThatBuildsTheStrip() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 0)
        val map = world()
        map.map!![2][2].terrain = TerrainType.AIRFIELD.value
        map.map!![2][2].flag = friendly.country
        map.map!![2][2].sapperBuilt = true
        GameHolder.instance = holderFor(map)
        val jet = place(map, jetEqid, 2, 2, side = 0)

        assertTrue(
            MovementRules.hasAirfield(map, jet),
            "with engineering off nothing can have built this, so the flag must not be believed",
        )
    }

    @Test
    fun theSapperStripAlsoRefusesTheJetStandingBesideIt() {
        val map = airfieldWorld(sapperBuilt = true)
        val jet = place(map, jetEqid, 2, 3, side = 0)

        assertFalse(MovementRules.hasAirfield(map, jet), "the adjacency test reads the same rule")
    }

    // ---- Rocket bomber ------------------------------------------------------------------------

    private fun ridgeWorld(): GameMap {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val holder = holderFor(map)
        holder.scenario?.trueDirectLof = true
        GameHolder.instance = holder
        return map
    }

    @Test
    fun aRidgeCutsAnOrdinaryGunsFire() {
        val map = ridgeWorld()
        val gun = place(map, plainAttackerEqid, 2, 2, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)

        assertFalse(AttackEligibility.canInitiateAttack(gun, target))
    }

    @Test
    fun aRocketBomberFiresOverTheRidge() {
        val map = ridgeWorld()
        val rockets = place(map, rocketPlaneEqid, 2, 2, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(rockets, target))
    }

    @Test
    fun aUnitInTheWayStillBlocksARocketBomber() {
        val map = ridgeWorld()
        GameHolder.instance?.scenario?.unitsBlockLof = true
        val rockets = place(map, rocketPlaneEqid, 2, 2, side = 0)
        place(map, infantryEqid, 2, 3, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)

        assertFalse(
            AttackEligibility.canInitiateAttack(rockets, target),
            "the ability is about hex TYPES; §7.10 forbids reading it any wider",
        )
    }

    // ---- Single fire support ------------------------------------------------------------------

    private fun supportWorld(): GameMap {
        ruleset()
        val map = world()
        GameHolder.instance = holderFor(map)
        return map
    }

    @Test
    fun anOrdinaryBatterySupportsAgainEvenAfterItHasSupportedOnce() {
        val map = supportWorld()
        val battery = place(map, ordinaryBatteryEqid, 2, 2, side = 0)
        val defender = place(map, infantryEqid, 2, 3, side = 0)
        val attacker = place(map, plainAttackerEqid, 2, 4, side = 1)
        battery.hasSupportedThisTurn = true

        assertTrue(
            CombatResolver.getSupportFireUnits(map.getUnits().toList(), attacker, defender).contains(battery),
        )
    }

    @Test
    fun aSingleFireSupportBatteryIsSpentAfterOneAnswer() {
        val map = supportWorld()
        val battery = place(map, oneShotBatteryEqid, 2, 2, side = 0)
        val defender = place(map, infantryEqid, 2, 3, side = 0)
        val attacker = place(map, plainAttackerEqid, 2, 4, side = 1)

        assertTrue(
            CombatResolver.getSupportFireUnits(map.getUnits().toList(), attacker, defender).contains(battery),
            "its first answer is free",
        )
        battery.hasSupportedThisTurn = true
        assertFalse(
            CombatResolver.getSupportFireUnits(map.getUnits().toList(), attacker, defender).contains(battery),
            "and its second is not",
        )
    }

    @Test
    fun firingInSupportIsWhatSpendsIt() {
        val map = supportWorld()
        val battery = place(map, oneShotBatteryEqid, 2, 2, side = 0)
        val attacker = place(map, plainAttackerEqid, 2, 4, side = 1)

        map.attackUnit(battery, attacker, true)

        assertTrue(battery.hasSupportedThisTurn)
    }

    @Test
    fun anOrdinaryAttackDoesNotSpendTheSupportShot() {
        val map = supportWorld()
        val battery = place(map, oneShotBatteryEqid, 2, 2, side = 0)
        val enemy = place(map, infantryEqid, 2, 3, side = 1)

        map.attackUnit(battery, enemy, false)

        assertFalse(
            battery.hasSupportedThisTurn,
            "OG restricts the SUPPORT action, not the gun's own attack",
        )
    }
}
