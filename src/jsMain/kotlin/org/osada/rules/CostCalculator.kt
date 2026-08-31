package org.osada.rules

import org.osada.CURRENCY_MULTIPLIER
import org.osada.LeaderType
import org.osada.OVERSTRENGTH_PENALTY
import org.osada.UPGRADE_PENALTY
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders

/**
 * Prestige cost calculations for buying, upgrading and selling units (including their
 * transports). Extracted from the former `GameRules` god-object; depends only on the
 * [Equipment] database and the currency/penalty constants. Faithful port of the
 * `osada.js` cost helpers.
 */
object CostCalculator {
    private const val FULL_STRENGTH = 10

    /**
     * The upgrade surcharge an `Influence` commander pays instead of [UPGRADE_PENALTY].
     *
     * OG's trait reads *"allows the unit to upgrade to better equipment at reduced prestige cost"*
     * and names no number, so the honest reading of "reduced" is the one that costs the player
     * nothing beyond the equipment itself: the 20% re-equipping surcharge is waived, and the new
     * kit is still paid for in full. Waiving more than the surcharge would make an upgrade cheaper
     * than the machine it buys, which no reading of the sentence supports.
     */
    private const val INFLUENCE_UPGRADE_PENALTY = 1.0

    /** Combined buy cost of a unit and (optionally) its transport. Pass -1 to skip either. */
    fun calculateUnitCosts(
        eqid: Int,
        transportEqid: Int,
    ): Int {
        var cost = 0
        if (eqid > 0) cost += (Equipment.getEquipment(eqid)?.cost ?: 0) * CURRENCY_MULTIPLIER
        if (transportEqid > 0) cost += (Equipment.getEquipment(transportEqid)?.cost ?: 0) * CURRENCY_MULTIPLIER
        return cost
    }

    /**
     * Net prestige cost of upgrading [unit] to [newEqid]/[transportEqid]. A change to a
     * different equipment id incurs the [UPGRADE_PENALTY]; keeping the same id is at face
     * value. The old unit's cost is credited back ([transportEqid] == -1 drops the
     * transport, so only the unit cost is credited).
     */
    fun calculateUpgradeCosts(
        unit: GameUnit,
        newEqid: Int,
        transportEqid: Int,
    ): Int {
        // OG's `Influence` leader waives the re-equipping surcharge -- advertised to the player and
        // backed by no rule anywhere until 2026-08-18 (`docs/og-fidelity-plan.md` A.4). It applies
        // only to the CHANGED-equipment branches, because the same-id branches never charged the
        // surcharge in the first place.
        val penalty =
            if (Leaders.unitHasLeader(unit, LeaderType.INFLUENCE)) INFLUENCE_UPGRADE_PENALTY else UPGRADE_PENALTY
        val newUnitCost =
            if (newEqid > 0) {
                if (unit.eqid == newEqid) {
                    calculateUnitCosts(unit.eqid, -1)
                } else {
                    (calculateUnitCosts(newEqid, -1) * penalty).toInt()
                }
            } else {
                calculateUnitCosts(unit.eqid, -1)
            }
        val newTransportCost =
            if (transportEqid > 0) {
                if (unit.transport?.eqid == transportEqid) {
                    calculateUnitCosts(-1, transportEqid)
                } else {
                    (calculateUnitCosts(-1, transportEqid) * penalty).toInt()
                }
            } else {
                0
            }
        val oldCost =
            if (unit.transport != null) {
                // When the upgrade drops the transport (transportEqid == -1), the old
                // cost is the unit alone — matches JS `-1 == m ? costs(eqid,-1) : ...`.
                if (transportEqid == -1) {
                    calculateUnitCosts(unit.eqid, -1)
                } else {
                    calculateUnitCosts(unit.eqid, unit.transport!!.eqid)
                }
            } else {
                calculateUnitCosts(unit.eqid, -1)
            }
        return (newUnitCost + newTransportCost - oldCost) shr 0
    }

    /** Prestige cost of a single strength point of [unit]'s equipment. */
    fun calculateUnitCostPerStrength(unit: GameUnit): Int = unit.unitData().cost * CURRENCY_MULTIPLIER / FULL_STRENGTH

    /**
     * Prestige actually charged per restored strength point, with the overstrength surcharge
     * applied. `Player.reinforceUnit` bills exactly this, and the action tooltip quotes exactly
     * this -- they must never be computed in two places.
     *
     * **OG's `elite_cost` is applied here** ([EliteReplacements]), which is what makes this the
     * price of the ELITE/normal replacement rather than the bare per-point cost. Overstrength is
     * bought with the same replacement points and is priced through the same key, so the two keep
     * the ratio `OVERSTRENGTH_PENALTY` gives them. `GreenReplacements` deliberately does NOT come
     * through here: `green_cost` and `elite_cost` are both percentages of the SAME standard cost,
     * so charging one on top of the other would compound them.
     */
    fun reinforceCostPerStrength(
        unit: GameUnit,
        overStrength: Boolean,
    ): Int {
        val penalty = if (overStrength) OVERSTRENGTH_PENALTY else 1.0
        val standard = kotlin.math.round(calculateUnitCostPerStrength(unit) * penalty).toInt()
        return EliteReplacements.priced(standard)
    }

    /** Prestige refunded when disbanding/selling [unit] at its current strength. */
    fun calculateUnitSellCost(unit: GameUnit): Int {
        val transportEqid = unit.transport?.eqid ?: -1
        return (calculateUnitCosts(unit.eqid, transportEqid) / UPGRADE_PENALTY / FULL_STRENGTH * unit.strength).toInt()
    }
}
