package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.hero.HeroCampaign
import org.osada.rules.Attachments
import org.osada.rules.CostCalculator
import org.osada.rules.FrontsAndFactions
import org.osada.rules.GameRules
import org.osada.rules.PurchaseCap
import org.osada.rules.ScenarioPurchaseList
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

/**
 * Unit purchase/upgrade/resupply/reinforce economy for [Player], split out to keep its function
 * count in bounds.
 *
 * OG's `Can't Buy` is enforced HERE as well as in the window that offers the card, because this is
 * the only function a purchase must pass through — the UI's Buy button, a future hotkey and a
 * replayed multiplayer order all land on it. [acquireUnit] deliberately does NOT check it: the
 * prototype award and campaign carry-over acquire units without buying them, and `Can't Buy` says
 * nothing about either.
 *
 * The transport is checked too. A transport is acquired by attaching it at purchase time rather
 * than as a unit of its own, so a `Can't Buy` prime mover would otherwise be bought through the
 * back door of the unit hauling it.
 */
fun Player.buyUnit(
    eqid: Int,
    transportEqid: Int,
): Boolean {
    val offered =
        Equipment.isPurchasable(eqid) &&
            (transportEqid <= 0 || Equipment.isPurchasable(transportEqid)) &&
            // OG's Fronts/Factions, as the scenario's own `.buy4` list (`rules/ScenarioPurchaseList`).
            // Enforced here for the same reason `Can't Buy` is: this is the one function every
            // purchase passes through, and the transport is checked for the same back-door reason.
            ScenarioPurchaseList.allows(this, eqid) &&
            (transportEqid <= 0 || ScenarioPurchaseList.allows(this, transportEqid)) &&
            // OG's Fronts/Factions as the scenario's own MASKS -- the wider of the two sources
            // (208 deployed scenarios against 5 `.buy4` files), and a separate layer rather than a
            // replacement (`rules/FrontsAndFactions`). The transport is checked for the same
            // back-door reason the whitelist checks it.
            FrontsAndFactions.admitsForPurchase(this, eqid) &&
            (transportEqid <= 0 || FrontsAndFactions.admitsForPurchase(this, transportEqid)) &&
            // OG's pool classes: an air or naval transport comes from the per-player pool, not the
            // shop, wherever the scenario authored its pools at all.
            FrontsAndFactions.poolClassPurchasable(this, eqid) &&
            // OG's purchase cap. Checked here as well as by the equipment window and the AI, for
            // the same reason `Can't Buy` is: this is the one function every purchase passes
            // through, a replayed multiplayer order included (`rules/PurchaseCap`).
            PurchaseCap.allows(this)
    val cost = GameRules.calculateUnitCosts(eqid, transportEqid)
    val affordable = offered && cost <= prestige
    val acquired = affordable && acquireUnit(eqid, transportEqid)
    if (acquired) {
        prestige -= cost
        // Booked only on a purchase. `acquireUnit` is deliberately left alone: the prototype award
        // and campaign carry-over hand out formations without buying them, and the cap counts what
        // the player ADDS by purchase.
        PurchaseCap.recordPurchase(this)
    }
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
    applyPurchaseDefaults(unit)
    addCoreUnit(unit)
    return true
}

/**
 * OG's two per-player purchase defaults, wired 2026-08-29 (`docs/og-fidelity-plan.md` §AF).
 *
 * The scenario may state what a newly acquired formation arrives with — [Player.defaultExperience]
 * (`opt_default_xp`, 224 scenarios) and [Player.defaultStrength] (`opt_allow_default_str`, 149).
 * `uspanwar1` puts the second in its own briefing: *"New purchased units will have 5 as default
 * strength"*.
 *
 * **Both are additive.** 0 means the author did not set it, so an unauthored scenario keeps exactly
 * what it did before: OSADA's own rule of matching the OPPONENT's average experience outside a
 * campaign, and full strength. That rule is not OG's and is deliberately left in place where OG has
 * nothing to say — replacing it with a bare 0 would be reading silence as an instruction.
 *
 * Experience is deliberately NOT applied inside a campaign, which is the existing carve-out: a
 * campaign's core roster carries its own veterans forward and a scenario default must not overwrite
 * them.
 */
private fun Player.applyPurchaseDefaults(unit: GameUnit) {
    if (GameHolder.instance?.campaign == null) {
        unit.experience =
            if (defaultExperience > 0) {
                defaultExperience
            } else {
                GameHolder.instance?.scenario?.getSideUnitsAvgExp(1 - side) ?: 0
            }
    }
    if (defaultStrength > 0) unit.strength = defaultStrength
}

/**
 * Re-equips [unit], charging the difference.
 *
 * **The scenario's purchase whitelist gates upgrades as well as purchases**, unlike `Can't Buy`:
 * OpenSuite's own sentence for Fronts/Factions is *"available to the player to buy new units **or
 * upgrade existing ones**"*, so both go through `rules/ScenarioPurchaseList`. `Can't Buy` stays
 * purchase-only because its wording says only that (`docs/og-fidelity-plan.md` §Q.1).
 */
fun Player.upgradeUnit(
    unit: GameUnit,
    newEqid: Int,
    transportEqid: Int,
): Boolean {
    val listed =
        ScenarioPurchaseList.allows(this, newEqid) &&
            ScenarioPurchaseList.allows(this, transportEqid) &&
            // *"buy new units **or upgrade existing ones**"* -- the masks gate both halves of
            // OpenSuite's own sentence, exactly as the resolved `.buy4` list does. The pool-class
            // filter is deliberately NOT applied to an upgrade: it says where a transport comes
            // from, not what a formation may become.
            FrontsAndFactions.admitsForPurchase(this, newEqid) &&
            FrontsAndFactions.admitsForPurchase(this, transportEqid)
    val cost = GameRules.calculateUpgradeCosts(unit, newEqid, transportEqid)
    if (!listed || cost > prestige || !unit.upgrade(newEqid, transportEqid)) return false
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
