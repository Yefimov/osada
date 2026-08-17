package org.osada.rules

import org.osada.GroundCondition
import org.osada.WeatherCondition
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ground state machine (`tools/og-import/DEFERRED.md` §7.46).
 *
 * OG: "When there are **several continuous turns of rain**, the ground condition changes to Mud, and
 * after several turns without rain, it changes to Dry. In case of several turns of snow, the ground
 * condition changes to Frozen, and after some without snow, it changes to **mud**."
 *
 * Both halves of that were wrong before: OSADA mired the ground on the first turn of rain, and a
 * thaw snapped back to whatever the scenario author had written instead of going through mud.
 */
class GroundConditionModelTest {
    private val dry = GroundCondition.DRY.value
    private val frozen = GroundCondition.FROZEN.value
    private val mud = GroundCondition.MUD.value

    @BeforeTest
    fun setup() {
        GroundConditionModel.reset()
        ActiveRuleset.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        ActiveRuleset.resetForTest()
    }

    /** Locks in a ruleset with [overrides] laid over OSADA's defaults. */
    private fun ruleset(vararg overrides: Pair<RuleKey, Int>) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "custom-1",
                name = "Test",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + overrides.toMap(),
            ),
        )
    }

    /** Feeds [turns] turns of [sky] and returns the ground at the end. */
    private fun run(
        ground: Int,
        sky: WeatherCondition,
        turns: Int,
    ): Int {
        var current = ground
        repeat(turns) { current = GroundConditionModel.advance(current, sky.value) }
        return current
    }

    // ---- a run is required, not a single turn ---------------------------------------------------

    @Test
    fun oneTurnOfRainDoesNotMireTheGround() {
        assertEquals(dry, GroundConditionModel.advance(dry, WeatherCondition.RAIN.value))
    }

    @Test
    fun rainMiresTheGroundOnlyOnceItsRunCompletes() {
        var ground = dry
        repeat(GroundConditionModel.TURNS_TO_CHANGE - 1) {
            ground = GroundConditionModel.advance(ground, WeatherCondition.RAIN.value)
            assertEquals(dry, ground, "still dry before the run completes")
        }

        ground = GroundConditionModel.advance(ground, WeatherCondition.RAIN.value)

        assertEquals(mud, ground)
    }

    @Test
    fun snowFreezesTheGroundOnTheSameSchedule() {
        assertEquals(frozen, run(dry, WeatherCondition.SNOW, GroundConditionModel.TURNS_TO_CHANGE))
    }

    @Test
    fun aBrokenRunStartsAgainFromScratch() {
        var ground = dry
        // Two wet turns, one clear turn, two wet turns: never three in a row, so never mud.
        ground = GroundConditionModel.advance(ground, WeatherCondition.RAIN.value)
        ground = GroundConditionModel.advance(ground, WeatherCondition.RAIN.value)
        ground = GroundConditionModel.advance(ground, WeatherCondition.FAIR.value)
        ground = GroundConditionModel.advance(ground, WeatherCondition.RAIN.value)
        ground = GroundConditionModel.advance(ground, WeatherCondition.RAIN.value)

        assertEquals(dry, ground)
    }

    // ---- the thaw goes through mud ---------------------------------------------------------------

    @Test
    fun frozenGroundThawsIntoMudRatherThanStraightToDry() {
        assertEquals(mud, run(frozen, WeatherCondition.FAIR, GroundConditionModel.TURNS_TO_CHANGE))
    }

    @Test
    fun theSecondDryRunIsWhatFinallyTakesTheMudAway() {
        val afterFirstRun = run(frozen, WeatherCondition.FAIR, GroundConditionModel.TURNS_TO_CHANGE)
        assertEquals(mud, afterFirstRun)

        // One step per completed run, never two on the same turn.
        assertEquals(mud, run(afterFirstRun, WeatherCondition.FAIR, GroundConditionModel.TURNS_TO_CHANGE - 1))
        assertEquals(dry, GroundConditionModel.advance(mud, WeatherCondition.FAIR.value))
    }

    @Test
    fun dryGroundStaysDryHoweverLongTheSpellRuns() {
        assertEquals(dry, run(dry, WeatherCondition.FAIR, GroundConditionModel.TURNS_TO_CHANGE * 4))
    }

    // ---- overcast is not precipitation ------------------------------------------------------------

    @Test
    fun overcastNeitherMiresNorFreezes() {
        assertEquals(dry, run(dry, WeatherCondition.OVERCAST, GroundConditionModel.TURNS_TO_CHANGE * 2))
    }

    @Test
    fun overcastCountsTowardsDryingOutBecauseItIsNotRain() {
        assertEquals(mud, run(frozen, WeatherCondition.OVERCAST, GroundConditionModel.TURNS_TO_CHANGE))
    }

    // ---- precedence ---------------------------------------------------------------------------------

    @Test
    fun aSnowRunFreezesGroundThatWasMud() {
        assertEquals(frozen, run(mud, WeatherCondition.SNOW, GroundConditionModel.TURNS_TO_CHANGE))
    }

    @Test
    fun aRainRunMiresGroundThatWasFrozen() {
        // "after some without snow, it changes to mud" -- rain is without snow, and mires it anyway.
        assertEquals(mud, run(frozen, WeatherCondition.RAIN, GroundConditionModel.TURNS_TO_CHANGE))
    }

    @Test
    fun groundAlreadyInTheTargetStateDoesNotConsumeTheRun() {
        // Rain onto ground that is already mud changes nothing, and the run keeps counting rather
        // than being spent on a no-op.
        assertEquals(mud, run(mud, WeatherCondition.RAIN, GroundConditionModel.TURNS_TO_CHANGE * 2))
    }

    // ---- ruleset control ---------------------------------------------------------------------

    @Test
    fun theRunLengthIsConfigurable() {
        ruleset(RuleKey.GROUND_CHANGE_TURNS to 1)

        assertEquals(mud, GroundConditionModel.advance(dry, WeatherCondition.RAIN.value))
    }

    @Test
    fun aLongerRunLengthHoldsTheGroundForLonger() {
        ruleset(RuleKey.GROUND_CHANGE_TURNS to 6)

        assertEquals(dry, run(dry, WeatherCondition.RAIN, 5))
        assertEquals(mud, GroundConditionModel.advance(dry, WeatherCondition.RAIN.value))
    }

    @Test
    fun theAuthorsOwnChoiceIsHonouredByDefault() {
        assertTrue(GroundConditionModel.followsWeather(authorAllows = true))
        assertFalse(GroundConditionModel.followsWeather(authorAllows = false))
    }

    @Test
    fun aProfileCanRefuseWeatherDrivenGroundOutright() {
        ruleset(RuleKey.GROUND_FOLLOWS_WEATHER to GroundConditionModel.FOLLOW_NEVER)

        assertFalse(GroundConditionModel.followsWeather(authorAllows = true))
        assertFalse(GroundConditionModel.followsWeather(authorAllows = false))
    }

    @Test
    fun aProfileCanForceItOnWhereTheAuthorSwitchedItOff() {
        ruleset(RuleKey.GROUND_FOLLOWS_WEATHER to GroundConditionModel.FOLLOW_ALWAYS)

        assertTrue(GroundConditionModel.followsWeather(authorAllows = false))
    }
}
