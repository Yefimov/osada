package org.osada.rules

import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.GameUnit

/**
 * Attachment combat bonuses (`docs/design/attachments.md` Tier 1): AntiTank's +hard-attack and Air Defense's
 * +air-attack/+air-defence, applied to whichever side's attack figure [AttackCalculation.resolveCrossIndexedStats]
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
    units: List<GameUnit> = emptyList(),
) {
    stats.attackerAttack += forwardObserverBonus(attacker, defender, context.defenderTarget, units)
    stats.defenderAttack += forwardObserverBonus(defender, attacker, context.attackerTarget, units)
    if (context.defenderTarget == UnitType.HARD.value) {
        stats.attackerAttack += Attachments.bonus(attacker, Attachments.SLOT_ANTI_TANK)
    }
    if (context.attackerTarget == UnitType.HARD.value) {
        stats.defenderAttack += Attachments.bonus(defender, Attachments.SLOT_ANTI_TANK)
    }
    // Air Defense is "Increase AA/AD" on the author's attachment page: air attack AND air defence.
    // Both halves follow the cross-indexing above -- against an air opponent the side fires with
    // `airatk` and defends with `airdef`, so the bonus lands on both. Until 2026-10-08 only the
    // attack half existed, and a unit being bombed got nothing from it.
    if (context.defenderTarget == UnitType.AIR.value) {
        val bonus = Attachments.bonus(attacker, Attachments.SLOT_AIR_DEFENSE)
        stats.attackerAttack += bonus
        stats.attackerDefense += bonus
    }
    if (context.attackerTarget == UnitType.AIR.value) {
        val bonus = Attachments.bonus(defender, Attachments.SLOT_AIR_DEFENSE)
        stats.defenderAttack += bonus
        stats.defenderDefense += bonus
    }
}

/**
 * Forward Observer (slot 6, `docs/design/attachments.md` Tier 3). OG's combat procedure
 * (`luis-guzman.com/OpenGen_Combat.html`): *"If A is Arty-class, AD-class or naval-unit, engine
 * scans adjacent hexes to P and for each A's friend-unit having ForwardObserver attachment, then:
 * A.EffAtk += atcFO_AttachmentBonus"*.
 *
 * So the bonus belongs to the OBSERVER but is paid to the unit FIRING: every friendly observer next
 * to [target] adds its own bonus, and several observers stack. [firing] is whichever side is
 * shooting -- OG's "Active" -- so a gun returning fire is observed for the same way. The observer
 * itself does not count for its own shot. Limited to soft and hard targets: every efile describes
 * the slot as *"SA/HA to adjacent targets"*, and those are the two attack values it names.
 *
 * [units] is the map's unit list, the same one Combat Support reads; a preview without it simply
 * shows no observer bonus.
 */
private fun forwardObserverBonus(
    firing: GameUnit,
    target: GameUnit,
    targetType: Int,
    units: List<GameUnit>,
): Int {
    val uclass = firing.unitData(true).uclass
    val observed =
        uclass == UnitClass.ARTILLERY.value ||
            uclass == UnitClass.AIR_DEFENCE.value ||
            UnitPredicates.isSea(firing)
    val groundTarget = targetType == UnitType.SOFT.value || targetType == UnitType.HARD.value
    val targetPos = target.getPos()
    val side = firing.player?.side
    val applies = observed && groundTarget
    if (!applies || targetPos == null || side == null) return 0
    return units.sumOf { observer ->
        val pos = observer.getPos()
        val eligible =
            observer !== firing &&
                !observer.destroyed &&
                pos != null &&
                observer.player?.side == side &&
                HexGeometry.distance(pos.row, pos.col, targetPos.row, targetPos.col) == 1
        if (eligible) Attachments.bonus(observer, Attachments.SLOT_FORWARD_OBSERVER) else 0
    }
}
