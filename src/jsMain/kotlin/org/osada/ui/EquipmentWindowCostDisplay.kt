package org.osada.ui

import kotlinx.browser.window
import org.osada.i18n.I18n

// The four independent cost-row renderers behind [EquipmentWindowBuilder.showEquipmentCosts],
// split out to keep that object within the project's function-count limits (each operates on its
// own disjoint set of DOM ids and has no shared state with the others).

internal fun showBuyCost(
    prestige: Int,
    buy: Int,
    buyBlockedReason: String?,
) {
    val eqNewText = byId("eqNewText")
    val eqNewCost = byId("eqNewCost")
    val eqNewBut = byId("eqNewBut")
    if (buy > 0 && buy <= prestige) {
        eqNewText?.textContent = I18n.t("equipment.action.buy.label")
        eqNewCost?.innerHTML = "$buy${UIBuilder.currencyIcon}"
        eqNewBut?.style?.display = "inline-block"
    } else {
        // A rule refusing the purchase outranks the wallet. Saving up cannot lift a rule, so
        // "Need N more prestige" would send the player after a unit they can never buy — and the
        // message it would hide is the one that explains why (no supply hex / wrong campaign nation).
        if (buyBlockedReason != null) {
            eqNewText?.innerHTML = "<span style='color:#BB7575'>$buyBlockedReason</span>"
        } else if (buy > prestige) {
            val diff = buy - prestige
            eqNewText?.innerHTML =
                "<span style='color:#BB7575'>" +
                I18n.t("equipment.cost.need_buy", mapOf("amount" to diff)) +
                "</span>"
        } else {
            eqNewText?.textContent = ""
        }
        eqNewBut?.style?.display = "none"
        eqNewCost?.textContent = ""
    }
}

internal fun showUpgradeCost(
    prestige: Int,
    upgrade: Int,
) {
    val eqUpgradeText = byId("eqUpgradeText")
    val eqUpgradeCost = byId("eqUpgradeCost")
    val eqUpgradeBut = byId("eqUpgradeBut")
    if (upgrade > 0 && upgrade <= prestige) {
        eqUpgradeText?.textContent = I18n.t("equipment.action.upgrade.label")
        eqUpgradeCost?.innerHTML = "$upgrade${UIBuilder.currencyIcon}"
        eqUpgradeBut?.style?.display = "inline-block"
    } else {
        if (upgrade > prestige) {
            val diff = upgrade - prestige
            eqUpgradeText?.innerHTML =
                "<span style='color:#BB7575'>" +
                I18n.t("equipment.cost.need_upgrade", mapOf("amount" to diff)) +
                "</span>"
        } else {
            eqUpgradeText?.textContent = ""
        }
        eqUpgradeBut?.style?.display = "none"
        eqUpgradeCost?.textContent = ""
    }
}

internal fun showSellCost(sell: Int) {
    val eqSellText = byId("eqSellText")
    val eqSellCost = byId("eqSellCost")
    val eqSellBut = byId("eqSellBut")
    if (sell > 0) {
        eqSellText?.textContent = I18n.t("equipment.action.sell.label")
        eqSellCost?.innerHTML = "$sell${UIBuilder.currencyIcon}"
        eqSellBut?.style?.display = "inline-block"
    } else {
        eqSellBut?.style?.display = "none"
        eqSellCost?.textContent = ""
        eqSellText?.textContent = ""
    }
}

internal fun showCurrentPrestige(prestige: Int) {
    val currentPrestige = byId("currentPrestige")
    currentPrestige?.textContent =
        I18n.t(
            if (window.innerWidth >= EquipmentWindowBuilder.NARROW_PRESTIGE_LABEL_WIDTH_THRESHOLD) {
                "equipment.cost.available"
            } else {
                "equipment.cost.available_short"
            },
        ) + " "
    val currentPrestigeAmount = byId("currentPrestigeAmount")
    currentPrestigeAmount?.innerHTML = "$prestige${UIBuilder.currencyIcon}"
}
