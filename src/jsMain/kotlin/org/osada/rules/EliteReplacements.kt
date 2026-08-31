package org.osada.rules

import org.osada.model.EfileConfig

/**
 * Open General's **elite replacements** — `elite_cost`, and the reason OSADA's ordinary Reinforce
 * had two jobs at once.
 *
 * > *"elite_cost — cost percent for elite (**normal**) units, relative to standar cost (0..65535).
 * >  default 0 means 100% (same than PG2)."* — `OPENTXT_SAMPLE/equip.cfg`
 *
 * **"Elite" is OG's word for the NORMAL replacement**, not for a premium extra. OG offers exactly
 * two ways to restore strength and this key prices the first of them:
 *
 * | | price | what the intake knows |
 * |---|---|---|
 * | elite / normal | `elite_cost`% of standard (default 100) | the formation's own experience — nothing lost |
 * | green ([GreenReplacements]) | `green_cost`% (default 25) | `green_exp`% of it, default nothing |
 *
 * ### The defect this closes
 *
 * `elite_cost` was decoded in §AA and read by nothing, so OSADA charged 100% where an efile said
 * otherwise. `eqp-gce` — **the one shipped efile that authors `green = 1`** — sets `elite_cost=133`
 * and `green_cost=100`, so with the key unread the two actions cost exactly the same while the
 * green one diluted LESS (`green_exp=50` against an ordinary intake at zero). The cheap action
 * strictly dominated the expensive one and the expensive one had no purpose. §AK.4 had recorded
 * the mismatch as *"elite by price and green by effect"* without noticing that green replacements
 * shipping beside it turned a house rule into a dominated choice.
 *
 * ### The ruling, and what it does NOT change (owner, 2026-08-31)
 *
 * > *"Пускай пополнения будут как обычно, просто сам факт пополнения сбивает экспу юнита. Так вроде
 * > в Armageddon сделано. Ну это показывает, что юнит набрал новичков."*
 *
 * **OSADA's ordinary replacement goes on diluting** — [ReplacementExperience] stays exactly as it
 * was, and so does every campaign that runs on it. That is 9 of the 10 shipped efiles: none of them
 * authors `green`, none of them offers a second action, and for them the only replacement there is
 * costs its `elite_cost`% and brings rookies who know nothing.
 *
 * **The one efile that authors a green action is the one that gets OG's split**, because there the
 * house rule has somewhere to go: the cheap action is the one that costs veterancy, so the
 * expensive one must be the one that does not. [preservesExperience] is that single condition, and
 * it is why `ReplacementExperience.dilutes()` yields to it. Without the yield the player is offered
 * a discount with no downside, which is not a choice.
 */
internal object EliteReplacements {
    /** *"default 0 means 100% (same than PG2)"*. */
    private const val DEFAULT_COST_PERCENT = 100

    private const val PERCENT = 100

    /** `elite_cost`, with OG's own default for 0. */
    fun costPercent(): Int = EfileConfig.intKey("elite_cost", 0).takeIf { it > 0 } ?: DEFAULT_COST_PERCENT

    /**
     * [standardCost] charged at the elite percentage, floored at 1 wherever there was anything to
     * pay — a replacement point is never free, and the flooring matches [GreenReplacements].
     */
    fun priced(standardCost: Int): Int =
        if (standardCost <= 0) standardCost else (standardCost * costPercent() / PERCENT).coerceAtLeast(1)

    /**
     * Whether the ordinary replacement preserves the formation's experience.
     *
     * True exactly when the efile authors the green alternative. See the KDoc: this is the whole of
     * the 2026-08-31 ruling, and the reason it is one condition rather than a new ruleset key is
     * that the two actions only make sense as a pair.
     */
    fun preservesExperience(): Boolean = GreenReplacements.enabled()
}
