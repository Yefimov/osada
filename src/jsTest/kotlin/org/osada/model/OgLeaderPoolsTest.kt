package org.osada.model

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.rules.GameRandomSource
import org.osada.rules.UnitExperience
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Open General's leader tables on the legacy path ([OgLeaderPools]): OG's class attribute for every
 * class the manual names, OG's random list 9..34 filtered by what can apply, and the efile keys
 * that switch parts of it off.
 */
class OgLeaderPoolsTest {
    private val flakEqid = 901
    private val cruiserEqid = 902
    private val truckEqid = 903
    private val infantryEqid = 904

    @BeforeTest
    fun setup() {
        EfileConfig.setForTest()
        GameRandomSource.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(flakEqid, EquipmentData().apply { uclass = UnitClass.FLAK.value })
        Equipment.putEquipment(cruiserEqid, EquipmentData().apply { uclass = UnitClass.CRUISER.value })
        Equipment.putEquipment(truckEqid, EquipmentData().apply { uclass = UnitClass.GROUND_TRANSPORT.value })
        Equipment.putEquipment(infantryEqid, EquipmentData().apply { uclass = UnitClass.INFANTRY.value })
    }

    @AfterTest
    fun teardown() {
        EfileConfig.resetForTest()
    }

    // ---- which traits a scenario unit can now roll -------------------------------------------

    /** The traits the user asked about: a scenario's infantry can be born a Camouflage Expert and
     *  set up an ambush at a crossroads. PM's infantry pool had none of these. */
    @Test
    fun infantryCanRollOgsAmbushAndStaminaTraits() {
        val pool = OgLeaderPools.randomPool(UnitClass.INFANTRY.value)
        listOf(
            LeaderType.FOREST_CAMOUFLAGE,
            LeaderType.OVERWATCH,
            LeaderType.RESILIENCE,
            LeaderType.COMBAT_SUPPORT,
            LeaderType.STREET_FIGHTER,
            LeaderType.BRIDGING,
        ).forEach { assertTrue(it in pool, "$it missing from $pool") }
    }

    @Test
    fun theClassAttributeIsNeverRolledAsTheSecondTrait() {
        UnitClass.entries.forEach { cls ->
            val own = OgLeaderPools.classTrait(cls.value) ?: return@forEach
            assertFalse(own in OgLeaderPools.randomPool(cls.value), "$cls rolls its own class attribute")
        }
    }

    @Test
    fun landTraitsAreNotOfferedToShipsOrAircraft() {
        listOf(UnitClass.CRUISER, UnitClass.FIGHTER).forEach { cls ->
            val pool = OgLeaderPools.randomPool(cls.value)
            assertFalse(LeaderType.FOREST_CAMOUFLAGE in pool, "$cls")
            assertFalse(LeaderType.STREET_FIGHTER in pool, "$cls")
            assertFalse(LeaderType.BRIDGING in pool, "$cls")
        }
    }

    @Test
    fun airTraitsAreOfferedOnlyToAircraft() {
        assertTrue(LeaderType.ALL_WEATHER_COMBAT in OgLeaderPools.randomPool(UnitClass.TACTICAL_BOMBER.value))
        assertTrue(LeaderType.SKILLED_GROUND_ATTACK in OgLeaderPools.randomPool(UnitClass.FIGHTER.value))
        assertFalse(LeaderType.ALL_WEATHER_COMBAT in OgLeaderPools.randomPool(UnitClass.TANK.value))
        assertFalse(LeaderType.OVERWATCH in OgLeaderPools.randomPool(UnitClass.FIGHTER.value))
    }

    /** Every trait in every pool is one some rule reads -- the A.4 lesson. Extended Front on a gun
     *  that already supports by class would be exactly the inert promise that sweep removed. */
    @Test
    fun extendedFrontIsNotOfferedToArtillery() {
        assertFalse(LeaderType.EXTENDED_FRONT in OgLeaderPools.randomPool(UnitClass.ARTILLERY.value))
        assertTrue(LeaderType.EXTENDED_FRONT in OgLeaderPools.randomPool(UnitClass.TANK.value))
    }

    // ---- class attributes --------------------------------------------------------------------

    @Test
    fun theManualsClassAttributesAreDerived() {
        assertEquals(LeaderType.FLAK_SUPPORT, OgLeaderPools.classTrait(UnitClass.FLAK.value))
        assertEquals(LeaderType.DEEP_SEA_HUNTER, OgLeaderPools.classTrait(UnitClass.DESTROYER.value))
        assertEquals(LeaderType.TENACIOUS_DEFENSE, OgLeaderPools.classTrait(UnitClass.INFANTRY.value))
        assertNull(OgLeaderPools.classTrait(UnitClass.GROUND_TRANSPORT.value))
    }

    // ---- leaders on the classes PM's pools left empty ----------------------------------------

    /** Until 2026-09-28 `generateLeader` returned -1 here, so an authored OG leader on a Flak
     *  battery or a warship vanished on load (180 of them across 110 deployed scenarios). */
    @Test
    fun aFlakBatteryAndACruiserCanHoldALeader() {
        listOf(flakEqid, cruiserEqid).forEach { eqid ->
            val unit = GameUnit(eqid)
            val rolled = Leaders.generateLeader(unit)
            assertTrue(rolled != -1, "eqid $eqid got no leader")
            unit.leader = rolled
            assertTrue(Leaders.getUnitLeaderDescriptions(unit).size == 2, "two attributes, as OG gives")
        }
    }

    @Test
    fun aTransportMayBeAuthoredALeaderButNeverEarnsOne() {
        val truck = GameUnit(truckEqid).apply { experience = UnitExperience.cap() }
        assertTrue(Leaders.generateLeader(truck) != -1, "design time: OG 24.12.10.0 allows it")
        assertEquals(-1, Leaders.generateLeaderWithChance(truck, expGained = 0), "combat never grants one")
    }

    // ---- efile keys --------------------------------------------------------------------------

    /** `noldr_randtype` names OG list entries; NOKORP disables 14, 17, 33. */
    @Test
    fun noldrRandtypeRemovesTheNamedEntries() {
        EfileConfig.setForTest(rawKeyMap = mapOf("noldr_randtype" to "14, 17, 33"))
        val pool = OgLeaderPools.randomPool(UnitClass.INFANTRY.value)
        assertFalse(LeaderType.INFLUENCE in pool)
        assertFalse(LeaderType.ALPINE_TRAINING in pool)
        assertFalse(LeaderType.BRIDGING in pool)
        assertTrue(LeaderType.FOREST_CAMOUFLAGE in pool)
    }

    /** `noldr_classunits` names OG classes: OG 5 is Flak, the same number in OSADA. */
    @Test
    fun noldrClassunitsRefusesTheNamedClass() {
        EfileConfig.setForTest(rawKeyMap = mapOf("noldr_classunits" to "5"))
        assertEquals(-1, Leaders.generateLeader(GameUnit(flakEqid)))
        assertTrue(Leaders.generateLeader(GameUnit(infantryEqid)) != -1)
    }
}
