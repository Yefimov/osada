package org.osada.rules

import org.osada.UnitType
import org.osada.model.GameUnit

/**
 * Attachment combat bonuses (DEFERRED.md §1.4 Tier 1): AntiTank's +hard-attack and Air Defense's
 * +air-attack, applied to whichever side's attack figure [AttackCalculation.resolveCrossIndexedStats]
 * actually sourced from that stat. Symmetric by construction -- an attacker fighting a hard target
 * uses its own `hardatk` (so its AntiTank bonus applies to `attackerAttack`), and a defender
 * counter-firing with `hardatk` against a hard-target attacker gets the same bonus on
 * `defenderAttack`; same shape for Air Defense against an air target. Queried per unit, never
 * written into the shared `EquipmentData` (the §3.1 trap `Attachments` exists to avoid).
 *
 * Split into its own file (an extension function, not a member) purely to keep [AttackCalculation]
 * within the project's function-count budget -- the same treatment `MoveExecutorHelpers.kt` etc.
 * already give other objects/classes at their limit.
 */
internal fun AttackCalculation.applyAttachmentBonuses(
    stats: AttackCalculation.CombatStats,
    attacker: GameUnit,
    defender: GameUnit,
    context: AttackCalculation.CombatContext,
) {
    if (context.defenderTarget == UnitType.HARD.value) {
        stats.attackerAttack += Attachments.bonus(attacker, Attachments.SLOT_ANTI_TANK)
    }
    if (context.attackerTarget == UnitType.HARD.value) {
        stats.defenderAttack += Attachments.bonus(defender, Attachments.SLOT_ANTI_TANK)
    }
    if (context.defenderTarget == UnitType.AIR.value) {
        stats.attackerAttack += Attachments.bonus(attacker, Attachments.SLOT_AIR_DEFENSE)
    }
    if (context.attackerTarget == UnitType.AIR.value) {
        stats.defenderAttack += Attachments.bonus(defender, Attachments.SLOT_AIR_DEFENSE)
    }
}
