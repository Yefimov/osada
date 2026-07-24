package org.osada.ui

import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.getUnitById
import org.osada.model.isAvailableIn
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCosts
import org.osada.rules.calculateUnitSellCost
import org.osada.rules.calculateUpgradeCosts

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
        val buyCost = resolveBuyCost(selectedUnit, eqUnitId, eqTransportId, year, month)
        UIBuilder.showEquipmentCosts(currentPlayer.prestige, buyCost, upgradeCost, sellCost)
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

    private fun resolveBuyCost(
        selectedUnit: GameUnit?,
        eqUnitId: Int,
        eqTransportId: Int,
        year: Int,
        month: Int,
    ): Int {
        if (eqUnitId <= 0) return -1
        val newEq = Equipment.getEquipment(eqUnitId)
        val newCountry = (newEq?.country ?: 0) - 1
        return when {
            selectedUnit != null &&
                !UIBuilder.eqClassButtons.containsKey(selectedUnit.unitData(true).uclass.toString()) -> -1
            newEq != null && !newEq.isAvailableIn(year, month) -> -1
            ui.game.campaign != null && ui.game.campaign!!.country != newCountry -> -1
            else -> GameRules.calculateUnitCosts(eqUnitId, eqTransportId)
        }
    }
}
