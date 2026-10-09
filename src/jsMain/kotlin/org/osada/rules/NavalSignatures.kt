package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitType
import org.osada.model.GameUnit
import org.osada.model.Leaders

/** The stat halves of OG's naval leader signatures, split from [AttackCalculation] (2026-10-09). */
internal object NavalSignatures {
    /**
     * OG's two naval class attributes with a stat of their own (current leader table): Deep Sea
     * Hunter *"+1 naval attack"* and Expert Sea Wolf *"+1 naval defense"*, both read as the value
     * the ship uses against a SHIP opponent. Their other halves (evasion, ZOC) live in `Evade` and
     * in Superior Maneuver through `OgLeaderPools.signatureGrants`.
     */
    fun apply(
        stats: AttackCalculation.CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: AttackCalculation.CombatContext,
    ) {
        val defenderIsShip = context.defenderTarget == UnitType.SEA.value
        val attackerIsShip = context.attackerTarget == UnitType.SEA.value
        if (defenderIsShip && Leaders.unitHasLeader(attacker, LeaderType.DEEP_SEA_HUNTER)) stats.attackerAttack += 1
        if (attackerIsShip && Leaders.unitHasLeader(defender, LeaderType.DEEP_SEA_HUNTER)) stats.defenderAttack += 1
        if (defenderIsShip && Leaders.unitHasLeader(attacker, LeaderType.EXPERT_SEA_WOLF)) stats.attackerDefense += 1
        if (attackerIsShip && Leaders.unitHasLeader(defender, LeaderType.EXPERT_SEA_WOLF)) stats.defenderDefense += 1
    }
}
