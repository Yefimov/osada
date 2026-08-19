package org.osada.rules

import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Combat penalties a formation carries because of the STATE IT IS IN rather than the exchange it is
 * fighting: it is out of ammunition, it is out of fuel, or it is standing in a minefield.
 *
 * Split from [AttackCalculation] to keep that object inside the project's function-per-object budget,
 * and because these three share one shape the `apply*` steps there do not: each is gated on a ruleset
 * key, each is a no-op with that key off, and none of them looks at the opponent at all.
 *
 * Both rules ship OFF. Neither changes any of the 502 shipped scenarios unless a profile asks.
 */
internal object UnitConditionPenalties {
    /**
     * OG 6.23's dry-unit penalties (`docs/og-fidelity-plan.md` B.8), behind `dry_unit_penalties`.
     *
     * OSADA already enforces both PROHIBITIONS — no ammo cannot attack ([AttackEligibility.canFire]),
     * no fuel cannot move (the move range collapses to zero) — and neither halving. These are the
     * halvings:
     *
     *  - *"[with no ammo it] cannot attack and defends with halved unsuppressed strength"* ->
     *    [dryDefense], on the defence stat. OSADA's damage formula reads the defender's DEFENCE, not
     *    its strength, so halving the defence is the same sentence expressed in this engine's terms;
     *    halving the strength value itself would do nothing to incoming fire.
     *  - *"[with no ammo] ... and halved initiative"*, *"[with no fuel it] cannot move and have its
     *    initiative halved"* -> this function, which is why a formation out of both loses initiative
     *    once, not twice.
     *
     * Rounded DOWN, unlike the weather halvings, which round up: an empty unit being made worse is
     * the point, and rounding a penalty in the victim's favour would blunt the rule the player
     * deliberately switched on.
     */
    fun dryInitiative(
        unit: GameUnit,
        initiative: Int,
    ): Int {
        if (!ActiveRuleset.flag(RuleKey.DRY_UNIT_PENALTIES, false)) return initiative
        val dry = unit.getAmmo() <= 0 || (UnitPredicates.unitUsesFuel(unit) && unit.getFuel() <= 0)
        return if (dry) initiative / 2 else initiative
    }

    /** The no-ammo half of [dryInitiative]'s rule, applied to a unit's defence stat. */
    private fun dryDefense(
        unit: GameUnit,
        defense: Int,
    ): Int {
        if (!ActiveRuleset.flag(RuleKey.DRY_UNIT_PENALTIES, false)) return defense
        return if (unit.getAmmo() <= 0) defense / 2 else defense
    }

    /**
     * Applies the no-ammo defence halving to both sides — either can be the one being shot at,
     * because the defender fires back in the same exchange.
     *
     * Runs after every bonus, so it halves the defence the formation would actually have had.
     */
    fun applyDryUnitPenalties(
        stats: AttackCalculation.CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
    ) {
        stats.attackerDefense = dryDefense(attacker, stats.attackerDefense)
        stats.defenderDefense = dryDefense(defender, stats.defenderDefense)
    }

    /**
     * OG 9.9's other half: *"While in a minefield a unit has 1 movement point and decreased
     * defense."* The movement half is capped in [MovementRules.getUnitMoveRange].
     *
     * Applied to whichever side stands in a field that threatens IT — a formation sitting in its own
     * minefield is not hampered by it, and both sides can be in one at once.
     */
    fun applyMinefieldPenalty(
        stats: AttackCalculation.CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
    ) {
        if (!Minefields.enabled()) return
        val penalty = Minefields.MINEFIELD_DEFENSE_PENALTY
        if (Minefields.threatens(attacker.getHex(), attacker.player?.side ?: -1)) {
            stats.attackerDefense = maxOf(0, stats.attackerDefense - penalty)
        }
        if (Minefields.threatens(defender.getHex(), defender.player?.side ?: -1)) {
            stats.defenderDefense = maxOf(0, stats.defenderDefense - penalty)
        }
    }
}
