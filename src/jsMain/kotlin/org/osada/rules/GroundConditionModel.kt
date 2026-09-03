package org.osada.rules

import org.osada.GroundCondition
import org.osada.WeatherCondition
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * How the ground dries, freezes and thaws under a run of weather
 * (`tools/og-import/DEFERRED.md` §7.46).
 *
 * Open General's own wording:
 *
 * > When there are **several continuous turns of rain**, the ground condition changes to Mud, and
 * > after several turns without rain, it changes to Dry. In case of several turns of snow, the
 * > ground condition changes to Frozen, and after some without snow, it changes to **mud**.
 *
 * Two things follow from that and were previously wrong here. The ground changes after a RUN of
 * weather, not on the first turn of it; and a thaw goes Frozen → Mud → Dry, one step per run,
 * rather than snapping back to whatever the scenario author wrote.
 *
 * Kept separate from [WeatherModel] and free of any [org.osada.scenario.Scenario] reference so the
 * state machine can be driven turn by turn in a test without fighting the random weather roll.
 */
internal object GroundConditionModel {
    /**
     * `INFERENCE`: OG says "several continuous turns" and never gives a number. Three is the
     * smallest count that reads as "several" rather than "a couple", and it leaves a scenario a
     * turn or two of warning before the roads go — which is the tactical point of the rule.
     *
     * Because it is a guess rather than a quoted rule, it is also the one the ruleset exposes
     * (`ground_change_turns`).
     */
    const val TURNS_TO_CHANGE = 3

    /** [RuleKey.GROUND_FOLLOWS_WEATHER]: never let the weather touch the ground. */
    const val FOLLOW_NEVER = 0

    /** [RuleKey.GROUND_FOLLOWS_WEATHER]: honour each scenario's own `weatherchg`. */
    const val FOLLOW_AS_AUTHORED = 1

    /** [RuleKey.GROUND_FOLLOWS_WEATHER]: run the simulation even where the author switched it off. */
    const val FOLLOW_ALWAYS = 2

    private fun turnsToChange(): Int =
        ActiveRuleset
            .intKey(RuleKey.GROUND_CHANGE_TURNS, TURNS_TO_CHANGE)
            .coerceIn(RuleKey.GROUND_CHANGE_TURNS.editorMin, RuleKey.GROUND_CHANGE_TURNS.editorMax)

    /**
     * Whether the ground may move at all this turn. [authorAllows] is the scenario's own
     * `weatherchg`; the ruleset can refuse it outright or override it in either direction, which is
     * exactly what a custom profile is for.
     */
    fun followsWeather(authorAllows: Boolean): Boolean =
        when (ActiveRuleset.intKey(RuleKey.GROUND_FOLLOWS_WEATHER, FOLLOW_AS_AUTHORED)) {
            FOLLOW_NEVER -> false
            FOLLOW_ALWAYS -> true
            else -> authorAllows
        }

    private var rainRun = 0
    private var snowRun = 0
    private var dryRun = 0

    fun reset() {
        rainRun = 0
        snowRun = 0
        dryRun = 0
    }

    /** The three run counters, for [org.osada.ui.WeatherModel.snapshot]. They are the only part of
     *  this state machine a save has to carry: everything else is re-derived from the scenario. */
    fun runs(): Triple<Int, Int, Int> = Triple(rainRun, snowRun, dryRun)

    /** Puts the counters back where a save left them. Negative values from a hand-edited or
     *  truncated save are clamped rather than rejected -- a broken run counter must not stop the
     *  battle from loading, and zero is simply "the run starts now". */
    fun restoreRuns(
        rain: Int,
        snow: Int,
        dry: Int,
    ) {
        rainRun = rain.coerceAtLeast(0)
        snowRun = snow.coerceAtLeast(0)
        dryRun = dry.coerceAtLeast(0)
    }

    /**
     * Advances one turn of [atmospheric] and returns the ground that should now be in force,
     * which is [currentGround] on every turn that does not complete a run.
     *
     * Freezing beats miring beats drying, so a snow squall in the middle of a wet spell still
     * freezes the ground rather than being outvoted by the rain that came before it.
     */
    fun advance(
        currentGround: Int,
        atmospheric: Int,
    ): Int {
        val needed = turnsToChange()
        countRun(atmospheric)
        return when {
            snowRun >= needed && currentGround != GroundCondition.FROZEN.value ->
                settle(GroundCondition.FROZEN.value)

            rainRun >= needed && currentGround != GroundCondition.MUD.value ->
                settle(GroundCondition.MUD.value)

            dryRun >= needed -> thaw(currentGround)

            else -> currentGround
        }
    }

    private fun countRun(atmospheric: Int) {
        when (atmospheric) {
            WeatherCondition.SNOW.value -> {
                snowRun++
                rainRun = 0
                dryRun = 0
            }

            WeatherCondition.RAIN.value -> {
                rainRun++
                snowRun = 0
                dryRun = 0
            }

            // Fair and Overcast are both "without rain and without snow": overcast does not wet the
            // ground, which is the same line the rest of the weather rules take.
            else -> {
                dryRun++
                rainRun = 0
                snowRun = 0
            }
        }
    }

    /**
     * One step of drying per completed run, never two: frozen ground thaws into mud and only a
     * further dry run takes that mud away. Resetting the counter is what makes the second step
     * cost its own run rather than arriving on the same turn.
     */
    private fun thaw(currentGround: Int): Int =
        when (currentGround) {
            GroundCondition.FROZEN.value -> settle(GroundCondition.MUD.value)
            GroundCondition.MUD.value -> settle(GroundCondition.DRY.value)
            else -> currentGround
        }

    /** A completed run is spent: the next change needs a fresh one. */
    private fun settle(ground: Int): Int {
        reset()
        return ground
    }
}
