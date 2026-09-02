package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.getCountryName
import org.osada.model.getUnitById
import org.osada.model.hasPurchaseAnchor
import org.osada.model.isAvailableIn
import org.osada.model.isPurchasable
import org.osada.model.isPurchasableGroundTransport
import org.osada.rules.FrontsAndFactions
import org.osada.rules.GameRules
import org.osada.rules.PurchaseCap
import org.osada.rules.ScenarioPurchaseList
import org.osada.rules.calculateUnitCosts
import org.osada.rules.calculateUnitSellCost
import org.osada.rules.calculateUpgradeCosts
import org.osada.rules.isPurchasableClass

/**
 * [EquipmentWindowController]'s buy/upgrade/sell cost badge computation. Split out purely to
 * keep [EquipmentWindowController] within the project's function-count/class-size limits -- not
 * expected to be called from elsewhere.
 */
internal class EquipmentCostsCalculator(
    private val ui: UI,
) {
    fun update() {
        val scenario = ui.game.scenario
        val map = scenario?.map
        if (scenario == null || map == null) return
        val currentPlayer = map.currentPlayer
        val eqUserSel = byId("eqUserSel")?.asDynamic()
        if (currentPlayer == null || eqUserSel == null) return

        val userUnitId = eqUserSel.userunit as? Int ?: -1
        val deployUnitId = eqUserSel.deployunit as? Int ?: -1
        val eqUnitId = eqUserSel.equnit as? Int ?: -1
        val eqTransportId = eqUserSel.eqtransport as? Int ?: -1
        val year = scenario.date.getFullYear()
        val month = scenario.date.getMonth() + 1

        val selectedUnit =
            if (deployUnitId == -1) {
                map.getUnitById(userUnitId)
            } else {
                currentPlayer.getCoreUnitList().getOrNull(deployUnitId)
            }

        val (upgradeCost, sellCost) = resolveUpgradeAndSellCost(selectedUnit, eqUnitId, eqTransportId)
        val buyCost = resolveBuyCost(eqUnitId, eqTransportId, year, month)
        UIBuilder.showEquipmentCosts(
            currentPlayer.prestige,
            buyCost,
            upgradeCost,
            sellCost,
            resolveBuyBlockedReason(eqUnitId),
        )
    }

    /** Why the Buy button is absent for [eqUnitId], or null when the refusal is not one we can
     *  usefully explain. The catalogue lists the whole side (see [EquipmentCatalogStrip]), so a
     *  campaign player routinely selects equipment they may not purchase — the Buy button then just
     *  vanished, which read as a bug. Name the rule instead. */
    private fun resolveBuyBlockedReason(eqUnitId: Int): String? =
        when {
            eqUnitId <= 0 -> null
            !hasPurchaseAnchor() -> I18n.t("equipment.buy_blocked.no_anchor")
            // OG's `Can't Buy` (`attr` bit 7), wired 2026-08-27. The card is deliberately still
            // listed -- the catalogue shows the whole side and explains refusals here rather than
            // hiding cards, and this record may still be a legal UPGRADE target.
            !Equipment.isPurchasable(eqUnitId) -> I18n.t("equipment.buy_blocked.cant_buy")
            !Equipment.isPurchasableGroundTransport(eqUnitId) -> I18n.t("equipment.buy_blocked.not_purchasable")
            // OG's purchase cap. Named rather than left silent for the same reason as the rules
            // around it: a Buy button that simply disappears reads as a bug (`rules/PurchaseCap`).
            !purchaseCapAllows() -> I18n.t("equipment.buy_blocked.purchase_cap")
            // The scenario's own Fronts/Factions list. Named rather than left silent for exactly
            // the reason the line above gives: a card the author closed off looks like a bug when
            // its Buy button simply disappears.
            !purchaseListAllows(eqUnitId, -1) -> I18n.t("equipment.buy_blocked.not_in_scenario_list")
            // The scenario's Fronts/Factions MASKS -- the same explanation, a different (and much
            // wider) source. Named separately from the `.buy4` list so the copy can be honest about
            // which of the two closed the card off.
            !frontsFactionsAllow(eqUnitId, -1) -> I18n.t("equipment.buy_blocked.not_in_scenario_list")
            !poolClassAllows(eqUnitId) -> I18n.t("equipment.buy_blocked.transport_pool")
            else -> campaignCountryRefusal(eqUnitId)
        }

    /** Mirrors [resolveBuyCost]'s campaign-country test so catalogue and button always agree. */
    private fun campaignCountryRefusal(eqUnitId: Int): String? {
        val campaign = ui.game.campaign ?: return null
        val eqCountry = (Equipment.getEquipment(eqUnitId)?.country ?: 0) - 1
        return if (campaign.country == eqCountry) {
            null
        } else {
            I18n.t(
                "equipment.buy_blocked.campaign_country",
                mapOf(
                    "selectedCountry" to Equipment.getCountryName(eqCountry),
                    "campaignCountry" to Equipment.getCountryName(campaign.country),
                ),
            )
        }
    }

    /** OG offers no purchases at all to a player with nowhere to place them; see
     *  [org.osada.model.hasPurchaseAnchor]. Evaluated live so capturing a port opens buying. */
    private fun hasPurchaseAnchor(): Boolean {
        val map = ui.game.scenario?.map
        val side = map?.currentPlayer?.side
        // No map/player yet -> permit; the gate is a rule, not a load-order guard.
        return if (map == null || side == null) true else map.hasPurchaseAnchor(side)
    }

    private fun resolveUpgradeAndSellCost(
        selectedUnit: GameUnit?,
        eqUnitId: Int,
        eqTransportId: Int,
    ): Pair<Int, Int> {
        if (selectedUnit == null) return 0 to 0
        var upgradeCost = 0
        val unitClass = EquipmentWindowState.normalizeUnitClass(selectedUnit.unitData(true).uclass)
        val unitCountry = selectedUnit.unitData(true).country - 1
        if (eqUnitId > 0) {
            val newEq = Equipment.getEquipment(eqUnitId)
            val newClass = newEq?.uclass?.let { EquipmentWindowState.normalizeUnitClass(it) } ?: -1
            val newCountry = (newEq?.country ?: 0) - 1
            if (unitClass == newClass && unitCountry == newCountry) {
                upgradeCost = GameRules.calculateUpgradeCosts(selectedUnit, eqUnitId, eqTransportId)
            }
        }
        val sellCost =
            if (ui.game.campaign == null || selectedUnit.isCore) {
                GameRules.calculateUnitSellCost(selectedUnit)
            } else {
                0
            }
        return upgradeCost to sellCost
    }

    /** The record-level half of [resolveBuyCost]'s refusals: OG's `Can't Buy` on either the unit or
     *  the transport bought with it, and OSADA's flat rule that a bare prime mover is attached
     *  rather than bought. Named so [resolveBuyCost] stays inside detekt's complexity budget and so
     *  [resolveBuyBlockedReason] can be read beside it. */
    private fun offeredForSale(
        eqUnitId: Int,
        eqTransportId: Int,
    ): Boolean =
        Equipment.isPurchasable(eqUnitId) &&
            (eqTransportId <= 0 || Equipment.isPurchasable(eqTransportId)) &&
            Equipment.isPurchasableGroundTransport(eqUnitId) &&
            purchaseListAllows(eqUnitId, eqTransportId) &&
            frontsFactionsAllow(eqUnitId, eqTransportId) &&
            poolClassAllows(eqUnitId) &&
            purchaseCapAllows()

    /** OG's Fronts/Factions as the scenario's own masks — `rules/FrontsAndFactions`. */
    private fun frontsFactionsAllow(
        eqUnitId: Int,
        eqTransportId: Int,
    ): Boolean {
        val player =
            ui.game.scenario
                ?.map
                ?.currentPlayer
        return FrontsAndFactions.admitsForPurchase(player, eqUnitId) &&
            (eqTransportId <= 0 || FrontsAndFactions.admitsForPurchase(player, eqTransportId))
    }

    /** OG's pool classes: air and naval transports come from the per-player pool, not the shop. */
    private fun poolClassAllows(eqUnitId: Int): Boolean =
        FrontsAndFactions.poolClassPurchasable(
            ui.game.scenario
                ?.map
                ?.currentPlayer,
            eqUnitId,
        )

    /** OG's per-player purchase cap — `rules/PurchaseCap`. Live, so replacing a loss re-opens the
     *  Buy button the moment the loss is swept. */
    private fun purchaseCapAllows(): Boolean =
        PurchaseCap.allows(
            ui.game.scenario
                ?.map
                ?.currentPlayer,
        )

    /** OG's Fronts/Factions as the scenario's own `.buy4` list — `rules/ScenarioPurchaseList`. */
    private fun purchaseListAllows(
        eqUnitId: Int,
        eqTransportId: Int,
    ): Boolean {
        val player =
            ui.game.scenario
                ?.map
                ?.currentPlayer
        return ScenarioPurchaseList.allows(player, eqUnitId) &&
            (eqTransportId <= 0 || ScenarioPurchaseList.allows(player, eqTransportId))
    }

    private fun resolveBuyCost(
        eqUnitId: Int,
        eqTransportId: Int,
        year: Int,
        month: Int,
    ): Int {
        if (eqUnitId <= 0) return -1
        val newEq = Equipment.getEquipment(eqUnitId)
        val newCountry = (newEq?.country ?: 0) - 1
        return when {
            // Gate on the class being BOUGHT, not on whatever unit happens to be selected --
            // see GameRules.isPurchasableClass for why PM's selection-based test was dropped.
            newEq != null && !GameRules.isPurchasableClass(newEq.uclass) -> -1
            newEq != null && !newEq.isAvailableIn(year, month) -> -1
            !offeredForSale(eqUnitId, eqTransportId) -> -1
            ui.game.campaign != null && ui.game.campaign!!.country != newCountry -> -1
            // Nowhere to place a purchase -> OG offers none at all; resolveBuyBlockedReason says why.
            !hasPurchaseAnchor() -> -1
            else -> GameRules.calculateUnitCosts(eqUnitId, eqTransportId)
        }
    }
}
