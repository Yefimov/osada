package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.WeatherCondition
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * What the weather does to combat and spotting, beyond grounding aircraft
 * (`AttackEligibility.airGroundedByWeather`, which stays where its many callers already look).
 *
 * Ported from Open General's manual, which is the only place these rules are written down that
 * OSADA can check. Panzer Marshal, the engine OSADA is otherwise a port of, reads `atmosferic` in
 * six places and every one is presentation — weather there is purely cosmetic
 * (`tools/og-import/DEFERRED.md` §7.45).
 *
 * > Bad weather affects air to ground or ground to air combat, as **attacking strength is halved**
 * > except with All weather combat leader of ability. **In rain or snow defense is modified by +3.**
 *
 * > **The spotting range of air units is halved in Overcast**, and the one of other units is halved
 * > in rain or snow.
 *
 * Two readings had to be chosen, both marked below as `INFERENCE`, because the manual is a
 * paragraph of prose rather than a table.
 *
 * All three rules, and the grounding in [AttackEligibility.airGroundedByWeather], are individually
 * switchable through the ruleset (`weather_*`, schema 2). Four switches rather than one: they are
 * four separate branches, and a player who turns one off should not silently lose the other three.
 * Every one of them ships ON.
 */
internal object WeatherCombatRules {
    /** OG: "In rain or snow defense is modified by +3." */
    private const val RAIN_SNOW_DEFENSE_BONUS = 3

    private fun atmospheric(): Int = GameHolder.instance?.scenario?.atmosferic ?: WeatherCondition.FAIR.value

    /** Anything worse than Fair. The same threshold `airGroundedByWeather` uses. */
    fun isBadWeather(): Boolean = atmospheric() != WeatherCondition.FAIR.value

    /** Precipitation specifically — the two conditions that also change the ground. */
    fun isPrecipitation(): Boolean =
        atmospheric() == WeatherCondition.RAIN.value || atmospheric() == WeatherCondition.SNOW.value

    /**
     * `INFERENCE`: the +3 is stated without naming a side, in a sentence of its own after the
     * air-ground one, so it is read as applying to whoever is defending in each exchange rather
     * than to one participant. Rain and snow only; Overcast does not carry it.
     */
    fun defenseBonus(): Int =
        if (isPrecipitation() && ActiveRuleset.flag(RuleKey.WEATHER_DEFENSE_BONUS, true)) {
            RAIN_SNOW_DEFENSE_BONUS
        } else {
            0
        }

    /**
     * Applies the rain/snow defence bonus to both halves of an exchange, because both sides defend
     * in their own half of it.
     *
     * Lives here rather than beside the other `AttackCalculation.apply*` steps so every weather rule
     * has one owner -- and so that object stays inside the project's function-count limit.
     */
    fun applyDefenseBonus(stats: AttackCalculation.CombatStats) {
        val bonus = defenseBonus()
        stats.attackerDefense += bonus
        stats.defenderDefense += bonus
    }

    /**
     * Strength points [shooter] actually brings to bear against [target].
     *
     * Halved only when the exchange crosses the air/ground boundary, which is exactly what OG's
     * "air to ground or ground to air combat" names: fighter-versus-fighter and tank-versus-tank are
     * untouched. In practice the common case is flak firing at aircraft, because an aircraft cannot
     * initiate an attack in bad weather at all unless it has the same leader that exempts it here.
     *
     * `INFERENCE`: the manual says "halved" without a rounding rule. Rounded UP, so a
     * one-strength unit still fires — a silent attack that can never do anything is indistinguishable
     * from a bug, and the supply math already takes the same "never round a real action down to
     * nothing" line.
     */
    fun firingStrength(
        shooter: GameUnit,
        target: GameUnit,
    ): Int {
        val strength = shooter.strength
        val crossesLayers = UnitPredicates.isAir(shooter) != UnitPredicates.isAir(target)
        val halved =
            ActiveRuleset.flag(RuleKey.WEATHER_HALVES_AIR_GROUND, true) &&
                isBadWeather() &&
                crossesLayers &&
                !isAllWeather(shooter)
        return if (halved) (strength + 1) / 2 else strength
    }

    /**
     * Spotting range for [unit], from its already-resolved [base] (equipment, recon leaders and the
     * recon attachment are folded in before this).
     *
     * `INFERENCE`: the manual names Overcast for air units and rain/snow for everyone else. Read as
     * "Overcast **or worse**" for aircraft, because the literal reading gives a pilot perfect
     * visibility in a blizzard and halves it in light cloud, which is not a rule anybody wrote on
     * purpose.
     *
     * Rounded up for the same reason as [firingStrength]: a unit that suddenly spots nothing at all,
     * not even the hex it stands on, reads as broken rather than as weather.
     */
    fun spotRange(
        unit: GameUnit,
        base: Int,
    ): Int {
        val weatherBlinds = if (UnitPredicates.isAir(unit)) isBadWeather() else isPrecipitation()
        val halved =
            ActiveRuleset.flag(RuleKey.WEATHER_HALVES_SPOTTING, true) &&
                weatherBlinds &&
                !isAllWeather(unit)
        return if (halved) (base + 1) / 2 else base
    }

    /**
     * `INFERENCE`: OG names the All Weather exception explicitly for grounding and for the halved
     * attack, and says nothing about spotting. It is applied to all three here because the trait's
     * own description — in OG's ability list and in ours — is "not affected by weather conditions",
     * and a trait that is affected by weather two thirds of the time does not say that.
     */
    private fun isAllWeather(unit: GameUnit): Boolean = Leaders.unitHasLeader(unit, LeaderType.ALL_WEATHER_COMBAT)
}
