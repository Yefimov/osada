package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.getUnits
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's `ground_carrier` bit 2 -- *"enables these options: Combat Support, AirDefense and
 * FireSupport"* -- the garrison that fights from inside its container.
 *
 * The bit is authored: `eqp-ag` sets `ground_carrier = 2`, `eqp-lxf` sets `11` (1 + 2 + 8) and
 * `eqp-son` sets `2`. `hangarCap` is on 13 unit classes, so the containers this covers are bunkers
 * and IFVs as much as ships.
 *
 * Two properties are pinned here and they pull in opposite directions, which is why they share a
 * file: a contained GROUND formation supports the battle from the container's hex, and a contained
 * AIRCRAFT never does. The second is the owner's 2026-08-31 ruling -- a plane that has not taken
 * off does not shoot, and this key grants no interception.
 */
class ContainerSupportFireTest : OgRulesTestHarness() {
    private val bunkerEqid = 961
    private val shipEqid = 962
    private val flakEqid = 963
    private val howitzerEqid = 964
    private val staffEqid = 965
    private val fighterEqid = 966
    private val bomberEqid = 967
    private val marinesEqid = 968

    @BeforeTest
    fun setup() {
        installTestWorld()
        putContainers()
        putGarrison()
        putAircraftAndInfantry()
    }

    private fun putContainers() {
        Equipment.putEquipment(
            bunkerEqid,
            EquipmentData().apply {
                name = "Casemate"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
                grounddef = 12
                hangarCap = 2
            },
        )
        Equipment.putEquipment(
            shipEqid,
            EquipmentData().apply {
                name = "Landing Ship"
                uclass = UnitClass.NAVAL_TRANSPORT.value
                movmethod = MovMethod.NAVAL.value
                target = UnitType.HARD.value
                grounddef = 6
                hangarCap = 2
            },
        )
    }

    private fun putGarrison() {
        Equipment.putEquipment(
            flakEqid,
            EquipmentData().apply {
                name = "8.8cm FlaK"
                // Air Defence, not Flak: `EquipmentCombatEligibility.isDedicatedAntiAir` excludes
                // the Flak class, whose real records carry OG's `CAN Air Atk` bit instead. Using a
                // dedicated class keeps this test about containment rather than about that grant.
                uclass = UnitClass.AIR_DEFENCE.value
                movmethod = MovMethod.TOWED.value
                target = UnitType.SOFT.value
                gunrange = 1
                ammo = 8
                airatk = 10
                hardatk = 8
            },
        )
        Equipment.putEquipment(
            howitzerEqid,
            EquipmentData().apply {
                name = "Field Battery"
                uclass = UnitClass.ARTILLERY.value
                movmethod = MovMethod.TOWED.value
                target = UnitType.SOFT.value
                gunrange = 2
                ammo = 8
                softatk = 11
                hardatk = 7
            },
        )
        Equipment.putEquipment(
            staffEqid,
            EquipmentData().apply {
                name = "Divisional Staff"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
                // OG's `Combat Support`, attr bit 16.
                attr = 65536
            },
        )
    }

