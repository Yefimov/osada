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
import org.osada.model.Player
import org.osada.model.resetEquipment
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three Open General weather rules OSADA was missing
 * (`Manual_OG-en.pdf`, recorded in `tools/og-import/DEFERRED.md` §7.45):
 * halved strength across the air/ground boundary, +3 defence in rain or snow, and halved spotting.
 *
 * The property that matters most is the first case: in Fair weather every one of these must be a
 * no-op, or the whole existing combat corpus silently changes meaning.
 */
class WeatherCombatRulesTest {
    private val fighterEqid = 1
    private val flakEqid = 2
    private val tankEqid = 3

    private val player =
        Player().apply {
            id = 0
            side = 0
        }
    private val enemy =
        Player().apply {
            id = 1
            side = 1
        }

    @BeforeTest
    fun setup() {
        GameHolder.instance = Game().apply { scenario = Scenario(null) }
        Equipment.resetEquipment()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Fighter"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                spotrange = 5
            },
        )
        Equipment.putEquipment(
            flakEqid,
            EquipmentData().apply {
                name = "Flak"
                uclass = UnitClass.AIR_DEFENCE.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.WHEELED.value
                spotrange = 3
            },
        )
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                name = "Tank"
                uclass = UnitClass.TANK.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                spotrange = 2
            },
        )
    }

    @AfterTest
    fun tearDown() {
        Equipment.resetEquipment()
        ActiveRuleset.resetForTest()
        GameHolder.instance = null
    }

    /** Locks in a ruleset with [rule] switched off and everything else at OSADA's defaults. */
    private fun switchOff(rule: RuleKey) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "custom-1",
                name = "No weather",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + (rule to 0),
            ),
        )
    }

    private fun weather(condition: WeatherCondition) {
        GameHolder.instance?.scenario?.atmosferic = condition.value
    }

    private fun unit(
        eqid: Int,
        owner: Player = player,
        strength: Int = 10,
        leader: LeaderType? = null,
    ) = GameUnit(eqid).apply {
        this.player = owner
        this.owner = owner.id
        this.strength = strength
        if (leader != null) this.leader = leader.value
    }

    // ---- fair weather changes nothing ---------------------------------------------------------

    @Test
    fun fairWeatherLeavesEverySignalUntouched() {
        weather(WeatherCondition.FAIR)
        val flak = unit(flakEqid)
        val plane = unit(fighterEqid, enemy)

        assertEquals(0, WeatherCombatRules.defenseBonus())
        assertEquals(10, WeatherCombatRules.firingStrength(flak, plane))
        assertEquals(4, WeatherCombatRules.spotRange(flak, 4))
        assertEquals(4, WeatherCombatRules.spotRange(plane, 4))
    }

    // ---- attacking strength -------------------------------------------------------------------

    @Test
    fun badWeatherHalvesFireAcrossTheAirGroundBoundary() {
        val flak = unit(flakEqid)
        val plane = unit(fighterEqid, enemy)

        listOf(WeatherCondition.OVERCAST, WeatherCondition.RAIN, WeatherCondition.SNOW).forEach { sky ->
            weather(sky)
            assertEquals(5, WeatherCombatRules.firingStrength(flak, plane), "flak at a plane in ${sky.name}")
            assertEquals(5, WeatherCombatRules.firingStrength(plane, flak), "plane at flak in ${sky.name}")
        }
    }

    @Test
    fun combatWithinOneLayerIsNotHalved() {
        weather(WeatherCondition.SNOW)

        assertEquals(
            10,
            WeatherCombatRules.firingStrength(unit(tankEqid), unit(tankEqid, enemy)),
            "ground versus ground is not air-to-ground combat",
        )
        assertEquals(
            10,
            WeatherCombatRules.firingStrength(unit(fighterEqid), unit(fighterEqid, enemy)),
            "air versus air is not air-to-ground combat either",
        )
    }

    @Test
    fun theAllWeatherCommanderFiresAtFullStrength() {
        weather(WeatherCondition.RAIN)
        val plane = unit(fighterEqid, leader = LeaderType.ALL_WEATHER_COMBAT)

        assertEquals(10, WeatherCombatRules.firingStrength(plane, unit(flakEqid, enemy)))
    }

    /** OG's `All Weather` equipment special (`attrEx` bit 2), wired 2026-08-19 as the second source
     *  of the same exemption `UnitCapabilities.hasAllWeather` grants alongside the leader trait. */
    @Test
    fun theAllWeatherEquipmentSpecialAlsoFiresAtFullStrength() {
        weather(WeatherCondition.RAIN)
        Equipment.getEquipment(fighterEqid)!!.attrEx = 4 // All Weather
        val plane = unit(fighterEqid)

        assertEquals(10, WeatherCombatRules.firingStrength(plane, unit(flakEqid, enemy)))
    }

    /** Rounded up, so a one-strength unit still fires. A silent attack that can never do anything
     *  is indistinguishable from a bug. */
    @Test
    fun halvingRoundsUpAndNeverSilencesAUnitCompletely() {
        weather(WeatherCondition.OVERCAST)
        val plane = unit(fighterEqid, enemy)

        assertEquals(1, WeatherCombatRules.firingStrength(unit(flakEqid, strength = 1), plane))
        assertEquals(2, WeatherCombatRules.firingStrength(unit(flakEqid, strength = 3), plane))
        assertEquals(5, WeatherCombatRules.firingStrength(unit(flakEqid, strength = 9), plane))
    }

    // ---- defence --------------------------------------------------------------------------------

    @Test
    fun defenceRisesByThreeInRainAndSnowOnly() {
        weather(WeatherCondition.OVERCAST)
        assertEquals(0, WeatherCombatRules.defenseBonus(), "overcast is not precipitation")

        weather(WeatherCondition.RAIN)
        assertEquals(3, WeatherCombatRules.defenseBonus())

        weather(WeatherCondition.SNOW)
        assertEquals(3, WeatherCombatRules.defenseBonus())
    }

    @Test
    fun theDefenceBonusReachesBothHalvesOfAnExchange() {
        weather(WeatherCondition.SNOW)
        val stats = AttackCalculation.CombatStats(attackerDefense = 10, defenderDefense = 20)

        WeatherCombatRules.applyDefenseBonus(stats)

        assertEquals(13, stats.attackerDefense)
        assertEquals(23, stats.defenderDefense)
    }

    // ---- spotting -------------------------------------------------------------------------------

    @Test
    fun aircraftLoseHalfTheirSightInAnySkyWorseThanFair() {
        val plane = unit(fighterEqid)

        listOf(WeatherCondition.OVERCAST, WeatherCondition.RAIN, WeatherCondition.SNOW).forEach { sky ->
            weather(sky)
            assertEquals(3, WeatherCombatRules.spotRange(plane, 5), "aircraft in ${sky.name}")
        }
    }

    @Test
    fun groundUnitsLoseHalfTheirSightOnlyInPrecipitation() {
        val tank = unit(tankEqid)

        weather(WeatherCondition.OVERCAST)
        assertEquals(4, WeatherCombatRules.spotRange(tank, 4), "overcast does not blind ground units")

        weather(WeatherCondition.RAIN)
        assertEquals(2, WeatherCombatRules.spotRange(tank, 4))

        weather(WeatherCondition.SNOW)
        assertEquals(2, WeatherCombatRules.spotRange(tank, 4))
    }

    @Test
    fun spottingNeverCollapsesToNothing() {
        weather(WeatherCondition.SNOW)

        assertEquals(1, WeatherCombatRules.spotRange(unit(tankEqid), 1))
    }

    @Test
    fun theAllWeatherCommanderKeepsFullSight() {
        weather(WeatherCondition.SNOW)
        val plane = unit(fighterEqid, leader = LeaderType.ALL_WEATHER_COMBAT)

        assertEquals(5, WeatherCombatRules.spotRange(plane, 5))
    }

    // ---- the wiring is real ---------------------------------------------------------------------

    @Test
    fun theSpotRangeAccessorTheFogOfWarUsesGoesThroughTheWeather() {
        // `MovementRules.setSpotRange` reads this, so patching it is what makes the rule real
        // rather than a helper nobody calls.
        val tank = unit(tankEqid)

        weather(WeatherCondition.FAIR)
        assertEquals(2, MovementRules.getUnitSpotRange(tank))

        weather(WeatherCondition.RAIN)
        assertEquals(1, MovementRules.getUnitSpotRange(tank))
    }

    // ---- every rule is switchable, and only the one switched ------------------------------------

    @Test
    fun turningOffTheGroundingLetsAircraftAttackInAnyWeather() {
        weather(WeatherCondition.SNOW)
        val plane = unit(fighterEqid)
        assertTrue(AttackEligibility.airGroundedByWeather(plane))

        switchOff(RuleKey.WEATHER_GROUNDS_AIRCRAFT)

        assertFalse(AttackEligibility.airGroundedByWeather(plane))
    }

    @Test
    fun turningOffTheHalvingRestoresFullStrengthAcrossTheLayers() {
        weather(WeatherCondition.SNOW)
        val flak = unit(flakEqid)
        val plane = unit(fighterEqid, enemy)
        assertEquals(5, WeatherCombatRules.firingStrength(flak, plane))

        switchOff(RuleKey.WEATHER_HALVES_AIR_GROUND)

        assertEquals(10, WeatherCombatRules.firingStrength(flak, plane))
    }

    @Test
    fun turningOffTheDefenceBonusRemovesIt() {
        weather(WeatherCondition.RAIN)
        assertEquals(3, WeatherCombatRules.defenseBonus())

        switchOff(RuleKey.WEATHER_DEFENSE_BONUS)

        assertEquals(0, WeatherCombatRules.defenseBonus())
    }

    @Test
    fun turningOffTheSpottingPenaltyRestoresFullSight() {
        weather(WeatherCondition.SNOW)
        val tank = unit(tankEqid)
        assertEquals(2, WeatherCombatRules.spotRange(tank, 4))

        switchOff(RuleKey.WEATHER_HALVES_SPOTTING)

        assertEquals(4, WeatherCombatRules.spotRange(tank, 4))
    }

    /** Four switches, not one: turning a single rule off must leave the other three alone, or the
     *  player cannot tell which of the four they actually changed. */
    @Test
    fun eachSwitchIsIndependentOfTheOtherThree() {
        weather(WeatherCondition.SNOW)
        switchOff(RuleKey.WEATHER_DEFENSE_BONUS)

        assertEquals(0, WeatherCombatRules.defenseBonus(), "the one switched off")
        assertTrue(AttackEligibility.airGroundedByWeather(unit(fighterEqid)), "grounding still on")
        assertEquals(
            5,
            WeatherCombatRules.firingStrength(unit(flakEqid), unit(fighterEqid, enemy)),
            "halving still on",
        )
        assertEquals(2, WeatherCombatRules.spotRange(unit(tankEqid), 4), "spotting still on")
    }

    /** Every weather rule ships ON, so a player who never opens the Rules window sees OG's rules. */
    @Test
    fun allFourShipEnabled() {
        listOf(
            RuleKey.WEATHER_GROUNDS_AIRCRAFT,
            RuleKey.WEATHER_HALVES_AIR_GROUND,
            RuleKey.WEATHER_DEFENSE_BONUS,
            RuleKey.WEATHER_HALVES_SPOTTING,
        ).forEach { rule ->
            assertEquals(1, RulesetDefaults.OSADA.getValue(rule), rule.key)
        }
    }
}
