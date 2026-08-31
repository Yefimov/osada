package org.osada.rules

import org.osada.model.EfileConfig
import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **green replacements** — the cheap intake that costs a formation its veterancy.
 *
 * `og-fidelity-plan.md` §Y.3 called this *"the largest unbuilt OG mechanic left"*, and it was never
 * blocked on evidence: `OPENTXT_SAMPLE/equip.cfg` documents all six keys in full.
 *
 * > *"green — Set 1 to enable green replacements."*
 * > *"green_cost — cost percent for green replacements, relative to standard cost (0..100).
 * >  **default 0 means 25%** (same than PG)."*
 * > *"green_exp — percent of green replacement coming with same experience than unit (0..100).
 * >  default 0 experience. Set 100 to avoid unit losing experience because green replacements."*
 * > *"green_defexp — If green reinforces come with default experience set in scenario (0..1)."*
 * > *"green_autorefit — If automatic refit should use greens, thus reducing experience (0..1).
 * >  default 0, autorefit use elite."*
 * > *"remove_leader — If Leaders must be removed if unit loses all bars when using green
 * >  replacements (0..1)."*
 *
 * ### Two actions, and which one OSADA already had
 *
 * OG offers **two** kinds of replacement: ELITE, at full price with no experience loss, and GREEN,
 * cheap and diluting. OSADA's existing Reinforce is the elite one by price and the green one by
 * effect — it charges full cost *and* dilutes, because `ReplacementExperience` was added as an
 * OSADA house rule in 2026-08-18. That rule's own KDoc predicted this: *"a separate costlier 'elite
 * replacement' action is the obvious answer and is not built here."*
 *
 * **This object does not touch the existing action.** It adds the cheap sibling OG has, priced and
 * diluted by the efile's own numbers. Whether the expensive one should stop diluting is a separate
 * question about an OSADA rule, and changing it here would re-tune every campaign at once.
 *
 * ### Gated twice, like `DepotSupply`
 *
 * [RuleKey.GREEN_REPLACEMENTS] is the player's switch and `green` in the efile is the content's.
 * **Four of the eighteen shipped `equip.cfg` files set `green = 1`**, with costs of 50/75/100%,
 * `green_exp` of 50 or 80, and two of them setting `remove_leader`. So unlike `supply_ex` this one
 * has real content behind it the moment the key goes on.
 *
 * This is an efile-level RULE rather than a per-record stat, which is why it does not fall foul of
 * §AB's ruling that *"united equipment file united units should move with same distance and same
 * experience, same point cost"* — the same formation costs the same everywhere; what differs is
 * whether a second, cheaper action exists at all.
 */
object GreenReplacements {
    /** *"default 0 means 25% (same than PG)"*. */
    private const val DEFAULT_COST_PERCENT = 25

    private const val PERCENT = 100

    /** Whether the cheap replacement action exists at all — the player's key AND the efile's. */
    fun enabled(): Boolean =
        ActiveRuleset.flag(RuleKey.GREEN_REPLACEMENTS, false) && EfileConfig.intKey("green", 0) != 0

    /** `green_cost`, with OG's own default for 0. */
    fun costPercent(): Int = EfileConfig.intKey("green_cost", 0).takeIf { it > 0 } ?: DEFAULT_COST_PERCENT

    /**
     * Prestige charged per restored strength point.
     *
     * Derived from the ordinary per-point cost so the two actions can never drift apart, and
     * floored at 1: a free replacement is not what *"cost percent"* means, and 25% of a cheap
     * formation rounds to nothing.
     *
     * **The base is the STANDARD cost, not the elite one.** `green_cost` and `elite_cost` are both
     * *"relative to standar cost"* in `equip.cfg`'s own words, so they are siblings rather than
     * nested: routing this through `CostCalculator.reinforceCostPerStrength` would charge
     * `green_cost` percent of `elite_cost` percent and make `eqp-gce`'s 100% green cost 133% of
     * base. [EliteReplacements] carries the pair.
     */
    fun costPerStrength(unit: GameUnit): Int =
        (CostCalculator.calculateUnitCostPerStrength(unit) * costPercent() / PERCENT).coerceAtLeast(1)

    /** `green_exp` — what fraction of the formation's own experience the intake arrives with. */
    private fun intakeExperiencePercent(): Int = EfileConfig.intKey("green_exp", 0).coerceIn(0, PERCENT)

    /** `green_defexp` — whether the intake instead arrives at the scenario's authored default. */
    private fun usesScenarioDefault(): Boolean = EfileConfig.intKey("green_defexp", 0) == 1

    /** `green_autorefit` — whether automatic between-scenario refit spends greens. */
    fun autorefitUsesGreens(): Boolean = enabled() && EfileConfig.intKey("green_autorefit", 0) == 1

    /** `remove_leader` — whether a commander leaves when the intake costs the formation its bars. */
    fun removesLeaderOnLastBar(): Boolean = EfileConfig.intKey("remove_leader", 0) == 1

    /** The experience the fresh intake itself arrives with. */
    private fun intakeExperience(unit: GameUnit): Int =
        if (usesScenarioDefault()) {
            unit.player?.defaultExperience ?: 0
        } else {
            unit.experience * intakeExperiencePercent() / PERCENT
        }

    /**
     * The formation's experience after [restored] green points join it.
     *
     * The strength-weighted average of the veterans and the intake — the same shape
     * [ReplacementExperience] uses for ordinary replacements, but with an intake that need not be
     * at zero. With `green_exp = 100` the two groups match and nothing is lost, which is exactly
     * what the key's own comment promises.
     */
    fun experienceAfter(
        unit: GameUnit,
        restored: Int,
    ): Int {
        val total = unit.strength + restored
        if (restored <= 0 || total <= 0) return unit.experience
        val intake = intakeExperience(unit)
        return (unit.experience * unit.strength + intake * restored) / total
    }

    /**
     * Apply green replacements to [unit], returning the points actually restored.
     *
     * Charges the player, dilutes, and removes the commander when `remove_leader` says the
     * formation has been diluted below its last experience bar. The leader clause is checked
     * against the bar count BEFORE and AFTER, because OG's wording is about *losing* all bars
     * rather than about having none.
     */
    fun apply(
        unit: GameUnit,
        available: Int,
    ): Int {
        val player = unit.player
        val perPoint = costPerStrength(unit)
        val affordable = ((player?.prestige ?: 0) / perPoint).coerceAtMost(available)
        if (player == null || affordable < 1) return 0
        val barsBefore = UnitExperience.bars(unit)
        player.prestige -= affordable * perPoint
        unit.experience = experienceAfter(unit, affordable)
        unit.strength += affordable
        unit.hasMoved = true
        unit.hasFired = true
        unit.hasResupplied = true
        if (removesLeaderOnLastBar() && barsBefore > 0 && UnitExperience.bars(unit) == 0) {
            unit.leader = -1
        }
        return affordable
    }
}
