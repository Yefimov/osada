package org.osada.ui

import org.osada.model.EquipmentData
import org.osada.model.GameUnit

/** Panel/equipment-window bridges for [UI], split out to keep its function count in bounds. */
fun UI.uiEndTurnInfo() {
    render.render()
    statusBarController.showStatusExtension()
}

/** Refresh the status-bar weather/ground glyph after the per-turn weather simulation changes it. */
fun UI.refreshWeatherDisplay() {
    statusBarController.updateStatusBar()
}

fun UI.equipmentWindowButtons(action: String) = eqWindowController.equipmentWindowButtons(action)

fun UI.updateEquipmentWindow(unitClass: Int) = eqWindowController.updateEquipmentWindow(unitClass)

internal fun UI.buildUnitContext(unit: GameUnit?) = unitInfoPanel.buildUnitContext(unit)

internal fun UI.showUnitInfo(unit: GameUnit?) = unitInfoPanel.showUnitInfo(unit)

internal fun UI.showEquipmentInfo(eq: EquipmentData?) = unitInfoPanel.showEquipmentInfo(eq)

internal fun UI.updateHoverInfo(
    row: Int,
    col: Int,
) = unitInfoPanel.updateHoverInfo(row, col)

internal fun UI.showEnemyCard(unit: GameUnit) = unitInfoPanel.showEnemyCard(unit)

/** Restore the unit-info panel after closing the buy/deploy window (hide unless user-pinned). */
internal fun UI.hideUnitInfoIfNotPinned() = unitInfoPanel.hideUnitInfoIfNotPinned()

internal fun UI.toggleStrategicZoom() = strategicZoomController.toggleStrategicZoom()
