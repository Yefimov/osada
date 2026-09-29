package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.detrainUnit
import org.osada.model.entrainUnit
import org.osada.rules.RailTransport

/**
 * The Rail chip: boards a train where the formation stands, or takes it off one (`rules/RailTransport`).
 *
 * Boarding changes the formation's art to the train's, which need not be in the sprite cache yet --
 * the same reason a purchase re-caches (`EquipmentWindowButtons`) -- so the map is redrawn once the
 * image is in rather than around the unit alone.
 *
 * Lives beside [UnitContextMenu] rather than inside it because that class is at its function budget.
 */
internal fun UnitContextMenu.performRailAction(
    map: GameMap,
    unit: GameUnit,
) {
    val aboard = RailTransport.isEntrained(unit)
    val done = if (aboard) map.detrainUnit(unit) else map.entrainUnit(unit)
    val pos = unit.getPos() ?: return
    if (done) {
        val key = if (aboard) "unit_info.action.rail_move.detrained" else "unit_info.action.rail_move.entrained"
        ui.showAlert(pos.row, pos.col, I18n.t(key), true)
    }
    ui.render.cacheImages { ui.render.render() }
}
