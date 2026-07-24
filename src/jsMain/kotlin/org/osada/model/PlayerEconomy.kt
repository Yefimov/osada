package org.osada.model

import org.osada.GameHolder
import org.osada.OVERSTRENGTH_PENALTY
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCostPerStrength
import org.osada.rules.calculateUnitCosts
import org.osada.rules.calculateUnitSellCost
import org.osada.rules.calculateUpgradeCosts
import org.osada.scenario.getSideUnitsAvgExp
import org.osada.scoreGains

/** Unit purchase/upgrade/resupply/reinforce economy for [Player], split out to keep its function count in bounds. */
fun Player.buyUnit(
    eqid: Int,
    transportEqid: Int,
): Boolean {
    val cost = GameRules.calculateUnitCosts(eqid, transportEqid)
    val affordable = cost <= prestige
    val acquired = affordable && acquireUnit(eqid, transportEqid)
    if (acquired) prestige -= cost
    return acquired
}

fun Player.acquireUnit(
    eqid: Int,
    transportEqid: Int,
): Boolean {
    val unit = GameUnit(eqid)
    if (transportEqid > 0) {
        unit.setTransport(transportEqid)
    }
    unit.owner = id
    unit.flag = country + 1
    unit.player = this
    if (GameHolder.instance?.campaign == null) {
        unit.experience = GameHolder.instance?.scenario?.getSideUnitsAvgExp(1 - side) ?: 0
    }
    addCoreUnit(unit)
    return true
}

fun Player.upgradeUnit(
    unit: GameUnit,
    newEqid: Int,
    transportEqid: Int,
): Boolean {
    val cost = GameRules.calculateUpgradeCosts(unit, newEqid, transportEqid)
    if (cost > prestige || !unit.upgrade(newEqid, transportEqid)) return false
    prestige -= cost
    return true
}

fun Player.sellUnit(unit: GameUnit): Boolean {
    val cost = GameRules.calculateUnitSellCost(unit)
    prestige += cost
    return true
}

fun Player.resupplyUnit(
    unit: GameUnit,
    supply: Supply,
) {
    updateScore(scoreGains["resupply"] ?: 0)
    unit.resupply(supply)
}

fun Player.reinforceUnit(
    unit: GameUnit,
    strength: Int,
    overStrength: Boolean,
): Int {
    val penalty = if (overStrength) OVERSTRENGTH_PENALTY else 1.0
    val costPerStrength = GameRules.calculateUnitCostPerStrength(unit)
    val unitCost = kotlin.math.round(costPerStrength * penalty).toInt()
    val maxAffordable = prestige / unitCost
    val toReinforce = if (maxAffordable < 1) 0 else kotlin.math.min(maxAffordable, strength)
    // Nothing to add (e.g. unit ineligible for overstrength): bail out WITHOUT calling
    // unit.reinforce(), which would mark the unit hasMoved/hasFired and wrongly end its turn.
    if (toReinforce >= 1) {
        prestige -= toReinforce * unitCost
        updateScore(scoreGains["reinforce"] ?: 0, strength)
        unit.reinforce(toReinforce, overStrength)
    }
    return toReinforce
}
