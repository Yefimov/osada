package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.hero.HeroCampaign
import org.osada.rules.Attachments
import org.osada.rules.CostCalculator
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCosts
import org.osada.rules.calculateUnitSellCost
import org.osada.rules.calculateUpgradeCosts
import org.osada.scenario.getSideUnitsAvgExp
import org.osada.scoreGains
import org.osada.uiSettings

internal fun Player.usesStalinRegime(): Boolean = uiSettings.stalinRegime && type == PlayerType.HUMAN_LOCAL

/**
 * Adds prestige and returns the amount actually applied. Only positive income is multiplied;
 * purchases, penalties and refunds retain their normal values.
 */
fun Player.awardPrestige(baseAmount: Int): Int {
    val amount = effectivePrestigeIncome(baseAmount)
    prestige = (prestige + amount).coerceAtLeast(0)
    return amount
}

/** The amount shown by income previews and applied by [awardPrestige]. */
fun Player.effectivePrestigeIncome(baseAmount: Int): Int =
    if (baseAmount > 0 && usesStalinRegime()) {
        baseAmount * GameUnit.STALIN_REGIME_MULTIPLIER
    } else {
        baseAmount
    }

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
    unit.synchronizeStalinRegime(usesStalinRegime())
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

/** Buys attachment [slotNumber] for [unit]'s formation (DEFERRED.md §1.4), same check-then-mutate-
 *  then-deduct shape as [upgradeUnit]. Fails without spending prestige when the slot is unaffordable
 *  or [HeroCampaign.purchaseAttachment] itself refuses (no formation, already full, already owned). */
fun Player.purchaseAttachment(
    unit: GameUnit,
    slotNumber: Int,
): Boolean {
    val cost = Attachments.cost(unit, slotNumber) ?: -1
    val purchased = cost in 0..prestige && HeroCampaign.purchaseAttachment(unit, slotNumber)
    if (purchased) prestige -= cost
    return purchased
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
    val unitCost = CostCalculator.reinforceCostPerStrength(unit, overStrength)
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
