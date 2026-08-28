package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
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
 * Open General's **Extended Naval Rules** (manual §9.6), built 2026-08-27 as schema 9
 * (`docs/og-fidelity-plan.md` §U). By shipped content this was the largest gap the project had:
 * 238 of the 457 scenarios whose source is readable author the switch.
 *
 * **Every bullet is tested OFF as well as ON**, which is the promise the key was admitted on: none
 * of the 502 shipped scenarios changes arithmetic until a profile asks for it. Two of the four
 * bullets take shots away, so the OFF cases here are the substance rather than a formality.
 */
class ExtendedNavalRulesTest : OgRulesTestHarness() {
    private val destroyerEqid = 970
    private val submarineEqid = 971
    private val navalTransportEqid = 972
    private val shoreBatteryEqid = 973
    private val cruiserEqid = 974

    /** OG's `No Intercept Air`, `attrEx` bit 5. */
    private val attrExNoInterceptAir = 32

    @BeforeTest
    fun setup() {
        installTestWorld()
        registerFleet()
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun registerFleet() {
        Equipment.putEquipment(destroyerEqid, ship("Destroyer", UnitClass.DESTROYER, gun = 2))
        Equipment.putEquipment(submarineEqid, ship("Submarine", UnitClass.SUBMARINE, gun = 3))
        Equipment.putEquipment(
            navalTransportEqid,
            ship("Naval Transport", UnitClass.NAVAL_TRANSPORT, gun = 1, naval = 0),
        )
        Equipment.putEquipment(cruiserEqid, ship("Heavy Cruiser", UnitClass.CRUISER, gun = 4))
        Equipment.putEquipment(
            shoreBatteryEqid,
            EquipmentData().apply {
                name = "Coastal Battery"
                uclass = UnitClass.FORTIFICATION.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TOWED.value
                movpoints = 0
                gunrange = 4
                ammo = 8
                navalatk = 12
                softatk = 8
                hardatk = 8
                grounddef = 10
            },
        )
    }

    private fun ship(
        label: String,
        cls: UnitClass,
        gun: Int,
        naval: Int = 10,
    ) = EquipmentData().apply {
        name = label
        uclass = cls.value
        target = UnitType.SEA.value
        movmethod = MovMethod.NAVAL.value
        movpoints = 8
        gunrange = gun
        ammo = 8
        navalatk = naval
        softatk = 4
        hardatk = 4
        grounddef = 6
        // Every ship here can engage a submarine on class or grant, so bullet 2's RANGE rule is
        // what the tests measure rather than `canAttackSubmarineTarget`'s class list.
        attrEx = ATTR_EX_ANTI_SUB
    }

    /** A sea map: the naval rules must not be measured on units standing in a field. */
    private fun sea(): GameMap =
        world().also { map ->
            for (r in 0 until map.rows) {
                for (c in 0 until map.cols) map.map!![r][c].terrain = TerrainType.OCEAN.value
            }
        }

    private fun withNavalRules(on: Boolean): GameMap {
        ruleset(RuleKey.EXTENDED_NAVAL to if (on) 1 else 0)
        val map = sea()
        GameHolder.instance = holderFor(map)
        return map
    }

    // ---- Bullet 2: ships attack submarines only at range 1 ----------------------------------

    @Test
    fun aCruiserShellsASubmarineAtRangeWithTheRuleOff() {
        val map = withNavalRules(on = false)
        val cruiser = place(map, cruiserEqid, 2, 2, side = 0)
        val submarine = place(map, submarineEqid, 2, 5, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(cruiser, submarine))
    }

    @Test
    fun theSameShotIsRefusedWithTheRuleOn() {
        val map = withNavalRules(on = true)
        val cruiser = place(map, cruiserEqid, 2, 2, side = 0)
        val submarine = place(map, submarineEqid, 2, 5, side = 1)

        assertFalse(AttackEligibility.canInitiateAttack(cruiser, submarine))
    }

    @Test
    fun aShipStillAttacksASubmarineItHasClosedWith() {
        val map = withNavalRules(on = true)
        val destroyer = place(map, destroyerEqid, 2, 2, side = 0)
        val submarine = place(map, submarineEqid, 2, 3, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(destroyer, submarine), "range 1 is allowed")
    }

    @Test
    fun theRangeRuleIsAboutSubmarinesAndNotAboutShipsGenerally() {
        val map = withNavalRules(on = true)
        val cruiser = place(map, cruiserEqid, 2, 2, side = 0)
        val destroyer = place(map, destroyerEqid, 2, 5, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(cruiser, destroyer), "a surface duel is untouched")
    }

    // ---- Bullet 4: submarines need direct line of fire ---------------------------------------

    @Test
    fun aSubmarineFiresThroughAnIslandWithTheRuleOff() {
        val map = withNavalRules(on = false)
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val submarine = place(map, submarineEqid, 2, 2, side = 0)
        val cruiser = place(map, cruiserEqid, 2, 4, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(submarine, cruiser))
    }

    @Test
    fun theIslandCutsTheSubmarinesFireWithTheRuleOn() {
        val map = withNavalRules(on = true)
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val submarine = place(map, submarineEqid, 2, 2, side = 0)
        val cruiser = place(map, cruiserEqid, 2, 4, side = 1)

        assertFalse(AttackEligibility.canInitiateAttack(submarine, cruiser))
    }

    @Test
    fun openWaterLeavesTheSubmarinesLineOfFireClear() {
        val map = withNavalRules(on = true)
        val submarine = place(map, submarineEqid, 2, 2, side = 0)
        val cruiser = place(map, cruiserEqid, 2, 4, side = 1)

        assertTrue(AttackEligibility.canInitiateAttack(submarine, cruiser))
    }

    @Test
    fun theLineOfFireRuleIsTheSubmarinesAloneAndNotEveryShip() {
        val map = withNavalRules(on = true)
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val cruiser = place(map, cruiserEqid, 2, 2, side = 0)
        val destroyer = place(map, destroyerEqid, 2, 4, side = 1)

        assertTrue(
            AttackEligibility.canInitiateAttack(cruiser, destroyer),
            "§9.6 imposes the line of fire on submarines; §9.5 is the key that imposes it generally",
        )
    }

    // ---- Bullet 1: ships return fire to artillery and forts -----------------------------------

    @Test
    fun aShoreBatteryShellsAShipWithImpunityWhenTheRuleIsOff() {
        val map = withNavalRules(on = false)
        val battery = place(map, shoreBatteryEqid, 2, 2, side = 1)
        val cruiser = place(map, cruiserEqid, 2, 5, side = 0)

        assertFalse(ExtendedNaval.shipReturnsFireToShoreBattery(battery, cruiser, distance = 3))
    }

    @Test
    fun theShipAnswersTheBatteryWithTheRuleOn() {
        val map = withNavalRules(on = true)
        val battery = place(map, shoreBatteryEqid, 2, 2, side = 1)
        val cruiser = place(map, cruiserEqid, 2, 5, side = 0)

        assertTrue(ExtendedNaval.shipReturnsFireToShoreBattery(battery, cruiser, distance = 3))
    }

    @Test
    fun aShipCannotAnswerABatteryBeyondItsOwnGuns() {
        val map = withNavalRules(on = true)
        val battery = place(map, shoreBatteryEqid, 2, 2, side = 1)
        val destroyer = place(map, destroyerEqid, 2, 6, side = 0)

        assertFalse(
            ExtendedNaval.shipReturnsFireToShoreBattery(battery, destroyer, distance = 4),
            "the recorded narrowing: the answer needs the ship's own range, as naval gunnery does",
        )
    }

    @Test
    fun theReturnFireRuleIsAboutArtilleryAndFortsAndNotAboutTanks() {
        val map = withNavalRules(on = true)
        val infantry = place(map, infantryEqid, 2, 2, side = 1)
        val cruiser = place(map, cruiserEqid, 2, 4, side = 0)

        assertFalse(ExtendedNaval.shipReturnsFireToShoreBattery(infantry, cruiser, distance = 2))
    }

    // ---- Bullet 3: destroyers escort naval transports -----------------------------------------

    @Test
    fun aDestroyerDoesNotEscortWithTheRuleOff() {
        val map = withNavalRules(on = false)
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        val escort = place(map, destroyerEqid, 2, 4, side = 0)

        assertFalse(ExtendedNaval.escortsNavalTransport(escort, submarine, transport))
    }

    @Test
    fun aDestroyerEscortsTheTransportWithTheRuleOn() {
        val map = withNavalRules(on = true)
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        val escort = place(map, destroyerEqid, 2, 4, side = 0)

        assertTrue(ExtendedNaval.escortsNavalTransport(escort, submarine, transport))
    }

    @Test
    fun theEscortIsTheExactClassTriangleOgNames() {
        val map = withNavalRules(on = true)
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        val cruiser = place(map, cruiserEqid, 2, 4, side = 0)
        val destroyer = place(map, destroyerEqid, 3, 4, side = 0)
        val otherDestroyer = place(map, destroyerEqid, 4, 4, side = 1)

        assertFalse(
            ExtendedNaval.escortsNavalTransport(cruiser, submarine, transport),
            "a cruiser is not a destroyer",
        )
        assertFalse(
            ExtendedNaval.escortsNavalTransport(destroyer, otherDestroyer, transport),
            "the attacker must be a submarine",
        )
        assertFalse(
            ExtendedNaval.escortsNavalTransport(destroyer, submarine, cruiser),
            "the escorted unit must be a naval transport",
        )
    }

    private companion object {
        /** OG's `ASW`, `attrEx` bit 12 — so the fixture's ships may engage a submarine at all. */
        const val ATTR_EX_ANTI_SUB = 4096
    }

    // ---- the escort's four narrowings (2026-08-27, from the author's combat procedure) ----------

    @Test
    fun theEscortMustBeBesideTheTransportNotMerelyInRangeOfTheSubmarine() {
        val map = withNavalRules(on = true)
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        val farEscort = place(map, destroyerEqid, 2, 5, side = 0)

        assertFalse(
            ExtendedNaval.escortsNavalTransport(farEscort, submarine, transport),
            "OG selects a destroyer ADJACENT to the defender",
        )
    }

    @Test
    fun anEscortThatHasAlreadySupportedThisTurnDoesNotEscortAgain() {
        val map = withNavalRules(on = true)
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        val escort = place(map, destroyerEqid, 2, 4, side = 0)

        assertTrue(ExtendedNaval.escortsNavalTransport(escort, submarine, transport))
        escort.hasSupportedThisTurn = true
        assertFalse(ExtendedNaval.escortsNavalTransport(escort, submarine, transport))
    }

    @Test
    fun aDestroyerWithNoInterceptAirDoesNotEscort() {
        val map = withNavalRules(on = true)
        Equipment.putEquipment(
            destroyerEqid + 10,
            ship("Radar Picket", UnitClass.DESTROYER, gun = 2).apply { attrEx = attrExNoInterceptAir },
        )
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        val picket = place(map, destroyerEqid + 10, 2, 4, side = 0)

        assertFalse(
            ExtendedNaval.escortsNavalTransport(picket, submarine, transport),
            "OG's procedure lists the condition, odd as it reads for a submarine attack",
        )
    }

    @Test
    fun onlyOneDestroyerEscortsHoweverManyScreenTheConvoy() {
        val map = withNavalRules(on = true)
        val submarine = place(map, submarineEqid, 2, 2, side = 1)
        val transport = place(map, navalTransportEqid, 2, 3, side = 0)
        // An escort must be adjacent to the transport AND able to fire on the submarine, which
        // bullet 2 caps at range 1 -- so the berths are the hexes the two share.
        val berths =
            HexGeometry
                .getAdjacent(2, 3)
                .filter { HexGeometry.distance(it.row, it.col, 2, 2) <= 1 }
                .filter { map.map!![it.row][it.col].unit == null }
        assertTrue(berths.size >= 2, "the fixture needs at least two shared berths")
        berths.forEach { place(map, destroyerEqid, it.row, it.col, side = 0) }

        val supporters =
            CombatResolver.getSupportFireUnits(map.getUnits().toList(), submarine, transport)
        val escorts =
            supporters.filter { ExtendedNaval.escortsNavalTransport(it, submarine, transport) }

        assertEquals(1, escorts.size, "OG selects the FIRST destroyer adjacent to the defender")
    }
}
