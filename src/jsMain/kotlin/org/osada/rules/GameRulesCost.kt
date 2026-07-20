package org.osada.rules

import org.osada.UnitClass
import org.osada.model.GameUnit

/**
 * Whether equipment of [uclass] may be purchased at all.
 *
 * This is a RULES question about the equipment being bought — deliberately independent of
 * `UIBuilder.eqClassButtons`, which is a UI tab list. PM 3.2.14 conflated the two
 * (`openpanzer.js:6524`): it refused a purchase whenever the *currently selected unit's* class
 * was absent from the 8-entry tab map, so merely having a fortification, transport or ship
 * selected disabled the Buy button for every piece of equipment in the catalogue. Buying never
 * depended on the selection (`EquipmentWindowButtons.onBuy` reads only `equnit`), so that gate
 * blocked nothing real and only produced a Buy button that vanished for no visible reason.
 *
 * Every value of [UnitClass] other than NONE is real equipment that appears in OG efile data,
 * so all of them are purchasable; availability date, campaign country and a purchase anchor are
 * the checks that actually constrain a purchase.
 */
fun GameRules.isPurchasableClass(uclass: Int): Boolean = uclass != UnitClass.NONE.value

// --- Costs (CostCalculator) ---

fun GameRules.calculateUnitCosts(
    eqid: Int,
    transportEqid: Int,
): Int = CostCalculator.calculateUnitCosts(eqid, transportEqid)

fun GameRules.calculateUpgradeCosts(
    unit: GameUnit,
    newEqid: Int,
    transportEqid: Int,
): Int = CostCalculator.calculateUpgradeCosts(unit, newEqid, transportEqid)

fun GameRules.calculateUnitCostPerStrength(unit: GameUnit): Int = CostCalculator.calculateUnitCostPerStrength(unit)

fun GameRules.calculateUnitSellCost(unit: GameUnit): Int = CostCalculator.calculateUnitSellCost(unit)
