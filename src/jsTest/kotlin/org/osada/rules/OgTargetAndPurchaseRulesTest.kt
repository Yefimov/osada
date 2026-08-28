package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.buyUnit
import org.osada.model.canBeAwardedAsPrototype
import org.osada.model.canInitiateAttackOnUnitType
import org.osada.model.isAiPurchasable
import org.osada.model.isPurchasable
import org.osada.scenario.getPrototypeUnitsAvailable
import kotlin.js.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four `attr` bits wired on 2026-08-27 (`docs/og-fidelity-plan.md` §U): the fourth target-type
 * prohibition, `CAN Air Atk`'s missing movement condition, and the three purchase/authoring bits.
 *
 * Every test here is written to FAIL against the previous behaviour. Three of the four bits had a
 * decoded value and no reader at all; the fourth had a reader that applied half of OG's sentence.
 * The negative cases matter as much as the positive ones — §M.1 listed these as *narrower than OG*,
 * and the way to get them wrong in the other direction is to apply a condition OG scopes to one
 * action to all three of the actions its own audit table separates.
 */
class OgTargetAndPurchaseRulesTest : OgRulesTestHarness() {
    /** `Can't Naval Atk` (bit 18), `CAN Air Atk` (15), `Can't Buy` (7), `No AI buy` (8),
     *  `No Prototype` (17). */
    private val attrCantNavalAtk = 262144
    private val attrCanAirAtk = 32768
    private val attrCantBuy = 128
    private val attrNoAiBuy = 256
    private val attrNoPrototype = 131072

    private val coastalGunEqid = 960
    private val blindCoastalGunEqid = 961
    private val destroyerEqid = 962
    private val flakTruckEqid = 963
    private val fighterEqid = 964
    private val bomberTargetEqid = 965
    private val unbuyableEqid = 966
    private val humanOnlyEqid = 967
    private val neverPrototypeEqid = 968
    private val ordinaryPrototypeEqid = 969

