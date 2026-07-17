package org.osada.rules

import org.osada.CURRENCY_MULTIPLIER
import org.osada.UPGRADE_PENALTY
import org.osada.model.Equipment
import org.osada.model.GameUnit

/**
 * Prestige cost calculations for buying, upgrading and selling units (including their
 * transports). Extracted from the former `GameRules` god-object; depends only on the
 * [Equipment] database and the currency/penalty constants. Faithful port of the
 * `osada.js` cost helpers.
 */
object CostCalculator {

    /** Combined buy cost of a unit and (optionally) its transport. Pass -1 to skip either. */
    fun calculateUnitCosts(eqid: Int, transportEqid: Int): Int {
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
    fun calculateUpgradeCosts(unit: GameUnit, newEqid: Int, transportEqid: Int): Int {
        if (unit == null) return 0
        val newUnitCost = if (newEqid > 0) {
            if (unit.eqid == newEqid) {
                calculateUnitCosts(unit.eqid, -1)
            } else {
                (calculateUnitCosts(newEqid, -1) * UPGRADE_PENALTY).toInt()
            }
        } else {
            calculateUnitCosts(unit.eqid, -1)
        }
        val newTransportCost = if (transportEqid > 0) {
            if (unit.transport?.eqid == transportEqid) {
                calculateUnitCosts(-1, transportEqid)
            } else {
                (calculateUnitCosts(-1, transportEqid) * UPGRADE_PENALTY).toInt()
            }
        } else {
            0
        }
        val oldCost = if (unit.transport != null) {
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
    fun calculateUnitCostPerStrength(unit: GameUnit): Int {
        if (unit == null) return -1
        return (unit.unitData().cost * CURRENCY_MULTIPLIER / 10)
    }

    /** Prestige refunded when disbanding/selling [unit] at its current strength. */
    fun calculateUnitSellCost(unit: GameUnit): Int {
        if (unit == null) return -1
        val transportEqid = unit.transport?.eqid ?: -1
        return (calculateUnitCosts(unit.eqid, transportEqid) / UPGRADE_PENALTY / 10 * unit.strength).toInt()
    }
}
