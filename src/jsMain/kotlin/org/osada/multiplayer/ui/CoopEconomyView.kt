package org.osada.multiplayer.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.RespondPurchase
import org.osada.multiplayer.coop.CoopSpending
import org.osada.multiplayer.coop.CoopState
import org.w3c.dom.HTMLElement

/** Optional proposals and actual treasury changes, separate from contribution points. */
internal object CoopEconomyView {
    private var expanded = false

    fun render(
        state: CoopState,
        self: String?,
    ): HTMLElement {
        val box = document.createElement("details") as HTMLElement
        box.className = "osada-coop-economy"
        box.asDynamic().open = expanded
        box.addEventListener("toggle", { expanded = box.asDynamic().open as? Boolean ?: false })
        val summary = document.createElement("summary") as HTMLElement
        summary.textContent = I18n.t("multiplayer.coop.economy.title")
        box.appendChild(summary)
        val body = document.createElement("div") as HTMLElement
        body.className = "osada-coop-economy-body"
        box.appendChild(body)
        body.appendChild(MultiplayerScreen.text(I18n.t("multiplayer.coop.economy.help")))
        state.economy.proposals.forEach { proposal ->
            val name = state.commanderById(proposal.commanderId)?.name ?: proposal.commanderId
            val unitName = Equipment.getEquipment(proposal.equipmentId)?.name ?: proposal.equipmentId.toString()
            val transportName = proposal.transportEquipmentId?.let { Equipment.getEquipment(it)?.name ?: it.toString() }
            val equipment = listOfNotNull(unitName, transportName).joinToString(" / ")
            val row = MultiplayerScreen.panel("osada-coop-purchase-proposal")
            row.setAttribute("data-proposal-id", proposal.id)
            row.appendChild(
                MultiplayerScreen.text(
                    I18n.t(
                        "multiplayer.coop.economy.proposal",
                        mapOf("name" to name, "equipment" to equipment, "cost" to proposal.quotedCost),
                    ),
                ),
            )
            if (self != null) {
                val approve = MultiplayerScreen.button(I18n.t("multiplayer.coop.economy.approve"))
                approve.disabled = self == proposal.commanderId || !OsadaMultiplayer.acceptsLocalCommands()
                approve.onclick =
                    { OsadaMultiplayer.submitOrder(RespondPurchase(proposal.id, true, state.humanPlayerId)) }
                row.appendChild(approve)
                val decline = MultiplayerScreen.button(I18n.t("multiplayer.coop.economy.decline"), quiet = true)
                decline.onclick =
                    { OsadaMultiplayer.submitOrder(RespondPurchase(proposal.id, false, state.humanPlayerId)) }
                row.appendChild(decline)
            }
            body.appendChild(row)
        }
        state.economy.spending
            .asReversed()
            .forEach { body.appendChild(spendingLine(state, it)) }
        return box
    }

    private fun spendingLine(
        state: CoopState,
        entry: CoopSpending,
    ): HTMLElement {
        val name = state.historicalCommanderById(entry.commanderId)?.name ?: entry.commanderId
        val action = I18n.tOrNull("multiplayer.coop.economy.action.${entry.action}") ?: entry.action
        val change = if (entry.change > 0) "+${entry.change}" else entry.change.toString()
        val line =
            MultiplayerScreen.text(
                I18n.t(
                    "multiplayer.coop.economy.entry",
                    mapOf("name" to name, "action" to action, "change" to change, "balance" to entry.balance),
                ),
            )
        line.setAttribute("data-spending-revision", entry.revision.toString())
        val approved = entry.approvedBy?.let { state.historicalCommanderById(it)?.name ?: it }
        if (approved !=
            null
        ) {
            line.appendChild(
                MultiplayerScreen.text(
                    I18n.t(
                        "multiplayer.coop.economy.approved_by",
                        mapOf(
                            "name" to approved,
                        ),
                    ),
                ),
            )
        }
        return line
    }
}
