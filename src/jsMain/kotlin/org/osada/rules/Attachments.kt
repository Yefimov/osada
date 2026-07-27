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
    // (`docs/design/attachments.md` §4) is Recon/Air Defense/AntiTank/Support/Fuel Pods/Fast Speed.
    // Bridging is named only because LXF disables it explicitly; it is Tier 2 and is NOT in
    // [IMPLEMENTED_SLOTS].
    const val SLOT_RECON = 1
    const val SLOT_AIR_DEFENSE = 2
    const val SLOT_BRIDGING = 3
    const val SLOT_ANTI_TANK = 4
    const val SLOT_SUPPORT_AMMO = 5
    const val SLOT_FUEL_PODS = 11
    const val SLOT_FAST_SPEED = 12

    /** The slots this engine actually applies -- Tier 1, the six pure stat deltas. Nothing else may
     *  be sold; see [availableSlots]. */
    val IMPLEMENTED_SLOTS =
        setOf(SLOT_RECON, SLOT_AIR_DEFENSE, SLOT_ANTI_TANK, SLOT_SUPPORT_AMMO, SLOT_FUEL_PODS, SLOT_FAST_SPEED)

    private const val DEFAULT_MIN_COST = 30
    private const val DEFAULT_FACTOR_PCT = 25
    private const val PERCENT = 100

    /** This unit's currently-purchased attachment slots as `slotNumber to slot`, or empty for a
     *  scenario-only unit (no formation record) or an efile with attachments off. The slot NUMBER
     *  is carried because the malus-type default table is keyed on it (`AttachmentPenalties`). */
    fun purchasedSlots(unit: GameUnit): List<Pair<Int, EfileConfig.AttachmentSlot>> {
        val formation = HeroCampaign.formationFor(unit)
        val config = EfileConfig.attachments()
        return if (formation == null || config == null) {
            emptyList()
        } else {
            formation.attachmentIds.mapNotNull { id ->
                id.toIntOrNull()?.let { number -> config.slots[number]?.let { number to it } }
            }
        }
    }

    /** Whether [unit]'s formation has purchased [slotNumber]. */
    fun has(
        unit: GameUnit,
        slotNumber: Int,
    ): Boolean = HeroCampaign.formationFor(unit)?.attachmentIds?.contains(slotNumber.toString()) == true

    /**
     * [slotNumber]'s bonus amount if [unit]'s formation has purchased it, else 0. The bonus's
     * TARGET stat (spot, hard attack, air attack, max ammo, max fuel, movement) is fixed by slot
     * number (`OG_ABILITY_AUDIT.md` §4) and is the caller's job to apply at the right read site --
     * this function only answers "how much, if any".
     *
     * **Slots 11 and 12 alone are read conditionally.** An efile that sets `attach_minfuel` /
     * `attach_minmove` spells Fuel Pods' / Fast Speed's column as a PERCENTAGE of the unit's base
     * stat, with that key as the minimum amount actually added; an efile that leaves the key at 0
     * spells it as a flat amount. Slots 1-10 are always flat.
     *
     *     delta = if (minKey > 0) max(minKey, baseStat * bonus / 100) else bonus
     *
     * Integer division throughout -- OG discards the fraction rather than rounding. The scaling is
     * folded in HERE, not offered as a separate call, so that no read site can forget it.
     *
     * `DOC-ONLY` (OG documentation, relayed 2026-07-27), but independently corroborated by our own
     * parsed `equip.cfg`: the two efiles carrying implausible flat magnitudes are exactly the two
     * that set the keys, and the one with credible flat magnitudes sets neither.
     *
     * | efile | `minMove` | `minFuel` | slot 12 | slot 11 |
     * |---|---|---|---|---|
     * | ATOMIC | 0 | 0 | 1 | 15 |
     * | LXF | 1 | 8 | 20 | 25 |
     * | BASEKORP | 2 | 20 | 30 | 50 |
     *
     * So LXF Fast Speed on a 6-MP unit is `max(1, 6*20/100)` = **+1**, not the +20 the flat reading
     * gave it (DEFERRED.md §1.17). The key floors THE BONUS; it is NOT a floor on the unit's
     * resulting stat, which is what DEFERRED.md §1.14/§1.16 were both about.
     */
    fun bonus(
        unit: GameUnit,
        slotNumber: Int,
    ): Int = if (has(unit, slotNumber)) previewBonus(unit, slotNumber) else 0

    /**
     * What [slotNumber] **would** give [unit], whether or not it is currently fitted — the figure a
     * purchase tile has to show, and the only difference from [bonus] is that this one does not
     * require ownership.
     *
     * Both go through the same scaling, so a preview can never disagree with what the unit gets
     * after buying. Keeping [bonus] ownership-gated is what makes it safe to call from the combat
     * and movement read sites without each of them re-checking [has].
     */
    fun previewBonus(
        unit: GameUnit,
        slotNumber: Int,
    ): Int {
        val config = EfileConfig.attachments()
        val raw = config?.slots?.get(slotNumber)?.bonus ?: 0
        return if (raw == 0) 0 else scaleConditionalSlot(unit, slotNumber, raw, config)
    }

    /** The slot-11/12 percentage rule described on [bonus]; every other slot returns [raw] as-is. */
    private fun scaleConditionalSlot(
        unit: GameUnit,
        slotNumber: Int,
        raw: Int,
        config: EfileConfig.AttachmentConfig?,
    ): Int {
        // Each base stat is read the way its own consumer reads it: movement from unitData() (which
        // resolves to the transport when mounted, exactly as GameUnit.getMovesLeft does), fuel from
        // unitData(true) (the unit's own capacity -- SupplyRules handles transport fuel separately).
        val (baseStat, minKey) =
            when (slotNumber) {
                SLOT_FAST_SPEED -> unit.unitData().movpoints to (config?.minMove ?: 0)
                SLOT_FUEL_PODS -> unit.unitData(true).fuel to (config?.minFuel ?: 0)
                else -> return raw
            }
        return if (minKey > 0) maxOf(minKey, baseStat * raw / PERCENT) else raw
    }

    /**
     * Slots [unit]'s formation could still purchase: implemented by this engine, enabled for the
     * active efile, not disabled by it (LXF disables Bridging), legal for this unit's class, and
     * not already bought. Empty when attachments are off, the unit has no formation, or it is
     * already at [MAX_PER_UNIT].
     *
     * **Only [IMPLEMENTED_SLOTS] are offered.** Every other slot is a Tier 2/3 mechanic this engine
     * does not have yet (`docs/design/attachments.md` §4), and offering one would sell the player a
     * no-op for real prestige -- strictly worse than not offering it. Deliberately silent about
     * why: the port's own build status is not game content (the rule DEFERRED.md §2.10 established).
     * Widen this set as each mechanic lands, and the slot appears on its own.
     *
     * **Known simplification**: `equip.xeqa`'s per-equipment allow-list is not modelled -- its
     * bitmask is indexed by the ORIGINAL per-efile equipment row order, and the runtime equipment
     * database is the id-renumbered `eqp-united` merge, so a naive index lookup would silently
     * attribute the wrong equipment's eligibility. OG's own pre-v6 fallback CLASS rules (quoted in
     * `docs/design/attachments.md` §3) are not applied either -- every one of them constrains a
     * Tier 2/3 slot, so none can fire while [IMPLEMENTED_SLOTS] holds only Tier 1. Implement them
     * together with the slot they gate, not before (see DEFERRED.md §1.4).
     */
    fun availableSlots(unit: GameUnit): List<Pair<Int, EfileConfig.AttachmentSlot>> {
        val config = EfileConfig.attachments()
        val formation = HeroCampaign.formationFor(unit)
        return if (config == null || formation == null || formation.attachmentIds.size >= MAX_PER_UNIT) {
            emptyList()
        } else {
            config.slots.entries
                .filter { (number, slot) ->
                    number in IMPLEMENTED_SLOTS &&
                        !slot.disabled &&
                        number.toString() !in formation.attachmentIds
                }.map { (number, slot) -> number to slot }
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
