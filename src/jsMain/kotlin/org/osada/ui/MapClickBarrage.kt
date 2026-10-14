package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.clearBarrageTargeting
import org.osada.model.fireBarrage
import org.osada.model.openBarrageTargeting
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.BarrageHex
import org.osada.multiplayer.command.HexCoordinate
import org.osada.rules.GameRules
import org.osada.rules.getDirection
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
    // In a room the shot is an order for the host; the targeting mode closes here either way.
    if (OsadaMultiplayer.active) {
        if (unit != null && hex.isBarrageSel) {
            OsadaMultiplayer.submitOrder(BarrageHex(unit.id, HexCoordinate(cell.col, cell.row), unit.owner))
        }
        map.clearBarrageTargeting()
        return unit != null && hex.isBarrageSel
    }
    val result = if (unit != null && hex.isBarrageSel) map.fireBarrage(unit, cell.row, cell.col) else null
    if (unit == null || !hex.isBarrageSel) map.clearBarrageTargeting()
    if (unit != null) {
        if (hex.isBarrageSel || result != null) {
            // No banner for a shot that was fired (owner, 2026-10-14): the animation and what it
            // leaves on the map are the feedback. Only a refused order is explained.
            if (result == null) ui.showAlert(cell.row, cell.col, I18n.t("unit_info.action.barrage.blocked"), true)
            // One console line per barrage, the way the AI's moves and the attack diagnostics are
            // logged: a shot into a hex nobody can see is exactly what a player asks about later.
            console.log("[OSADA] barrage ${unit.id} -> ${cell.row},${cell.col}: $result")
        }
        if (result != null) playBarrageFire(unit, cell)
        ui.render.render(cell.row, cell.col, getUnitRenderRadius(unit))
    }
    return result != null
}

/**
 * The firing animation and its sound, the same ones an ordinary attack by [unit] plays: a barrage
 * is the gun (or the bomber) firing, and a shot the player ordered without hearing it reads as if
 * nothing happened. Plays whether or not the roll landed, since the shells were fired either way.
 */
private fun MapClickHandler.playBarrageFire(
    unit: GameUnit,
    target: Cell,
) {
    val pos = unit.getPos() ?: return
    val type = attackAnimationByClass.getOrNull(unit.unitData(true).uclass) ?: return
    val direction = GameRules.getDirection(pos.row, pos.col, target.row, target.col) ?: unit.facing
    ui.render.addAnimation(pos.row, pos.col, type, direction, unit)
    ui.render.runAnimation(null)
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
        val chance = mapOf("chance" to org.osada.rules.Barrage.HIT_CHANCE_PERCENT)
        ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.barrage.pick", chance), true)
    }
    ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit))
}
