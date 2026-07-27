package org.osada.rules

import org.osada.hero.HeroCampaign
import org.osada.model.EfileConfig
import org.osada.model.GameUnit

/**
 * Attachments (DEFERRED.md §1.4, `docs/design/attachments.md`): per-efile purchasable per-unit
 * upgrades, gated by `attach_on` (default off — 14 of 22 campaigns never see this at all).
 *
 * A **pure query layer over `CoreFormation.attachmentIds`**, never a stat-mutation one:
 * `GameUnit.unitData()` returns the shared, global `EquipmentData` for that equipment id, so
 * writing a bonus into it would leak to every unit of that type on both sides for the rest of the
 * session (the trap the design doc calls out first). Every read site calls into this object at the
 * point of use, exactly like `Leaders.unitHasLeader` already does for leader traits.
 *
 * **Slot number is the mechanic, never the display name** — ATOMIC's slot 5 is "Ammunition",
 * LXF's/GCE's is "Support", but both are the same fixed maximum-ammunition attachment. Only core
 * formation units can have attachments (§3.2) — scenario/auxiliary units have no formation record.
 */
internal object Attachments {
    const val MAX_PER_UNIT = 2

    // Fixed slot mechanics (`OG_ABILITY_AUDIT.md` §4) -- the SLOT NUMBER decides which stat the
    // bonus applies to; only the magnitude, name, cost and penalty are per-efile data. Tier 1
    // (`docs/design/attachments.md` §4) is Recon/Air Defense/AntiTank/Support/Fuel
    // Pods/Fast Speed; the others are declared for `availableSlots`/eligibility but grant no bonus
    // yet (Tier 2/3, deliberately not built in this pass).
    const val SLOT_RECON = 1
    const val SLOT_AIR_DEFENSE = 2
    const val SLOT_BRIDGING = 3
    const val SLOT_ANTI_TANK = 4
    const val SLOT_SUPPORT_AMMO = 5
    const val SLOT_FUEL_PODS = 11
    const val SLOT_FAST_SPEED = 12

    // `malus-type` column: which stat an attachment's penalty reduces.
    private const val PENALTY_MOVEMENT = 1
    private const val PENALTY_INITIATIVE = 2
    private const val PENALTY_AMMO = 3

    private const val DEFAULT_MIN_COST = 30
    private const val DEFAULT_FACTOR_PCT = 25
    private const val PERCENT = 100

    /** This unit's currently-purchased attachment slots, or empty for a scenario-only unit (no
     *  formation record) or an efile with attachments off. */
    private fun purchasedSlots(unit: GameUnit): List<EfileConfig.AttachmentSlot> {
        val formation = HeroCampaign.formationFor(unit)
        val config = EfileConfig.attachments()
        return if (formation == null || config == null) {
            emptyList()
        } else {
            formation.attachmentIds.mapNotNull { id -> id.toIntOrNull()?.let(config.slots::get) }
        }
    }

    /** Whether [unit]'s formation has purchased [slotNumber]. */
    fun has(
        unit: GameUnit,
        slotNumber: Int,
    ): Boolean = HeroCampaign.formationFor(unit)?.attachmentIds?.contains(slotNumber.toString()) == true

    /** [slotNumber]'s fixed bonus amount if [unit]'s formation has purchased it, else 0. The
     *  bonus's TARGET stat (spot, hard attack, air attack, max ammo, max fuel, movement) is fixed
     *  by slot number (`OG_ABILITY_AUDIT.md` §4) and is the caller's job to apply at the right
     *  read site -- this function only answers "how much, if any". */
    fun bonus(
        unit: GameUnit,
        slotNumber: Int,
    ): Int {
        if (!has(unit, slotNumber)) return 0
        return EfileConfig
            .attachments()
            ?.slots
            ?.get(slotNumber)
            ?.bonus ?: 0
    }

    private fun penaltySum(
        unit: GameUnit,
        penaltyType: Int,
    ): Int = purchasedSlots(unit).filter { it.penaltyType == penaltyType }.sumOf { it.penalty }

    /** Summed movement-point penalty (already negative) across every purchased attachment whose
     *  malus-type is Movement. */
    fun movementPenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_MOVEMENT)

    /** Summed initiative penalty (already negative) across every purchased attachment whose
     *  malus-type is Initiative. */
    fun initiativePenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_INITIATIVE)

    /** Summed ammo penalty (already negative) across every purchased attachment whose malus-type
     *  is Ammo. */
    fun ammoPenalty(unit: GameUnit): Int = penaltySum(unit, PENALTY_AMMO)

    /** Slots [unit]'s formation could still purchase: enabled for the active efile, not disabled
     *  for this efile (LXF disables Bridging), and not already bought. Empty when attachments are
     *  off, the unit has no formation, or it is already at [MAX_PER_UNIT].
     *
     *  **Known simplification**: `equip.xeqa`'s per-equipment allow-list is not modelled -- its
     *  bitmask is indexed by the ORIGINAL per-efile equipment row order, and the runtime equipment
     *  database is the id-renumbered `eqp-united` merge, so a naive index lookup would silently
     *  attribute the wrong equipment's eligibility. Every enabled, non-disabled slot is offered to
     *  every unit until that id mapping is verified (see DEFERRED.md §1.4). */
    fun availableSlots(unit: GameUnit): List<Pair<Int, EfileConfig.AttachmentSlot>> {
        val config = EfileConfig.attachments()
        val formation = HeroCampaign.formationFor(unit)
        return if (config == null || formation == null || formation.attachmentIds.size >= MAX_PER_UNIT) {
            emptyList()
        } else {
            config.slots.entries
                .filter { (number, slot) -> !slot.disabled && number.toString() !in formation.attachmentIds }
                .map { (number, slot) -> number to slot }
                .sortedBy { it.first }
        }
    }

    /**
     * `cost = minCost + (unitCostPerStrengthPoint x unitBaseStrength x factorPct) / 100`
     * (`EFILE_GCE/equip.cfg`'s own comment, `DOC-ONLY`). `minCost`/`factorPct` come from the
     * slot's own columns when set (nonzero), else the efile's global `attach_mincost`/
     * `attach_factor`, else the documented defaults 30/25 -- `INFERENCE`: the importer cannot
     * distinguish an explicit 0 from an omitted column, so a genuinely free slot (the doc allows
     * one) would incorrectly fall through to a default; no shipped efile has been seen to rely on
     * that distinction.
     */
    fun cost(
        unit: GameUnit,
        slotNumber: Int,
    ): Int? {
        val config = EfileConfig.attachments()
        val slot = config?.slots?.get(slotNumber)
        return if (config == null || slot == null) {
            null
        } else {
            val minCost = firstNonZero(slot.minCost, config.minCostDefault, DEFAULT_MIN_COST)
            val factorPct = firstNonZero(slot.factCost, config.factorDefaultPct, DEFAULT_FACTOR_PCT)
            val perStrength = CostCalculator.calculateUnitCostPerStrength(unit)
            minCost + (perStrength * CombatResolver.FULL_STRENGTH.toInt() * factorPct) / PERCENT
        }
    }

    private fun firstNonZero(vararg values: Int): Int = values.firstOrNull { it != 0 } ?: values.last()
}
