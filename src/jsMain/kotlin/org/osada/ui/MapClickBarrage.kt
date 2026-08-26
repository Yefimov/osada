package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.clearBarrageTargeting
import org.osada.model.fireBarrage
import org.osada.model.openBarrageTargeting
import org.osada.uiSettings

/**
 * A click while OG 9.2's Barrage targeting mode is open.
 *
 * Firing is the only thing this click can do: an offered hex is shelled, and **anything else
 * cancels the mode** rather than doing whatever that click would normally have done. A mode that
 * swallows the next click and also moves your gun is worse than one that only ever closes, and the
 * mode is a single chip press away from being reopened.
 *
 * Lives beside [MapClickHandler] rather than inside it because that class is at its function budget
 * — the same reason `MoveExecutorHelpers.kt` exists.
 */
internal fun MapClickHandler.resolveBarrageClick(
    map: GameMap,
    cell: Cell,
    hex: Hex,
): Boolean {
    val unit = map.currentUnit
    uiSettings.barrageMode = false
    val result = if (unit != null && hex.isBarrageSel) map.fireBarrage(unit, cell.row, cell.col) else null
    if (unit == null || !hex.isBarrageSel) map.clearBarrageTargeting()
    if (unit != null) {
        if (hex.isBarrageSel || result != null) {
            ui.showAlert(cell.row, cell.col, I18n.t(barrageMessageKey(result)), true)
            // One console line per barrage, the way the AI's moves and the attack diagnostics are
            // logged: a shot into a hex nobody can see is exactly what a player asks about later.
            console.log("[OSADA] barrage ${unit.id} -> ${cell.row},${cell.col}: $result")
        }
        ui.render.render(cell.row, cell.col, getUnitRenderRadius(unit))
    }
    return result != null
}

/** Which sentence the banner shows — OG's three outcomes, plus a miss and a refusal. */
private fun barrageMessageKey(result: org.osada.rules.Barrage.BarrageResult?): String =
    when {
        result == null -> "unit_info.action.barrage.blocked"
        !result.hit -> "unit_info.action.barrage.done.miss"
        result.blewBridge -> "unit_info.action.barrage.done.bridge"
        result.wreckedTerrain -> "unit_info.action.barrage.done.wrecked"
        result.leftRubble -> "unit_info.action.barrage.done.rubble"
        result.leftCrater -> "unit_info.action.barrage.done.crater"
        else -> "unit_info.action.barrage.done.hit"
    }

/**
 * The Barrage chip: OG 9.2's is the one action in the strip that needs a TARGET, so it opens a
 * targeting mode instead of firing. Pressing it again closes the mode — a mode entered by accident
 * has to be leavable the same way it was entered.
 */
internal fun UnitContextMenu.toggleBarrageTargeting(
    map: GameMap,
    unit: GameUnit,
    pos: Cell,
) {
    if (uiSettings.barrageMode) {
        uiSettings.barrageMode = false
        map.clearBarrageTargeting()
    } else {
        uiSettings.barrageMode = true
        map.openBarrageTargeting(unit)
        ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.barrage.pick"), true)
    }
    ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit))
}
