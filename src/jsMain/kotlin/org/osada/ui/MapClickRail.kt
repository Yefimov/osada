package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.clearRailTargeting
import org.osada.model.moveByRail
import org.osada.model.openRailDestinations
import org.osada.uiSettings

/**
 * A click while OG's railway destination mode is open.
 *
 * Travelling is the only thing this click can do: an offered hex is reached, and **anything else
 * cancels the mode** rather than doing whatever that click would normally have done. The same
 * rule `MapClickBarrage` follows, and for the same reason — a mode that swallows the next click
 * and also moves your formation is worse than one that only ever closes.
 *
 * Lives beside [MapClickHandler] rather than inside it because that class is at its function
 * budget.
 */
internal fun MapClickHandler.resolveRailClick(
    map: GameMap,
    cell: Cell,
    hex: Hex,
): Boolean {
    val unit = map.currentUnit
    uiSettings.railMode = false
    val moved = unit != null && hex.isRailSel && map.moveByRail(unit, cell.row, cell.col)
    if (unit == null || !hex.isRailSel) map.clearRailTargeting()
    if (unit != null) {
        if (hex.isRailSel || moved) {
            val key = if (moved) "unit_info.action.rail_move.done" else "unit_info.action.rail_move.blocked"
            ui.showAlert(cell.row, cell.col, I18n.t(key), true)
            console.log("[OSADA] rail ${unit.id} -> ${cell.row},${cell.col}: $moved")
        }
        ui.render.render(cell.row, cell.col, getUnitRenderRadius(unit))
    }
    return moved
}

/**
 * The Railway chip: like Barrage it needs a DESTINATION, so it opens a mode instead of acting.
 * Pressing it again closes the mode.
 */
internal fun UnitContextMenu.toggleRailTargeting(
    map: GameMap,
    unit: GameUnit,
    pos: Cell,
) {
    if (uiSettings.railMode) {
        uiSettings.railMode = false
        map.clearRailTargeting()
    } else {
        uiSettings.railMode = true
        map.openRailDestinations(unit)
        ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.rail_move.pick"), true)
    }
    ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit))
}
