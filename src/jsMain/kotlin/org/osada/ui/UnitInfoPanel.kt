package org.osada.ui

import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.uiSettings

/**
 * Displays unit/equipment statistics in the unit-info panel, builds the per-unit action
 * context menu, and executes those actions. Extracted from the former `UI` god-class (SRP).
 * The context menu lives in [UnitContextMenu]; the live-unit and equipment-catalogue stat
 * cards in [UnitStatCard] / [EquipmentStatCard]; the hover forecast in [UnitHoverForecast].
 */
internal class UnitInfoPanel(
    private val ui: UI,
) {
    private val contextMenu = UnitContextMenu(ui)
    private val hoverForecast = UnitHoverForecast(ui)
    private val statCard = UnitStatCard(ui, hoverForecast)

    fun buildUnitContext(unit: GameUnit?) = contextMenu.buildUnitContext(unit)

    /**
     * The unit-info panel normally only renders when the user has pinned it via "Inspect Unit"
     * (`unitInfoVisibility`). While the buy/deploy equipment window is open we force it visible so
     * selecting a unit always shows its stats (PM leaves this to the pinned toggle; we make it
     * automatic during purchase). [hideUnitInfoIfNotPinned] restores the pre-buy state on close.
     */
    fun hideUnitInfoIfNotPinned() {
        if (!uiSettings.unitInfoVisibility) {
            makeHidden("unit-info")
            byId("inspectunit")?.let { toggleButton(it, false) }
        }
    }

    fun showUnitInfo(unit: GameUnit?) {
        statCard.showUnitInfo(unit)
        UnitIdentityPresenter.present(ui, unit)
    }

    fun showEquipmentInfo(eq: EquipmentData?) = EquipmentStatCard.showEquipmentInfo(eq)

    fun updateHoverInfo(
        row: Int,
        col: Int,
    ) = hoverForecast.updateHoverInfo(row, col)

    fun showEnemyCard(unit: GameUnit) = hoverForecast.showEnemyCard(unit)
}

/**
 * True while the buy/deploy equipment window (or its reserve strip) is open — see
 * [UnitInfoPanel.hideUnitInfoIfNotPinned]'s doc comment. Shared by [UnitStatCard] and
 * [EquipmentStatCard].
 */
internal fun equipmentWindowOpen(): Boolean = isVisible("equipment") || isVisible("container-unitlist")
