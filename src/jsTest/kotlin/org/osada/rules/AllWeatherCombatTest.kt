package org.osada.rules

import org.osada.Game
import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.WeatherCondition
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.Player
import org.osada.model.resetEquipment
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The All Weather Combat exception to weather grounding.
 *
 * Open General's own wording (`Manual_OG-en.pdf`): "the impossibility of air units to attack in bad
 * weather, **except for units with the All weather leader**". OSADA shipped the rule without the
 * exception, so the trait was description-only while the rule it was supposed to escape was live.
 *
 * The second half matters as much as the first: a trait no air formation can ever hold is still
 * inert however correct the predicate is, which is what the last two cases lock.
 */
class AllWeatherCombatTest {
    private val fighterEqid = 1
    private val infantryEqid = 2

    private val player =
        Player().apply {
            id = 0
            side = 0
        }

    @BeforeTest
    fun setup() {
        // `airGroundedByWeather` reads the LIVE scenario through GameHolder; a test without one
        // would pass vacuously on the "fair weather" default and prove nothing.
        GameHolder.instance = Game().apply { scenario = Scenario(null) }
        Equipment.resetEquipment()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Fighter"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                ammo = 8
            },
        )
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Infantry"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                ammo = 8
            },
        )
    }

    @AfterTest
    fun tearDown() {
        Equipment.resetEquipment()
        GameHolder.instance = null
    }

    private fun setWeather(atmospheric: Int) {
        GameHolder.instance?.scenario?.atmosferic = atmospheric
    }

    private fun unit(
        eqid: Int,
        leader: LeaderType? = null,
    ) = GameUnit(eqid).apply {
        this.player = this@AllWeatherCombatTest.player
        owner = this@AllWeatherCombatTest.player.id
        strength = 10
        ammo = 8
        if (leader != null) this.leader = leader.value
    }

    @Test
    fun anOrdinaryAircraftIsGroundedByEveryNonFairSky() {
        val plane = unit(fighterEqid)

        listOf(WeatherCondition.OVERCAST, WeatherCondition.RAIN, WeatherCondition.SNOW).forEach { sky ->
            setWeather(sky.value)
            assertTrue(AttackEligibility.airGroundedByWeather(plane), "grounded in ${sky.name}")
        }
        setWeather(WeatherCondition.FAIR.value)
        assertFalse(AttackEligibility.airGroundedByWeather(plane))
    }

    @Test
    fun anAllWeatherCommanderFliesInEveryNonFairSky() {
        val plane = unit(fighterEqid, LeaderType.ALL_WEATHER_COMBAT)

        listOf(WeatherCondition.OVERCAST, WeatherCondition.RAIN, WeatherCondition.SNOW).forEach { sky ->
            setWeather(sky.value)
            assertFalse(AttackEligibility.airGroundedByWeather(plane), "flies in ${sky.name}")
        }
    }

    /** OG's `All Weather` equipment special (`SpecialEx` bit 60.2, `attrEx` bit 2), wired
     *  2026-08-19 as the second, equipment-level source of the same exemption -- see
     *  `UnitCapabilities.hasAllWeather`'s header. */
    @Test
    fun theEquipmentLevelAllWeatherSpecialAlsoFliesInEveryNonFairSky() {
        Equipment.getEquipment(fighterEqid)!!.attrEx = 4 // All Weather
        val plane = unit(fighterEqid)

        listOf(WeatherCondition.OVERCAST, WeatherCondition.RAIN, WeatherCondition.SNOW).forEach { sky ->
            setWeather(sky.value)
            assertFalse(AttackEligibility.airGroundedByWeather(plane), "flies in ${sky.name}")
        }
    }

    @Test
    fun theExemptionIsSpecificToThisTraitAndNotToHavingAnyCommander() {
        setWeather(WeatherCondition.OVERCAST.value)

        assertTrue(AttackEligibility.airGroundedByWeather(unit(fighterEqid, LeaderType.AGGRESSIVE_ATTACK)))
        assertFalse(AttackEligibility.airGroundedByWeather(unit(fighterEqid, LeaderType.ALL_WEATHER_COMBAT)))
    }

    @Test
    fun groundUnitsAreUntouchedByTheRuleEitherWay() {
        setWeather(WeatherCondition.SNOW.value)

        assertFalse(AttackEligibility.airGroundedByWeather(unit(infantryEqid)))
        assertFalse(AttackEligibility.airGroundedByWeather(unit(infantryEqid, LeaderType.ALL_WEATHER_COMBAT)))
    }

    // ---- reachability ---------------------------------------------------------------------

    /** A trait only an air unit can benefit from has to be obtainable by an air formation, or the
     *  rule above can never once fire in a real campaign. */
    @Test
    fun theTraitIsOfferedToTheAirClassesThatActuallyAttack() {
        listOf(UnitClass.FIGHTER, UnitClass.TACTICAL_BOMBER).forEach { cls ->
            assertTrue(
                Leaders.unitClassLeaders[cls.value]?.contains(LeaderType.ALL_WEATHER_COMBAT) == true,
                "${cls.name} cannot obtain All Weather Combat",
            )
        }
    }

    /** `generateLeader` skips index 0 (the class signature is granted separately), so a trait sitting
     *  first in a pool is never rolled. This one must not be placed there. */
    @Test
    fun theTraitIsNotStrandedInTheUnrollableSignatureSlot() {
        listOf(UnitClass.FIGHTER, UnitClass.TACTICAL_BOMBER).forEach { cls ->
            val pool = Leaders.unitClassLeaders[cls.value].orEmpty()
            assertTrue(pool.indexOf(LeaderType.ALL_WEATHER_COMBAT) > 0, "${cls.name} pool: $pool")
        }
    }
}
