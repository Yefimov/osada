package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.ATTR_MASK_AIR_SUPPORT
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.landInHangar
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Airbase unit (`rules/Airbase`, owner's decisions 2026-10-07): it services aircraft beside it
 * (OG's `Air support`) and inside its hangar (refuel, rearm and repair every round, unasked), and
 * repair costs half either way.
 *
 * Prices are compared with the same aircraft's undiscounted price, so the tests pin the discount
 * and not the efile's replacement pricing underneath it.
 */
class AirbaseTest : OgRulesTestHarness() {
    /** Merged record ids on [Airbase]'s list: one with a hangar, one without (pzliga `Airfield 2`). */
    private val airbaseEqid = 465
    private val openAirbaseEqid = 466
    private val planeEqid = 996
    private val carrierEqid = 995

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(airbaseEqid, airbase(airbaseEqid, hangar = 4))
        Equipment.putEquipment(openAirbaseEqid, airbase(openAirbaseEqid, hangar = 0))
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                eqid = planeEqid
                name = "Bf 109"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 10
                ammo = 6
                fuel = 8
                cost = 20
            },
        )
        Equipment.putEquipment(
            carrierEqid,
            EquipmentData().apply {
                eqid = carrierEqid
                name = "Fleet Carrier"
                uclass = UnitClass.CARRIER.value
                movmethod = MovMethod.NAVAL.value
                hangarCap = 4
            },
        )
    }

    private fun airbase(
        id: Int,
        hangar: Int,
    ) = EquipmentData().apply {
        eqid = id
        name = "Airbase"
        uclass = UnitClass.FORTIFICATION.value
        target = UnitType.HARD.value
        attr = ATTR_MASK_AIR_SUPPORT
        hangarCap = hangar
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun liveWorld(prestige: Int = 0): GameMap {
        val map = world(prestige)
        GameHolder.instance = holderFor(map)
        return map
    }

    private fun damagedPlane(
        map: GameMap,
        row: Int,
        col: Int,
    ): GameUnit = place(map, planeEqid, row, col, side = 0).apply { strength = 5 }

    private fun fullPrice(map: GameMap): Int = CostCalculator.reinforceCostPerStrength(damagedPlane(map, 6, 6), false)

    @Test
    fun anAircraftBesideAnAirbaseIsServicedAndRepairsAtHalfPrice() {
        val map = liveWorld()
        place(map, airbaseEqid, 2, 2, side = 0)
        val plane = damagedPlane(map, 2, 3)
        val full = fullPrice(map)

        assertTrue(MovementRules.hasAirfield(map, plane), "no landing needed")
        assertEquals((full + 1) / 2, CostCalculator.reinforceCostPerStrength(plane, false))
    }

    @Test
    fun anEnemyOrDistantAirbaseGivesNothing() {
        val map = liveWorld()
        place(map, airbaseEqid, 2, 2, side = 1)
        place(map, airbaseEqid, 0, 7, side = 0)
        val plane = damagedPlane(map, 2, 3)

        assertEquals(fullPrice(map), CostCalculator.reinforceCostPerStrength(plane, false))
    }

    @Test
    fun anAirbaseWithNoHangarStillServicesTheRingAroundIt() {
        val map = liveWorld()
        place(map, openAirbaseEqid, 2, 2, side = 0)
        val plane = damagedPlane(map, 2, 3)

        assertTrue(MovementRules.hasAirfield(map, plane))
    }

    @Test
    fun insideTheHangarRepairCostsHalf() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 2, 2, side = 0)
        val plane = damagedPlane(map, 2, 3)
        val full = fullPrice(map)
        assertTrue(map.landInHangar(plane, airbase))

        assertTrue(full > 1)
        assertEquals((full + 1) / 2, CostCalculator.reinforceCostPerStrength(plane, false))
        assertEquals(
            ((GreenReplacements.costPerStrength(damagedPlane(map, 6, 5)) + 1) / 2).coerceAtLeast(1),
            GreenReplacements.costPerStrength(plane),
            "green replacements too",
        )
    }

    @Test
    fun aircraftInsideAreRepairedEveryRoundAtHalfPrice() {
        val map = liveWorld(prestige = 1000)
        val airbase = place(map, airbaseEqid, 2, 2, side = 0)
        val plane = damagedPlane(map, 2, 3)
        val half = (fullPrice(map) + 1) / 2
        map.landInHangar(plane, airbase)

        CarrierHangars.endRoundForContained(map, airbase, 0)

        assertEquals(10, plane.strength)
        assertEquals(1000 - 5 * half, friendly.prestige)
        assertFalse(plane.hasMoved, "and ready to act next turn")
    }

    @Test
    fun aCarrierRepairsAtTheOrdinaryPrice() {
        val map = liveWorld()
        val carrier = place(map, carrierEqid, 2, 2, side = 0)
        val plane = damagedPlane(map, 2, 3)
        val full = fullPrice(map)
        map.landInHangar(plane, carrier)

        assertEquals(full, CostCalculator.reinforceCostPerStrength(plane, false))
    }

    @Test
    fun aDestroyedAirbaseGivesNoDiscount() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 2, 2, side = 0)
        val plane = damagedPlane(map, 2, 3)
        val full = fullPrice(map)
        map.landInHangar(plane, airbase)
        airbase.destroyed = true

        assertEquals(full, CostCalculator.reinforceCostPerStrength(plane, false))
    }

    @Test
    fun theUnitAnEfileCallsAirfieldIsShownAsAnAirbase() {
        val renamed = EquipmentData().apply { name = "Airfield 2" }
        val untouched = EquipmentData().apply { name = "Airport" }

        Airbase.applyDisplayName(466, renamed)
        Airbase.applyDisplayName(471, untouched)

        assertEquals("Airbase 2", renamed.name)
        assertEquals("Airport", untouched.name)
        assertTrue(Airbase.isAirbase(EquipmentData().apply { eqid = 471 }))
    }
}