    @BeforeTest
    fun setup() {
        installTestWorld()
        registerNavalCast()
        registerAirCast()
        registerPurchaseCast()
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    /** Two identical shore batteries and one ship to shoot at: the only difference between the
     *  batteries is bit 18. */
    private fun registerNavalCast() {
        listOf(coastalGunEqid to 0, blindCoastalGunEqid to attrCantNavalAtk).forEach { (eqid, bits) ->
            Equipment.putEquipment(
                eqid,
                EquipmentData().apply {
                    name = "Coastal Battery"
                    uclass = UnitClass.ARTILLERY.value
                    target = UnitType.HARD.value
                    movmethod = MovMethod.TOWED.value
                    movpoints = 2
                    gunrange = 3
                    ammo = 8
                    navalatk = 11
                    hardatk = 6
                    softatk = 6
                    attr = bits
                },
            )
        }
        Equipment.putEquipment(
            destroyerEqid,
            EquipmentData().apply {
                name = "Destroyer"
                uclass = UnitClass.DESTROYER.value
                target = UnitType.SEA.value
                movmethod = MovMethod.NAVAL.value
                movpoints = 8
                ammo = 8
                navalatk = 9
                grounddef = 6
            },
        )
    }

    /** A flak truck that engages aircraft ONLY because it carries `CAN Air Atk`, a Fighter that
     *  engages them because of what it is, and a plane for both to shoot at. */
    private fun registerAirCast() {
        Equipment.putEquipment(
            flakTruckEqid,
            EquipmentData().apply {
                name = "Flak Truck"
                uclass = UnitClass.GROUND_TRANSPORT.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.WHEELED.value
                movpoints = 6
                ammo = 8
                airatk = 7
                softatk = 4
                grounddef = 3
                attr = attrCanAirAtk
            },
        )
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Fighter"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 10
                ammo = 6
                airatk = 12
                airdef = 10
            },
        )
        Equipment.putEquipment(
            bomberTargetEqid,
            EquipmentData().apply {
                name = "Level Bomber"
                uclass = UnitClass.LEVEL_BOMBER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 10
                ammo = 6
                softatk = 10
                airdef = 6
            },
        )
    }

    private fun registerPurchaseCast() {
        Equipment.putEquipment(unbuyableEqid, purchaseRecord("Author Bunker", attrCantBuy))
        Equipment.putEquipment(humanOnlyEqid, purchaseRecord("Experimental Tank", attrNoAiBuy))
        Equipment.putEquipment(neverPrototypeEqid, purchaseRecord("Sealed Design", attrNoPrototype, year = 1943))
        Equipment.putEquipment(ordinaryPrototypeEqid, purchaseRecord("Follow-On Tank", bits = 0, year = 1943))
    }

    private fun purchaseRecord(
        label: String,
        bits: Int,
        year: Int = 1941,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.TANK.value
        target = UnitType.HARD.value
        movmethod = MovMethod.TRACKED.value
        movpoints = 5
        ammo = 6
        hardatk = 8
        softatk = 8
        grounddef = 7
        country = 20
        attr = bits
        // Above PROTOTYPE_MIN_COST once CURRENCY_MULTIPLIER is applied, so the prototype pool
        // rejects these records for their `attr` rather than for being cheap.
        cost = 30
        yearavailable = year
        yearexpired = year + 5
    }

    // ---- Can't Naval Atk --------------------------------------------------------------------

    @Test
    fun aBatteryWithoutTheBitStillShootsAtShips() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, coastalGunEqid, 2, 2, side = 0)
        val ship = place(map, destroyerEqid, 2, 4, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(gun, ship))
    }

    @Test
    fun cantNavalAtkForbidsTheShotTheSameRecordCouldOtherwiseTake() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, blindCoastalGunEqid, 2, 2, side = 0)
        val ship = place(map, destroyerEqid, 2, 4, side = 1)

        assertFalse(AttackEligibility.canInitiateAttack(gun, ship))
        assertFalse(Equipment.canInitiateAttackOnUnitType(blindCoastalGunEqid, destroyerEqid))
    }

    @Test
    fun cantNavalAtkLeavesTheOtherThreeTargetTypesAlone() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val gun = place(map, blindCoastalGunEqid, 2, 2, side = 0)
        val infantry = place(map, infantryEqid, 2, 3, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(gun, infantry), "only the SEA row is refused")
    }

    // ---- CAN Air Atk, and the half of its sentence OSADA never read -------------------------

    @Test
    fun aFlakTruckThatHasNotMovedMayEngageAircraft() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val truck = place(map, flakTruckEqid, 2, 2, side = 0)
        val bomber = place(map, bomberTargetEqid, 2, 3, side = 1)

        assertFalse(AttackEligibility.blockedByMovedAirGrant(truck, bomber))
        assertTrue(AttackEligibility.canInitiateAttack(truck, bomber))
    }

    @Test
    fun theSameTruckMayNotEngageThemAfterMoving() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val truck = place(map, flakTruckEqid, 2, 2, side = 0)
        val bomber = place(map, bomberTargetEqid, 2, 3, side = 1)
        truck.hasMoved = true

        assertTrue(AttackEligibility.blockedByMovedAirGrant(truck, bomber))
        assertFalse(AttackEligibility.canInitiateAttack(truck, bomber))
    }

    @Test
    fun spendingOneMovementPointIsEnoughToLoseTheGrant() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val truck = place(map, flakTruckEqid, 2, 2, side = 0)
        val bomber = place(map, bomberTargetEqid, 2, 3, side = 1)
        truck.moveLeft = truck.unitData(useReal = true).movpoints - 1

        assertTrue(AttackEligibility.blockedByMovedAirGrant(truck, bomber), "one step unlimbers it")
    }

    @Test
    fun theConditionBelongsToTheGrantRatherThanToTheTarget() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val fighter = place(map, fighterEqid, 2, 2, side = 0)
        val bomber = place(map, bomberTargetEqid, 2, 3, side = 1)
        fighter.hasMoved = true

        assertFalse(
            AttackEligibility.blockedByMovedAirGrant(fighter, bomber),
            "a Fighter engages aircraft because of its class, so the ability's condition is not its",
        )
        assertTrue(AttackEligibility.canInitiateAttack(fighter, bomber))
    }

    @Test
    fun aReactionIsNotAnActiveAttackAndKeepsTheGrant() {
        val map = world()
        GameHolder.instance = holderFor(map)
        val truck = place(map, flakTruckEqid, 2, 2, side = 0)
        val bomber = place(map, bomberTargetEqid, 2, 3, side = 1)
        truck.hasMoved = true

        assertTrue(
            AttackEligibility.canInitiateAttack(truck, bomber, asActiveAttack = false),
            "OG scopes the condition to the active attack: defensive AD and interception keep it",
        )
    }

    // ---- Can't Buy / No AI buy / No Prototype -----------------------------------------------

    @Test
    fun cantBuyRefusesThePurchaseAndSpendsNothing() {
        world(prestige = 1000)
        val before = friendly.prestige

        assertFalse(Equipment.isPurchasable(unbuyableEqid))
        assertFalse(friendly.buyUnit(unbuyableEqid, -1))
        assertEquals(before, friendly.prestige, "a refused purchase must not cost prestige")
        assertEquals(0, friendly.getCoreUnitList().size)
    }

    @Test
    fun anOrdinaryRecordIsStillBought() {
        world(prestige = 1000)

        assertTrue(friendly.buyUnit(humanOnlyEqid, -1))
        assertEquals(1, friendly.getCoreUnitList().size)
    }

    @Test
    fun noAiBuyIsTheAiRuleAloneAndNotThePlayers() {
        assertTrue(Equipment.isPurchasable(humanOnlyEqid), "the player may buy it")
        assertFalse(Equipment.isAiPurchasable(humanOnlyEqid), "the AI may not")
        assertTrue(Equipment.isAiPurchasable(unbuyableEqid), "bit 8 is a different bit from bit 7")
        assertFalse(Equipment.isPurchasable(unbuyableEqid))
    }

    @Test
    fun noPrototypeIsExcludedFromTheBrilliantVictoryDraw() {
        val map = world()
        val holder = holderFor(map)
        holder.scenario?.date = Date(1942, 5, 1)
        GameHolder.instance = holder

        val pool = holder.scenario!!.getPrototypeUnitsAvailable(20)

        assertTrue(ordinaryPrototypeEqid in pool, "next year's tank is an eligible award")
        assertFalse(neverPrototypeEqid in pool, "the author excluded this one from the draw")
        assertFalse(Equipment.canBeAwardedAsPrototype(neverPrototypeEqid))
        assertTrue(Equipment.canBeAwardedAsPrototype(ordinaryPrototypeEqid))
    }
}
