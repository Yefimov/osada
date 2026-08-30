package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.RAIL_UNKNOWN
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four mechanics `docs/og-fidelity-plan.md` §Y.3 listed as unbuilt, and the transport-weight
 * question §Y.5 carried — all closed 2026-08-30.
 *
 * None of them was ever blocked on evidence: every key is documented in `OPENTXT_SAMPLE/equip.cfg`
 * or `EFILE_NOKORP/equip.cfg`, and the register said so. What they were blocked on was effort.
 */
class LastFourMechanicsTest : OgRulesTestHarness() {
    private val carrierEqid = 990
    private val planeEqid = 991
    private val subEqid = 992

    /** A record with a real COST — the harness's own units are free, and a free formation makes
     *  "greens are cheaper" untestable: `costPerStrength` floors at 1 so it never gives strength
     *  away, which correctly makes 25% of nothing MORE than nothing. */
    private val pricedEqid = 993

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            carrierEqid,
            EquipmentData().apply {
                name = "Fleet Carrier"
                uclass = UnitClass.CARRIER.value
                movmethod = MovMethod.NAVAL.value
                hangarCap = 2
            },
        )
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                name = "Carrier Fighter"
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
            },
        )
        Equipment.putEquipment(
            pricedEqid,
            EquipmentData().apply {
                name = "Rifle Regiment"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                cost = 40
            },
        )
        Equipment.putEquipment(
            subEqid,
            EquipmentData().apply {
                name = "Submarine"
                uclass = UnitClass.SUBMARINE.value
                movmethod = MovMethod.NAVAL.value
                groundweight = 0
            },
        )
    }

    @AfterTest
    fun teardown() {
        efileKeys.clear()
        clearTestWorld()
    }

    /** `EfileConfig.setForTest` REPLACES the whole key map, so a second call would silently undo
     *  the first. These tests set several keys per case, so they accumulate here instead. */
    private val efileKeys = mutableMapOf<String, Int>()

    private fun efile(
        key: String,
        value: Int,
    ) {
        efileKeys[key] = value
        EfileConfig.setForTest(efileKeys.toMap())
    }

    // ---- Green replacements -----------------------------------------------------------------

    /** *"green_cost — cost percent ... default 0 means 25%"*, and the key gates the whole thing. */
    @Test
    fun greenReplacementsCostAFractionAndOnlyExistWhenBothGatesAgree() {
        val map = world(prestige = 10_000)
        val unit = place(map, pricedEqid, 2, 2, 0).apply { strength = 5 }

        ruleset(RuleKey.GREEN_REPLACEMENTS to 0)
        efile("green", 1)
        assertFalse(GreenReplacements.enabled(), "the player's key is off")

        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 0)
        assertFalse(GreenReplacements.enabled(), "the efile does not define them")

        efile("green", 1)
        assertTrue(GreenReplacements.enabled())
        assertEquals(25, GreenReplacements.costPercent(), "0 means OG's own 25%")
        val full = CostCalculator.reinforceCostPerStrength(unit, false)
        assertTrue(
            GreenReplacements.costPerStrength(unit) < full,
            "the whole point is that greens are cheaper than trained replacements",
        )
    }

    /** *"green_exp — ... Set 100 to avoid unit losing experience because green replacements"*. */
    @Test
    fun greenExperienceRunsFromTotalDilutionToNone() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 1)
        val map = world(prestige = 10_000)
        val unit =
            place(map, infantryEqid, 2, 2, 0).apply {
                strength = 5
                experience = 400
            }

        efile("green_exp", 0)
        assertEquals(200, GreenReplacements.experienceAfter(unit, 5), "raw intake halves a half-strength unit")

        efile("green_exp", 100)
        assertEquals(400, GreenReplacements.experienceAfter(unit, 5), "100 means nothing is lost")
    }

    /** *"remove_leader — If Leaders must be removed if unit loses all bars"*. */
    @Test
    fun theCommanderLeavesWhenGreensCostTheFormationItsLastBar() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 1)
        efile("green_exp", 0)
        efile("remove_leader", 1)
        val map = world(prestige = 10_000)
        val unit =
            place(map, infantryEqid, 2, 2, 0).apply {
                strength = 1
                experience = 100
                leader = 4
            }

        assertTrue(GreenReplacements.apply(unit, 9) > 0)
        assertEquals(0, UnitExperience.bars(unit), "the intake diluted the last bar away")
        assertEquals(-1, unit.leader, "so the commander goes")
    }

    /** With `remove_leader` absent the commander stays, however diluted the formation is. */
    @Test
    fun theCommanderStaysWhenTheEfileDoesNotAskForRemoval() {
        ruleset(RuleKey.GREEN_REPLACEMENTS to 1)
        efile("green", 1)
        efile("green_exp", 0)
        val map = world(prestige = 10_000)
        val unit =
            place(map, infantryEqid, 2, 2, 0).apply {
                strength = 1
                experience = 100
                leader = 4
            }
        GreenReplacements.apply(unit, 9)
        assertEquals(4, unit.leader)
    }

    // ---- Carrier hangars --------------------------------------------------------------------

    /** Containment: the aircraft leaves the map entirely, and comes back where the ship is. */
    @Test
    fun anAircraftInAHangarIsOffTheMapAndComesBackNextTurn() {
        ruleset(RuleKey.CARRIER_HANGARS to 1)
        efile("ground_carrier", 1)
        val map = world()
        val carrier = place(map, carrierEqid, 3, 3, 0)
        val plane = place(map, planeEqid, 4, 4, 0)

        assertTrue(CarrierHangars.board(map, plane, carrier))
        assertEquals(1, carrier.hangar.size)
        assertTrue(plane !in map.units, "a contained aircraft is not on the map")
        assertFalse(
            CarrierHangars.canLaunch(map, plane, carrier),
            "and cannot take off again on the turn it landed",
        )

        map.turn += 1
        assertTrue(CarrierHangars.launch(map, plane, carrier))
        assertEquals(0, carrier.hangar.size)
        assertTrue(plane in map.units)
    }

    /** *"any value no zero allow to enter"*, and capacity comes from the ship's own record. */
    @Test
    fun aHangarHoldsExactlyWhatItsRecordAllows() {
        ruleset(RuleKey.CARRIER_HANGARS to 1)
        efile("ground_carrier", 1)
        val map = world()
        val carrier = place(map, carrierEqid, 3, 3, 0)
        assertEquals(2, CarrierHangars.capacity(carrier))
        assertTrue(CarrierHangars.board(map, place(map, planeEqid, 4, 4, 0), carrier))
        assertTrue(CarrierHangars.board(map, place(map, planeEqid, 4, 5, 0), carrier))
        assertFalse(CarrierHangars.hasRoom(carrier), "two is the ship's whole hangar")
        assertFalse(CarrierHangars.board(map, place(map, planeEqid, 4, 6, 0), carrier))
    }

    /** *"4 disables being launched (taking off) units"*. */
    @Test
    fun bitFourKeepsTheAircraftAboardForGood() {
        ruleset(RuleKey.CARRIER_HANGARS to 1)
        efile("ground_carrier", 1 or 4)
        val map = world()
        val carrier = place(map, carrierEqid, 3, 3, 0)
        val plane = place(map, planeEqid, 4, 4, 0)
        assertTrue(CarrierHangars.board(map, plane, carrier))
        map.turn += 1
        assertFalse(CarrierHangars.canLaunch(map, plane, carrier))
    }

    /** An aircraft cannot outlive the ship it was contained in. */
    @Test
    fun theHangarGoesDownWithItsShip() {
        ruleset(RuleKey.CARRIER_HANGARS to 1)
        efile("ground_carrier", 1)
        val map = world()
        val carrier = place(map, carrierEqid, 3, 3, 0)
        val plane = place(map, planeEqid, 4, 4, 0)
        CarrierHangars.board(map, plane, carrier)
        CarrierHangars.sinkWith(carrier)
        assertTrue(plane.destroyed)
        assertTrue(carrier.hangar.isEmpty())
    }

    /** Nothing happens at all without both gates — the default game is untouched. */
    @Test
    fun hangarsDoNothingWithoutBothGates() {
        ruleset(RuleKey.CARRIER_HANGARS to 0)
        efile("ground_carrier", 1)
        val map = world()
        val carrier = place(map, carrierEqid, 3, 3, 0)
        assertEquals(0, CarrierHangars.capacity(carrier))
        assertFalse(CarrierHangars.board(map, place(map, planeEqid, 4, 4, 0), carrier))
    }

    // ---- rem_leader and sub_buytra ------------------------------------------------------------

    /** OG's `rem_leader`, sourced from the 2024 changelog's *"removal via Ctrl+X"*. */
    @Test
    fun aCommanderCanBeDismissedOnlyWhenTheEfileAllowsIt() {
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0).apply { leader = 2 }
        efile("rem_leader", 0)
        assertFalse(LeaderDismissal.canDismiss(unit))
        assertFalse(LeaderDismissal.dismiss(unit))
        assertEquals(2, unit.leader)

        efile("rem_leader", 1)
        assertTrue(LeaderDismissal.dismiss(unit))
        assertEquals(-1, unit.leader)
        assertFalse(LeaderDismissal.dismiss(unit), "and there is nobody left to dismiss")
    }

    /** *"sub_buytra — Set to 1, to allow subs to buy transport"*. 708 of 710 subs have no weight. */
    @Test
    fun submarinesAreOfferedTransportOnlyWhenTheEfileSaysSo() {
        efile("sub_buytra", 0)
        assertFalse(UnitPredicates.isTransportable(subEqid))
        efile("sub_buytra", 1)
        assertTrue(UnitPredicates.isTransportable(subEqid))
        assertFalse(
            UnitPredicates.isTransportable(infantryEqid),
            "and the key changes nothing for anybody else",
        )
    }

    // ---- Transport weights and the recovered permissions --------------------------------------

    /** Both sides read the SAME per-type field, and a zero on either means "unrestricted". */
    @Test
    fun transportWeightsGateOnTheSharedPerTypeField() {
        val cargo = EquipmentData().apply { navalWeight = 0x01 }
        val fits = EquipmentData().apply { navalWeight = 0x03 }
        val doesNot = EquipmentData().apply { navalWeight = 0x02 }
        val unrestricted = EquipmentData().apply { navalWeight = 0 }
        val naval = UnitClass.NAVAL_TRANSPORT.value

        assertTrue(TransportWeights.compatible(cargo, fits, naval))
        assertFalse(TransportWeights.compatible(cargo, doesNot, naval))
        assertTrue(TransportWeights.compatible(cargo, unrestricted, naval), "0 carries anything")
        assertTrue(
            TransportWeights.compatible(EquipmentData().apply { navalWeight = RAIL_UNKNOWN }, doesNot, naval),
            "and a record OG never spoke about rides anything",
        )
    }
}
