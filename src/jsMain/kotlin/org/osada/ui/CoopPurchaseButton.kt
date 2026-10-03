package org.osada.ui

import kotlinx.browser.document
import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.ProposePurchase
import org.osada.multiplayer.command.PurchaseUnit
import org.osada.multiplayer.coop.CoopRuntime
import org.w3c.dom.HTMLButtonElement

internal object CoopPurchaseButton {
    fun quote(order: PurchaseUnit): Int? {
        val ui = GameHolder.instance?.ui
        val date = ui?.game?.scenario?.date
        val offered =
            Equipment.getEquipment(order.equipmentId) != null &&
                (order.transportEquipmentId == null || Equipment.getEquipment(order.transportEquipmentId) != null)
        return if (ui != null && date != null && offered) {
            EquipmentCostsCalculator(ui)
                .resolveBuyCost(
                    order.equipmentId,
                    order.transportEquipmentId ?: -1,
                    date.getFullYear(),
                    date.getMonth() + 1,
                ).takeIf { it >= 0 }
        } else {
            null
        }
    }

    fun update(buyCost: Int) {
        val coop = CoopRuntime.state
        val existing = document.getElementById("coopProposePurchase")
        if (coop == null) {
            existing?.remove()
            return
        }
        val buy = byId("eqNewBut") ?: return
        val button =
            existing as? HTMLButtonElement ?: (document.createElement("button") as HTMLButtonElement).also {
                it.id = "coopProposePurchase"
                it.type = "button"
                it.className = "osada-coop-purchase-button"
                buy.parentElement?.appendChild(it)
            }
        button.textContent = I18n.t("multiplayer.coop.economy.propose")
        button.disabled = buyCost < 0 || coop.briefing?.open == true || !OsadaMultiplayer.acceptsLocalCommands()
        button.onclick = {
            val selection = byId("eqUserSel")?.asDynamic()
            val eqid = selection?.equnit as? Int ?: -1
            val transport = (selection?.eqtransport as? Int)?.takeIf { it > 0 }
            if (eqid > 0) OsadaMultiplayer.submitOrder(ProposePurchase(eqid, transport, coop.humanPlayerId))
        }
    }
}
