package org.osada.rules

import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * OG's `critical_hit` — a naval shot that sinks its target outright.
 *
 * > *"0 to disable, 1.. factor N in formula. Chance for critical hit.*
 * > *C(firing) = ( NA(Firing) × (1+bars(Firing)) × SP(Firing) × N − D(Fired) × (1+Bars(Fired)) ×
 * > SP(Fired) × N ) / 30*
 * > *NA(Firing) is naval attack of unit firing. SP(Firing/Fired) is unit strength at start of
 * > combat. D(Fired) is GD or AD depending unit firing is Air/Gnd. Submarines always add 10% when
 * > firing (either attacking or defending). If C(firing) > 75 then C(firing)=75. If C(Firing) <
 * > Dice(1,100) then critical hit, fired unit is sunk"*
 *
 * Both commented `equip.cfg` copies carry this text identically, and **`eqp-lxf` sets
 * `critical_hit = 2`** — the efile behind more shipped campaigns than any other that has an
 * `equip.cfg`. The formula has never run. `docs/og-fidelity-plan.md` §AA.6 records how it was found:
 * not by working through the manual, but by finally reading a config key nobody had looked up.
 *
 * ### The one correction made to the quoted text
 *
 * *"If C(Firing) < Dice(1,100) then critical hit"* is **read as inverted**, and this is a named
 * `INFERENCE`. As literally written a LOWER chance would make a critical MORE likely, and the
 * clamp immediately above it — *"if C > 75 then C = 75"* — only makes sense if `C` is a percentage
 * that a roll must come in UNDER. Taken literally the rule would sink ships most often when the
 * firing unit is weakest, which no reading of "critical hit" supports. So: roll d100, critical when
 * the roll is below `C`.
 *
 * ### Why it is behind a ruleset key
 *
 * It sinks a ship regardless of strength, and no shipped campaign was balanced with it running.
 * [RuleKey.NAVAL_CRITICAL_HITS] is **off by default and on in the OG Fidelity profile**, where the
 * efile's own `N` then applies. That makes this the one key in the enum whose OFF diverges from a
 * shipped efile's explicit instruction — taken deliberately, and recorded on the key itself.
 *
 * ### Where it is applied, and why that is not the forecast
 *
 * `CombatApplication.applyCombatDamage`, which runs only on the COMMITTED shot — the same place
 * `SingleFireSup.` spends its battery, and for the same reason. A random outcome must never reach
 * the attack forecast, which promises the player what will actually happen. The roll comes from
 * [GameRandomSource], the synchronised stream both multiplayer peers share (§H.6), so the two
 * cannot disagree about whether a ship went down.
 */
object CriticalHit {
    /** OG's own divisor. */
    private const val DIVISOR = 30

    /** *"If C(firing) > 75 then C(firing)=75"*. */
    private const val MAX_PERCENT = 75

    /** *"Submarines always add 10% when firing"*. */
    private const val SUBMARINE_BONUS = 10

    private const val FULL_ROLL = 100

    /** The efile's `N`, or 0 when the rule is off for this ruleset or unset by the efile. */
    private fun factor(): Int =
        if (!ActiveRuleset.flag(RuleKey.NAVAL_CRITICAL_HITS, false)) 0 else EfileConfig.intKey("critical_hit", 0)

    /**
     * The chance, as a percentage, that [firing] sinks [fired] outright — 0 when the rule cannot
     * apply.
     *
     * **Naval targets only.** `NA(Firing)` is the firing unit's NAVAL attack and the outcome is
     * *"sunk"*, so a shot with no naval attack, or one at something that does not float, can never
     * produce one.
     */
    fun percentFor(
        firing: GameUnit,
        fired: GameUnit,
    ): Int {
        val n = factor()
        val navalAttack = firing.unitData().navalatk
        if (n <= 0 || navalAttack <= 0 || !UnitPredicates.isSea(fired)) return 0
        val defenderData = fired.unitData()
        // "D(Fired) is GD or AD depending unit firing is Air/Gnd."
        val defence =
            if (UnitPredicates.isAir(firing)) defenderData.airdef else defenderData.grounddef
        val offence = navalAttack * (1 + UnitExperience.bars(firing)) * firing.strength * n
        val resistance = defence * (1 + UnitExperience.bars(fired)) * fired.strength * n
        val submarineBonus = if (isSubmarine(firing)) SUBMARINE_BONUS else 0
        return ((offence - resistance) / DIVISOR + submarineBonus).coerceIn(0, MAX_PERCENT)
    }

    /**
     * Rolls for a critical hit. True means [fired] is sunk outright.
     *
     * Consumes a number from the shared stream **only when the rule could actually fire**, so
     * turning the key off cannot change any other random outcome in the game.
     */
    fun sinks(
        firing: GameUnit,
        fired: GameUnit,
    ): Boolean {
        val percent = percentFor(firing, fired)
        return percent > 0 && GameRandomSource.nextInt(FULL_ROLL) < percent
    }

    /** Whether [unit] is a submarine, for the 10% the formula grants one that is firing. */
    private fun isSubmarine(unit: GameUnit): Boolean = unit.unitData().uclass == UnitClass.SUBMARINE.value
}
