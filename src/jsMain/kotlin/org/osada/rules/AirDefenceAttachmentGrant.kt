package org.osada.rules

import org.osada.model.ATTR_MASK_CANNOT_ATTACK_AIR
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.canInitiateAttackOnUnitType

/**
 * The Air Defense attachment (slot 2) as a way to ENGAGE aircraft, not only a bonus to an existing
 * air attack (owner's decision, 2026-10-08).
 *
 * OG's slot 2 is *"Increase AA/AD"*, and OSADA had applied it only to formations that could already
 * fire at aircraft. A formation whose record cannot -- an ordinary infantry or tank battalion --
 * now treats the attachment the way OG treats `CAN Air Atk`: it may order an attack on an aircraft,
 * **only if it has not moved this turn** (`AttackEligibility.blockedByMovedAirGrant`), and it fires
 * and returns fire with its record's air attack plus the attachment bonus ([effectiveAirAttack]).
 *
 * Deliberately NOT extended to the engine's reactions: air-defence support for neighbours and the
 * interception of moving aircraft stay with real FlaK / Air Defence formations
 * (`AirDefenceSupport`, `AAInterception`), which read their own class and range rules. A record
 * that OG explicitly forbids from attacking aircraft (`Cannot Attack Air`) keeps that prohibition.
 */
internal object AirDefenceAttachmentGrant {
    /** The air attack [unit] fires with: its record's, plus a fitted Air Defense bonus. */
    fun effectiveAirAttack(unit: GameUnit): Int =
        unit.unitData().airatk + Attachments.bonus(unit, Attachments.SLOT_AIR_DEFENSE)

    /** OG's target-type matrix for [attacker] against [defender], widened by [grants]. */
    fun typeMatrixAllows(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean =
        Equipment.canInitiateAttackOnUnitType(attacker.getEqid(), defender.getEqid()) ||
            grants(attacker, defender)

    /** Whether the attachment is what lets [attacker] order a shot at [defender] -- an aircraft its
     *  own record could not otherwise target. */
    fun grants(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val refusedByRecord = !Equipment.canInitiateAttackOnUnitType(attacker.getEqid(), defender.getEqid())
        val forbidden = attacker.unitData().attr and ATTR_MASK_CANNOT_ATTACK_AIR != 0
        return UnitPredicates.isAir(defender) &&
            refusedByRecord &&
            !forbidden &&
            Attachments.has(attacker, Attachments.SLOT_AIR_DEFENSE)
    }
}
