package org.osada.rules

import org.osada.model.GameUnit

/**
 * Attachment penalties (the `malus-type` column). Extensions on [Attachments] rather than members
 * of it purely to keep that object inside the project's function-count budget -- the same treatment
 * `GameRulesFuelAmmo.kt` gives `GameRules`. Call them as `Attachments.movementPenalty(unit)`.
 *
 * **The malus-type column is OPTIONAL, and omitted does NOT mean "no penalty".**
 * `EFILE_NOKORP/equip.cfg` -- the only copy shipping the explanatory comments -- states it directly:
 *
 * ```
 * * A new optional parameter can be defined, after fact-cost, to customize the malus-type
 * * (penalty type) for each attachment.
 * * The value of this new parameter can be: 0=Default/Omitted (current penalty),
 * * 1=Movement penalty, 2=Inititative penalty, 3=ammo penalty, 4=fuel penalty
 * ```
 *
 * So `0` means "use this slot's built-in default", and the importer cannot distinguish an explicit
 * 0 from an absent column anyway. [DEFAULT_MALUS_TYPE] is that built-in table.
 *
 * **This is not guesswork.** Three independent copies in the install agree slot-for-slot:
 * `EFILE_NOKORP` writes all twelve values explicitly, and `EFILE_GCE` / `EFILE_ATOMIC` name the same
 * stat per slot in their trailing comments (`* Spot,Mov`, `* AA/AD, Ini`, ...).
 *
 * **The bug this fixes:** `EFILE_GCE` sets `penalty = -1` on all eight of its slots and omits the
 * column, so every one of those penalties was silently dropped -- a shipped campaign whose
 * attachments were all upside. `EFILE_LXF` writes the column explicitly (and overrides slot 7 to
 * ammo), `ATOMIC`/`BASEKORP` have `penalty = 0` throughout, so those three are unaffected either
 * way. See DEFERRED.md §1.19.
 */
internal const val PENALTY_MOVEMENT = 1
internal const val PENALTY_INITIATIVE = 2
internal const val PENALTY_AMMO = 3
internal const val PENALTY_FUEL = 4

/** Built-in malus-type per slot, used whenever an efile omits the column (or writes 0). */
private val DEFAULT_MALUS_TYPE =
    mapOf(
        Attachments.SLOT_RECON to PENALTY_MOVEMENT,
        Attachments.SLOT_AIR_DEFENSE to PENALTY_INITIATIVE,
        Attachments.SLOT_BRIDGING to PENALTY_MOVEMENT,
        Attachments.SLOT_ANTI_TANK to PENALTY_MOVEMENT,
        Attachments.SLOT_SUPPORT_AMMO to PENALTY_MOVEMENT,
        6 to PENALTY_INITIATIVE, // Forward Observer
        7 to PENALTY_MOVEMENT, // Special Munition  (LXF overrides this to ammo)
        8 to PENALTY_INITIATIVE, // Fast Entrench
        9 to PENALTY_AMMO, // Bunker Buster
        10 to PENALTY_INITIATIVE, // Fast Builder
        Attachments.SLOT_FUEL_PODS to PENALTY_MOVEMENT,
        Attachments.SLOT_FAST_SPEED to PENALTY_INITIATIVE,
    )

/** Which stat [slotNumber]'s penalty actually reduces: the efile's own column when it states one,
 *  else this slot's documented default. */
internal fun effectiveMalusType(
    slotNumber: Int,
    declared: Int,
): Int = if (declared != 0) declared else DEFAULT_MALUS_TYPE[slotNumber] ?: 0

private fun Attachments.penaltySum(
    unit: GameUnit,
    penaltyType: Int,
): Int =
    fittedSlots(unit)
        .filter { (number, slot) -> effectiveMalusType(number, slot.penaltyType) == penaltyType }
        .sumOf { (_, slot) -> slot.penalty }

/** Summed movement-point penalty (already negative) across every purchased attachment whose
 *  malus-type is Movement. */
internal fun Attachments.movementPenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_MOVEMENT)

/** Summed initiative penalty (already negative) across every purchased attachment whose malus-type
 *  is Initiative. */
internal fun Attachments.initiativePenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_INITIATIVE)

/** Summed ammo penalty (already negative) across every purchased attachment whose malus-type is
 *  Ammo. */
internal fun Attachments.ammoPenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_AMMO)

/** Summed max-fuel penalty (already negative) across every purchased attachment whose malus-type is
 *  Fuel. No efile we ship uses type 4, but OG defines it and an efile could. */
internal fun Attachments.fuelPenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_FUEL)
