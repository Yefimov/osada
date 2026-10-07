package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.collectPersistentCampaignUnits
import org.osada.model.landInHangar
import org.osada.model.launchFromHangar
import org.osada.model.restoreCoreUnitList
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Carriers and airbases hold aircraft (owner's decision 2026-10-07): the hangar works in every efile,
 * not only the four that set `ground_carrier`, and it is on by default.
 */
class HangarTest : OgRulesTestHarness() {
    /** A merged record id on [Airbase]'s list. */
    private val airbaseEqid = 465
    private val planeEqid = 997
    private val bunkerEqid = 998

    @BeforeTest
    fun setup() {
        installTestWorld()
        // No `ground_carrier`: most efiles do not set it, and aircraft must not depend on it.
        EfileConfig.setForTest(emptyMap())
        Equipment.putEquipment(
            airbaseEqid,
            EquipmentData().apply {
                eqid = airbaseEqid
                name = "Airbase"
                uclass = UnitClass.FORTIFICATION.value
                target = UnitType.HARD.value
                hangarCap = 2
            },
        )
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                eqid = planeEqid
                name = "Fighter"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
                ammo = 6
                fuel = 40
            },
        )
        Equipment.putEquipment(
            bunkerEqid,
            EquipmentData().apply {
                eqid = bunkerEqid
                name = "Bunker"
                uclass = UnitClass.FORTIFICATION.value
                hangarCap = 3
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun liveWorld(): GameMap {
        val map = world()
        GameHolder.instance = holderFor(map)
        return map
    }

    @Test
    fun aircraftLandInAnAirbaseWithoutAnyEfileKey() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)
        val plane = place(map, planeEqid, 3, 4, 0)

        assertTrue(CarrierHangars.active(), "on by default")
        assertEquals(airbase, HangarStowage.landingContainer(map, plane))
        assertTrue(map.landInHangar(plane, airbase))
        assertTrue(plane !in map.units)
        assertEquals(airbase, plane.containedIn)
    }

    @Test
    fun aBunkerIsNotAnAirfield() {
        val map = liveWorld()
        place(map, bunkerEqid, 3, 3, 0)
        val plane = place(map, planeEqid, 3, 4, 0)

        assertTrue(HangarStowage.containersNear(map, plane).isEmpty())
        assertFalse(UnitActionAvailability.forAction(UnitActionId.LAND_IN_HANGAR, context(map, plane)).applicable)
    }

    /** Owner's ruling 2026-10-07: the hangar chips are for aircraft, even where an efile lets land
     *  formations into containers. */
    @Test
    fun landFormationsGetNoHangarChips() {
        EfileConfig.setForTest(mapOf("ground_carrier" to 1))
        val map = liveWorld()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        place(map, airbaseEqid, 2, 3, 0)
        val infantry = place(map, infantryEqid, 3, 4, 0)

        assertFalse(UnitActionAvailability.forAction(UnitActionId.LAND_IN_HANGAR, context(map, infantry)).applicable)
        assertFalse(UnitActionAvailability.forAction(UnitActionId.HANGAR, context(map, bunker)).applicable)
    }

    /** A land formation takes cover in a friendly fortification where the efile admits land units,
     *  and comes out onto a free hex beside it -- never onto the fortification's own hex. */
    @Test
    fun infantryTakesCoverInABunkerAndComesOutBesideIt() {
        EfileConfig.setForTest(mapOf("ground_carrier" to 1))
        val map = liveWorld()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val infantry = place(map, infantryEqid, 3, 4, 0)

        val chip = UnitActionAvailability.forAction(UnitActionId.ENTER_COVER, context(map, infantry))
        assertTrue(chip.enabled)
        assertTrue(UnitActionAvailability.forAction(UnitActionId.COVER, context(map, bunker)).applicable)
        assertEquals(bunker, HangarStowage.coverContainer(map, infantry))
        assertTrue(map.landInHangar(infantry, bunker))

        CarrierHangars.endRoundForContained(map, bunker, 0)
        map.turn += 1
        val cells = CarrierHangars.launchCells(map, infantry, bunker)
        assertTrue(cells.isNotEmpty())
        assertTrue(bunker.getHex() !in cells, "a hex holds one ground formation, and the bunker is it")
    }

    /** `ground_carrier` 2 (AG) lets a garrison support from inside but not go in during play. */
    @Test
    fun noCoverWhereTheEfileAdmitsNoLandUnits() {
        EfileConfig.setForTest(mapOf("ground_carrier" to 2))
        val map = liveWorld()
        place(map, bunkerEqid, 3, 3, 0)
        val infantry = place(map, infantryEqid, 3, 4, 0)

        assertFalse(UnitActionAvailability.forAction(UnitActionId.ENTER_COVER, context(map, infantry)).applicable)
    }

    @Test
    fun aFullHangarKeepsTheChipAndSaysWhy() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)
        map.landInHangar(place(map, planeEqid, 2, 3, 0), airbase)
        map.landInHangar(place(map, planeEqid, 4, 3, 0), airbase)
        val third = place(map, planeEqid, 3, 4, 0)

        val chip = UnitActionAvailability.forAction(UnitActionId.LAND_IN_HANGAR, context(map, third))
        assertTrue(chip.applicable)
        assertFalse(chip.enabled)
        assertTrue(chip.reasons.any { it.reason == ActionBlockReason.HANGAR_FULL })
    }

    @Test
    fun theRuleSwitchedOffShowsNoHangar() {
        ruleset(RuleKey.CARRIER_HANGARS to 0)
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)

        assertFalse(UnitActionAvailability.forAction(UnitActionId.HANGAR, context(map, airbase)).applicable)
    }

    @Test
    fun aLandedAircraftTakesOffNextTurnKeepingItsIdAndFlies() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)
        val plane = place(map, planeEqid, 3, 4, 0)
        val id = plane.id
        map.landInHangar(plane, airbase)

        assertFalse(CarrierHangars.canLaunch(map, plane, airbase), "not on the turn it landed")
        CarrierHangars.endRoundForContained(map, airbase, 0)
        map.turn += 1
        val home = assertNotNull(airbase.getHex())
        assertTrue(home in CarrierHangars.launchCells(map, plane, airbase))
        assertTrue(map.launchFromHangar(airbase, plane, home))
        assertEquals(id, plane.id, "the id it went in with")
        assertEquals(plane, map.currentUnit, "selected, so the next click is where it flies")
        assertTrue(map.currentMoveRange.isNotEmpty())
    }

    @Test
    fun aircraftInsideAreRefuelledAndRearmedEachRound() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)
        val plane =
            place(map, planeEqid, 3, 4, 0).apply {
                ammo = 1
                fuel = 3
            }
        map.landInHangar(plane, airbase)

        CarrierHangars.endRoundForContained(map, airbase, 0)

        assertEquals(6, plane.ammo)
        assertEquals(40, plane.fuel)
        assertFalse(plane.hasMoved, "and ready to act")
    }

    @Test
    fun aReserveAircraftIsStowedAtDeploymentAndMayTakeOffOnTheFirstTurn() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)
        val reserve =
            org.osada.model
                .GameUnit(planeEqid)
                .apply { owner = friendly.id }

        assertTrue(HangarStowage.stowAtDeployment(map, reserve, airbase))
        assertTrue(reserve.isDeployed)
        assertTrue(CarrierHangars.canLaunch(map, reserve, airbase), "it never landed")
        assertTrue(HangarStowage.returnToReserve(reserve))
        assertFalse(reserve.isDeployed)
        assertTrue(airbase.hangar.isEmpty())
    }

    @Test
    fun aPassengerCarriesForwardAsItselfAndTheContainerArrivesEmpty() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0).apply { isCore = true }
        val plane = place(map, planeEqid, 3, 4, 0).apply { isCore = true }
        map.landInHangar(plane, airbase)

        map.collectPersistentCampaignUnits(friendly)

        assertTrue(plane in friendly.getCoreUnitList())
        assertNull(plane.containedIn)
        assertTrue(airbase.hangar.isEmpty())
    }

    @Test
    fun aRestoredHangarKeepsItsPassengerOnTheRoster() {
        val map = liveWorld()
        val airbase = place(map, airbaseEqid, 3, 3, 0)
        val plane =
            place(map, planeEqid, 3, 4, 0).apply {
                isCore = true
                isDeployed = true
            }
        map.landInHangar(plane, airbase)

        map.restoreCoreUnitList(friendly, emptyList())

        assertTrue(plane in friendly.getCoreUnitList())
    }

    private fun context(
        map: GameMap,
        unit: org.osada.model.GameUnit,
    ) = UnitActionContext(map, unit, friendly)
}
