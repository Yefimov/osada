package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.getUnitById
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.DisbandUnit
import org.osada.multiplayer.command.PurchaseUnit
import org.osada.multiplayer.command.UpgradeUnit

/**
 * The equipment window's buy / upgrade / sell buttons in a multiplayer room: each becomes an order
 * for the host (`OsadaMultiplayer.submitOrder`), which applies it and publishes the new state. The
 * selection is read exactly as `EquipmentWindowButtons` reads it -- a reserve formation by its tray
 * index, a deployed one by its unit id -- and the host resolves either (`findCommandUnit`).
 */
internal object HostHqOrders {
    fun send(
        action: String,
        map: GameMap,
        player: Player,
        eqUserSel: dynamic,
    ) {
        val equnit = eqUserSel.equnit as? Int ?: -1
        val transport = (eqUserSel.eqtransport as? Int ?: -1).takeIf { it > 0 }
        val unitId = selectedUnitId(map, player, eqUserSel)
        when (action) {
            "buy" -> if (equnit > 0) OsadaMultiplayer.submitOrder(PurchaseUnit(equnit, transport, player.id))
            "upgrade" -> unitId?.let { OsadaMultiplayer.submitOrder(UpgradeUnit(it, equnit, transport, player.id)) }
            "sell" -> unitId?.let { sell(map, player, it) }
        }
    }

    /** Selling and disbanding are permanent, so the same confirmation the local path asks first. */
    private fun sell(
        map: GameMap,
        player: Player,
        unitId: Int,
    ) {
        val unit = map.getUnitById(unitId) ?: player.getCoreUnitList().firstOrNull { it.id == unitId } ?: return
        SellDisbandConfirmDialog.open(unit, isDisband = unit.isDeployed) {
            OsadaMultiplayer.submitOrder(DisbandUnit(unitId, player.id))
        }
    }

    private fun selectedUnitId(
        map: GameMap,
        player: Player,
        eqUserSel: dynamic,
    ): Int? {
        val userUnitId = eqUserSel.userunit as? Int ?: -1
        val deployUnitId = eqUserSel.deployunit as? Int ?: -1
        return if (userUnitId == -1) {
            player.getCoreUnitList().getOrNull(deployUnitId)?.id
        } else {
            map.getUnitById(userUnitId)?.id
        }
    }
}