    private fun putAircraftAndInfantry() {
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Carrier Fighter"
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
                target = UnitType.AIR.value
                gunrange = 1
                ammo = 8
                airatk = 12
            },
        )
        Equipment.putEquipment(
            bomberEqid,
            EquipmentData().apply {
                name = "Level Bomber"
                uclass = UnitClass.TACTICAL_BOMBER.value
                movmethod = MovMethod.AIR.value
                target = UnitType.AIR.value
                ammo = 6
                hardatk = 11
                softatk = 11
            },
        )
        Equipment.putEquipment(
            marinesEqid,
            EquipmentData().apply {
                name = "Marine Battalion"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
                softatk = 7
                grounddef = 5
            },
        )
    }

    @AfterTest
    fun teardown() {
        EfileConfig.resetForTest()
        clearTestWorld()
    }

    /**
     * Both gates on, with the enter/launch bit so a passenger can be put aboard in the test.
     *
     * [flakRange] is OG's own `flak_range`; the air-defence cases need 2 because a supporter answers
     * for a NEIGHBOUR, so the container is one hex further from the attacker than the defender is.
     */
    private fun containersOn(
        mode: Int = 1 or 2,
        flakRange: Int = 1,
    ) {
        ruleset(RuleKey.CARRIER_HANGARS to 1, RuleKey.FLAK_RANGE to flakRange)
        EfileConfig.setForTest(mapOf("ground_carrier" to mode))
    }

    // ---- FireSupport and AirDefense from inside -----------------------------------------------

    /** A battery inside a bunker answers for the neighbour the bunker is beside. */
    @Test
    fun aContainedBatteryGivesFireSupportFromItsContainersHex() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val battery = place(map, howitzerEqid, 5, 5, 0)
        val neighbour = place(map, marinesEqid, 3, 4, 0)
        val enemy = place(map, marinesEqid, 3, 5, 1)

        assertTrue(CarrierHangars.board(map, battery, bunker))
        assertEquals(bunker.getPos()?.row, CarrierHangars.supportPosition(battery)?.row)

        val support = CombatResolver.getSupportFireUnits(map.getUnits().toList(), enemy, neighbour)
        assertTrue(support.any { it === battery }, "the garrison fires from the bunker's hex")
    }

    /** A FlaK inside a bunker answers an air attack on the neighbour -- the AirDefense half. */
    @Test
    fun aContainedFlakAirDefendsFromItsContainersHex() {
        containersOn(flakRange = 2)
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val flak = place(map, flakEqid, 5, 5, 0)
        val neighbour = place(map, marinesEqid, 3, 4, 0)
        val bomber = place(map, bomberEqid, 3, 5, 1)

        assertTrue(CarrierHangars.board(map, flak, bunker))
        val support = CombatResolver.getSupportFireUnits(map.getUnits().toList(), bomber, neighbour)
        assertTrue(support.any { it === flak }, "the gun in the casemate still shoots at aircraft")
    }

    /** Without bit 2 the same garrison is silent -- the bit is the whole permission. */
    @Test
    fun withoutBitTwoAContainedBatteryDoesNothing() {
        containersOn(mode = 1)
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val battery = place(map, howitzerEqid, 5, 5, 0)
        val neighbour = place(map, marinesEqid, 3, 4, 0)
        val enemy = place(map, marinesEqid, 3, 5, 1)

        assertTrue(CarrierHangars.board(map, battery, bunker))
        assertFalse(CarrierHangars.supportFromInsideEnabled())
        assertEquals(null, CarrierHangars.supportPosition(battery), "and it has no position of its own")
        val support = CombatResolver.getSupportFireUnits(map.getUnits().toList(), enemy, neighbour)
        assertTrue(support.none { it === battery })
    }

    /** Range is measured from the container, so a garrison two hexes away answers for nobody. */
    @Test
    fun theContainersHexIsWhatTheRangeIsMeasuredFrom() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 0, 0, 0)
        val battery = place(map, howitzerEqid, 5, 5, 0)
        val neighbour = place(map, marinesEqid, 3, 4, 0)
        val enemy = place(map, marinesEqid, 3, 5, 1)

        assertTrue(CarrierHangars.board(map, battery, bunker))
        val support = CombatResolver.getSupportFireUnits(map.getUnits().toList(), enemy, neighbour)
        assertTrue(support.none { it === battery }, "a bunker in the far corner supports nobody")
    }

    // ---- Combat Support from inside ------------------------------------------------------------

    /** The third of the three options: a staff element lends its bars from inside. */
    @Test
    fun aContainedStaffLendsItsCombatSupportBars() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val staff = place(map, staffEqid, 5, 5, 0).apply { experience = 300 }
        val neighbour = place(map, marinesEqid, 3, 4, 0)

        assertEquals(0, UnitCapabilities.combatSupportBars(map.getUnits().toList(), neighbour))
        assertTrue(CarrierHangars.board(map, staff, bunker))
        assertEquals(
            3,
            UnitCapabilities.combatSupportBars(map.getUnits().toList(), neighbour),
            "three bars, lent from the bunker the staff is inside",
        )
    }

    // ---- The owner's ruling: aircraft never fire from a hangar ---------------------------------

    /** A fighter in a hangar does not air-defend, whatever bit 2 says. */
    @Test
    fun aContainedFighterNeverAnswersAnAirAttack() {
        containersOn(flakRange = 2)
        val map = world()
        val ship = place(map, shipEqid, 3, 3, 0)
        val fighter = place(map, fighterEqid, 5, 5, 0)
        val neighbour = place(map, marinesEqid, 3, 4, 0)
        val bomber = place(map, bomberEqid, 3, 5, 1)

        val onDeck = CombatResolver.getSupportFireUnits(map.getUnits().toList(), bomber, neighbour)
        assertTrue(
            onDeck.any { it === fighter },
            "on the map the fighter IS an air-defence supporter, or the next assertion proves nothing",
        )

        assertTrue(CarrierHangars.board(map, fighter, ship))
        assertFalse(CarrierHangars.supportsFromInside(fighter), "an aircraft that has not launched does not shoot")
        assertEquals(null, CarrierHangars.supportPosition(fighter), "it borrows no position either")
        val contained = CombatResolver.getSupportFireUnits(map.getUnits().toList(), bomber, neighbour)
        assertTrue(contained.none { it === fighter })
    }

    /** Nor does it lend Combat Support bars. */
    @Test
    fun aContainedAircraftIsNotACombatSupporterEither() {
        containersOn()
        val map = world()
        val ship = place(map, shipEqid, 3, 3, 0)
        val fighter = place(map, fighterEqid, 5, 5, 0).apply { experience = 300 }
        val neighbour = place(map, marinesEqid, 3, 4, 0)

        assertTrue(CarrierHangars.board(map, fighter, ship))
        assertEquals(0, UnitCapabilities.combatSupportBars(map.getUnits().toList(), neighbour))
    }

    // ---- Bit 8, and containers that are not carriers -------------------------------------------

    /** `hangarCap` on a non-carrier class is a container: 694 of the 916 shipped records are. */
    @Test
    fun anyRecordWithCapacityIsAContainer() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        assertEquals(2, CarrierHangars.capacity(bunker), "an infantry-class casemate still holds two")
        assertTrue(CarrierHangars.board(map, place(map, marinesEqid, 4, 4, 0), bunker))
    }

    /** *"8 allow land units to enter naval-class carriers out of port"* -- without it, a port. */
    @Test
    fun landUnitsNeedBitEightOrAPortToBoardAShip() {
        containersOn(mode = 1 or 2)
        val map = world()
        val ship = place(map, shipEqid, 3, 3, 0)
        val marines = place(map, marinesEqid, 3, 4, 0)
        assertFalse(CarrierHangars.canBoard(marines, ship), "at sea, with no bit 8, the ramp stays up")

        portAt(map, 3, 3)
        assertTrue(CarrierHangars.canBoard(marines, ship), "in a port it loads")
    }

    /** With bit 8 the same battalion boards anywhere -- LXF's own `ground_carrier = 11`. */
    @Test
    fun bitEightLetsLandUnitsBoardAtSea() {
        containersOn(mode = 1 or 2 or 8)
        val map = world()
        val ship = place(map, shipEqid, 3, 3, 0)
        val marines = place(map, marinesEqid, 3, 4, 0)
        assertTrue(CarrierHangars.canBoard(marines, ship))
    }

    /** An aircraft is unaffected by bit 8 -- the sentence names land units. */
    @Test
    fun bitEightSaysNothingAboutAircraft() {
        containersOn(mode = 1 or 2)
        val map = world()
        val ship = place(map, shipEqid, 3, 3, 0)
        val fighter = place(map, fighterEqid, 3, 4, 0)
        assertTrue(CarrierHangars.canBoard(fighter, ship))
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    /** A passenger that fired in support gets its flags back with the round, or a bunker garrison
     *  would answer once in the whole battle. */
    @Test
    fun aPassengersTurnFlagsAreResetWithTheRound() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val battery = place(map, howitzerEqid, 5, 5, 0)

        assertTrue(CarrierHangars.board(map, battery, bunker))
        battery.hasFired = true
        battery.hasSupportedThisTurn = true

        CarrierHangars.endRoundForContained(map, bunker, 0)
        assertFalse(battery.hasFired, "the round gave the battery its fire back")
        assertFalse(battery.hasSupportedThisTurn)
    }

    /** Launching clears the back-reference, so a formation back on the map fires from its own hex. */
    @Test
    fun launchingRestoresTheUnitsOwnPosition() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val marines = place(map, marinesEqid, 4, 4, 0)

        assertTrue(CarrierHangars.board(map, marines, bunker))
        assertEquals(bunker, marines.containedIn)
        map.turn += 1
        assertFalse(
            CarrierHangars.launch(map, marines, bunker),
            "the bunker itself holds the hex's one ground slot, so there is nowhere to step",
        )
        val beside = map.map!![3][4]
        assertTrue(CarrierHangars.launch(map, marines, bunker, beside))
        assertEquals(null, marines.containedIn)
        assertEquals(3, marines.getPos()?.row, "it steps out beside the bunker")
        assertEquals(4, marines.getPos()?.col)
    }

    /** Losing the container loses the garrison, and the back-reference with it. */
    @Test
    fun theGarrisonGoesDownWithItsContainer() {
        containersOn()
        val map = world()
        val bunker = place(map, bunkerEqid, 3, 3, 0)
        val battery = place(map, howitzerEqid, 5, 5, 0)

        assertTrue(CarrierHangars.board(map, battery, bunker))
        CarrierHangars.sinkWith(bunker)
        assertTrue(battery.destroyed)
        assertEquals(null, battery.containedIn)
    }

    private fun portAt(
        map: GameMap,
        row: Int,
        col: Int,
    ) {
        map.map!![row][col].terrain = TerrainType.PORT.value
    }
}
