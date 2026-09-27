package org.osada.rules.ruleset

import org.osada.Game
import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.resetEquipment
import org.osada.rules.AttackEligibility
import org.osada.rules.WeatherCombatRules
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The opt-in "aircraft in bad weather" trial (`docs/design/combat-movement-heroes-weather-audit.md`
 * §7.3): a built-in overlay that lets aircraft start attacks in overcast, rain and snow while every
 * existing weather penalty still applies. It must never change the other profiles.
 */
class AviationTrialProfileTest {
    private val fighterEqid = 7301

    @BeforeTest
    fun setup() {
        EfileConfig.resetForTest()
        ActiveRuleset.resetForTest()
        GameHolder.instance = Game().apply { scenario = Scenario(null).apply { atmosferic = SNOW } }
        Equipment.resetEquipment()
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                ammo = 8
            },
        )
    }

    @AfterTest
    fun tearDown() {
        ActiveRuleset.resetForTest()
        GameHolder.instance = null
    }

    private fun select(source: RulesetSource) {
        ActiveRuleset.set(RulesetResolver.resolve(RulesetProfileStore.builtIns().first { it.source == source }))
    }

    private fun fighter() =
        GameUnit(fighterEqid).apply {
            owner = 0
            player = Player().apply { id = 0 }
        }

    @Test
    fun theTrialIsABuiltInThatOnlyLiftsTheGrounding() {
        val trial = RulesetProfileStore.builtIns().single { it.source == RulesetSource.AVIATION_TRIAL }
        assertEquals(RulesetProfile.AVIATION_TRIAL_ID, trial.id)
        assertEquals(mapOf(RuleKey.WEATHER_GROUNDS_AIRCRAFT to 0), trial.overrides)
    }

    @Test
    fun underTheTrialAircraftMayAttackInSnowWithThePenaltiesStillOn() {
        select(RulesetSource.AVIATION_TRIAL)
        assertFalse(AttackEligibility.airGroundedByWeather(fighter()))
        assertTrue(ActiveRuleset.flag(RuleKey.WEATHER_HALVES_AIR_GROUND, false), "halved air-ground fire stays")
        assertEquals(3, WeatherCombatRules.defenseBonus(), "the rain/snow defence bonus stays")
    }

    @Test
    fun authorsVisionStillGroundsAircraftInSnow() {
        select(RulesetSource.AUTHORS_VISION)
        assertTrue(AttackEligibility.airGroundedByWeather(fighter()))
    }

    private companion object {
        const val SNOW = 3
    }
}
