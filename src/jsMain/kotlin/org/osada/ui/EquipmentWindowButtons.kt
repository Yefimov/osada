package org.osada.ui

import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.buyUnit
import org.osada.model.disbandUnit
import org.osada.model.sellUnit
import org.osada.model.upgradeUnit

/**
 * [EquipmentWindowController]'s equipment-window button actions (change country / buy / upgrade
 * / sell). Split out purely to keep [EquipmentWindowController] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal class EquipmentWindowButtons(
    private val ui: UI,
) {
    fun handle(action: String) {
        val map = ui.game.scenario?.map
        val player = map?.currentPlayer
        val eqUserSel = byId("eqUserSel")?.asDynamic()
        if (map == null || player == null || eqUserSel == null) return
        when (action) {
            "changecountry" -> onChangeCountry(eqUserSel)
            "buy" -> onBuy(player, eqUserSel)
            "upgrade" -> onUpgrade(map, player, eqUserSel)
            "sell" -> onSell(map, player, eqUserSel)
        }
    }

    private fun onChangeCountry(eqUserSel: dynamic) {
        val countryEl = byId("eqSelCountry") ?: return
        val current = countryEl.asDynamic().country as? Int ?: 0
        val next = if (current >= ui.countriesOnSpotSide.size - 1) 0 else current + 1
        countryEl.asDynamic().country = next
        eqUserSel.userunit = -1
        eqUserSel.equnit = -1
        ui.updateEquipmentWindow(eqUserSel.eqclass as? Int ?: UnitClass.TANK.value)
    }

    private fun onBuy(
        player: Player,
        eqUserSel: dynamic,
    ) {
        val equnit = eqUserSel.equnit as? Int ?: -1
        var transport = eqUserSel.eqtransport as? Int ?: -1
        if (equnit <= 0) return
        if (transport < 0) transport = -1
        if (!player.buyUnit(equnit, transport)) return
        Equipment.getEquipment(equnit)?.let { ui.updateEquipmentWindow(it.uclass) }
        // Refreshes the Reserves button's undeployed-count badge — buying grows the
        // reserve pool, but nothing else on this path calls updateStatusBar (it's
        // normally driven by turn changes / window open-close), so without this the
        // badge silently stayed stale until some unrelated event happened to refresh it.
        ui.updateStatusBar()
        saveReserveChanges()
    }

    private fun onUpgrade(
        map: GameMap,
        player: Player,
        eqUserSel: dynamic,
    ) {
        val userUnitId = eqUserSel.userunit as? Int ?: -1
        val deployUnitId = eqUserSel.deployunit as? Int ?: -1
        val equnit = eqUserSel.equnit as? Int ?: -1
        var transport = eqUserSel.eqtransport as? Int ?: -1
        if (transport < 0) transport = -1
        if (userUnitId == -1) {
            val unit = player.getCoreUnitList().getOrNull(deployUnitId)
            if (unit != null && player.upgradeUnit(unit, equnit, transport)) {
                ui.updateEquipmentWindow(unit.unitData(true).uclass)
                saveReserveChanges()
            }
        } else {
            if (map.upgradeUnit(userUnitId, equnit, transport)) {
                ui.render.cacheImages { ui.render.render() }
                if (equnit > 0) Equipment.getEquipment(equnit)?.let { ui.updateEquipmentWindow(it.uclass) }
                saveReserveChanges()
            }
        }
    }

    private fun onSell(
        map: GameMap,
        player: Player,
        eqUserSel: dynamic,
    ) {
        val userUnitId = eqUserSel.userunit as? Int ?: -1
        val deployUnitId = eqUserSel.deployunit as? Int ?: -1
        val equnit = eqUserSel.equnit as? Int ?: -1
        if (userUnitId == -1) {
            val unit = player.getCoreUnitList().getOrNull(deployUnitId)
            if (unit != null && player.sellUnit(unit)) {
                player.removeUndeployedCoreUnit(deployUnitId)
                ui.updateEquipmentWindow(unit.unitData(true).uclass)
                ui.updateStatusBar() // reserve pool shrank — refresh the badge, see "buy" above
                saveReserveChanges()
            }
        } else {
            if (map.disbandUnit(userUnitId)) {
                ui.render.cacheImages { ui.render.render() }
                eqUserSel.userunit = -1
                if (equnit > 0) Equipment.getEquipment(equnit)?.let { ui.updateEquipmentWindow(it.uclass) }
                saveReserveChanges()
            }
        }
    }

    /** Purchase/refit decisions are part of deployment preparation and must survive a reload even
     * before the player ends turn one. */
    private fun saveReserveChanges() {
        ui.game.state?.save()
    }
}
