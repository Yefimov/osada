package org.osada.ui

import org.osada.TerrainType
import org.osada.TooltipColor
import org.osada.TooltipStyle
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.getPlayer
import org.osada.rules.GameRules
import org.osada.rules.airGroundedByWeather
import org.osada.rules.isAir
import org.osada.rules.unitLowAmmo
import org.osada.rules.unitLowFuel
import org.osada.uiSettings

/** Tooltip/alert helpers for [UI], split out to keep its function count in bounds. */
fun UI.showAlert(
    row: Int,
    col: Int,
    text: String,
    friendly: Boolean,
) {
    val pos = render.cellToScreen(row, col, true)
    val color = if (friendly) TooltipColor.PLAYER else TooltipColor.ENEMY
    UIBuilder.gameSmallToolTip(text, pos.x.toInt(), pos.y.toInt(), color, null, TooltipStyle.TEXT)
}

fun UI.showGameToolTip(
    message: String,
    row: Int,
    col: Int,
) {
    val pos = render.cellToScreen(row, col, true)
    UIBuilder.gameToolTip(message, pos.x.toInt(), pos.y.toInt())
}

@Suppress("UnusedReceiverParameter")
fun UI.removeAllSmallToolTips(clearUnitTooltips: Boolean = false) {
    val list = UIBuilder.smallToolTipList.toList()
    list.reversed().forEach { id ->
        if (clearUnitTooltips || !id.startsWith("gsttu")) {
            delTag(byId(id))
            UIBuilder.smallToolTipList.remove(id)
        }
    }
}

fun UI.addSmallToolTips(all: Boolean = false) {
    val map = game.scenario?.map ?: return
    val currentPlayer = map.currentPlayer ?: return
    val side = currentPlayer.side
    for (r in 0 until map.rows) {
        for (c in 0 until map.cols) {
            val hex = map.map?.get(r)?.get(c) ?: continue
            val hasHexToolTip = !all && addHexFlagToolTip(this, map, hex, r, c, side, currentPlayer)
            if (!hasHexToolTip) addUnitStatusToolTips(this, hex, r, c, side)
        }
    }
}

@Suppress("UnusedReceiverParameter")
fun UI.removeUnitToolTip(unitId: Int) {
    val id = "gsttu$unitId"
    delTag(byId(id))
    UIBuilder.smallToolTipList.remove(id)
}

/** Objective/flag tooltip half of [addSmallToolTips]'s per-hex body. Returns whether a
 *  non-null tooltip text was resolved (and, if so, drawn) — the caller uses this to decide
 *  whether the unit-status tooltip branch should still run for this hex. */
private fun addHexFlagToolTip(
    ui: UI,
    map: GameMap,
    hex: Hex,
    r: Int,
    c: Int,
    side: Int,
    currentPlayer: Player,
): Boolean {
    if (hex.flag == -1 || hex.owner == -1) return false
    var text: String? =
        if (hex.name.isNotEmpty()) {
            hex.name
        } else if (hex.victorySide != -1) {
            "Objective"
        } else {
            null
        }
    if (!uiSettings.showDetailInfoToolTips && hex.victorySide == -1) text = null
    var color = TooltipColor.ENEMY
    var style = TooltipStyle.TEXT
    // hex.owner is a player id, not a side; comparing directly misreports
    // ownership color whenever a side has more than one player (pre-existing bug,
    // same root cause as the sidebar objectives "held" bug this session fixed).
    if (map.getPlayer(hex.owner).side == side) color = TooltipColor.PLAYER
    if (hex.terrain == TerrainType.AIRFIELD.value && currentPlayer.airTransports > 0) {
        text =
            "${currentPlayer.airTransports}&nbsp;" +
                "<span style='font-family: osada-menu;'>&#xe900;</span> "
        style = TooltipStyle.PIN
    }
    if (hex.terrain == TerrainType.PORT.value && currentPlayer.navalTransports > 0) {
        text =
            "${currentPlayer.navalTransports}&nbsp;" +
                "<span style='font-family: osada-menu;'>&#xe901;</span>"
        style = TooltipStyle.PIN
    }
    text?.let {
        val pos = ui.render.cellToScreen(r, c, true)
        UIBuilder.gameSmallToolTip(it, pos.x.toInt(), pos.y.toInt(), color, null, style)
    }
    return text != null
}

/** Unit-status (no ammo / no fuel / grounded) tooltip half of [addSmallToolTips]'s per-hex
 *  body — only reached when the hex itself didn't already resolve an objective/flag tooltip. */
private fun addUnitStatusToolTips(
    ui: UI,
    hex: Hex,
    r: Int,
    c: Int,
    side: Int,
) {
    val unit = hex.getUnit(uiSettings.airMode) ?: return
    if (unit.player?.side != side) return
    val unitTipId = "gsttu${unit.id}"
    if (GameRules.unitLowAmmo(unit, 1)) {
        val pos = ui.render.cellToScreen(r, c, true)
        UIBuilder.gameSmallToolTip("No Ammo", pos.x.toInt(), pos.y.toInt(), 0, unitTipId, TooltipStyle.TEXT)
    }
    if (GameRules.unitLowFuel(unit, 1)) {
        val pos = ui.render.cellToScreen(r, c, true)
        UIBuilder.gameSmallToolTip("No Fuel", pos.x.toInt(), pos.y.toInt(), 0, unitTipId, TooltipStyle.TEXT)
    }
    // Bad weather silently empties an air unit's attack range (CombatResolver.
    // airGroundedByWeather) with zero explanation otherwise — reads exactly like a
    // bug ("my plane can't shoot") rather than the OG rule it actually is. Own id
    // suffix (not the shared unitTipId): gameSmallToolTip sets the DOM id literally,
    // so reusing unitTipId here would collide with the ammo/fuel tooltip's own id
    // for the same unit if more than one condition is true at once.
    if (GameRules.isAir(unit) && GameRules.airGroundedByWeather(unit)) {
        val pos = ui.render.cellToScreen(r, c, true)
        // "Grounded" not "Grounded (Weather)": .smallToolTip is a fixed 101x15px
        // box sized for "No Ammo"/"No Fuel" — the longer text overflowed it, with
        // "(Weather)" clipped outside the box. Matches the unit-card badge's own
        // wording (osadaUcWeather), which carries the full explanation on hover.
        UIBuilder.gameSmallToolTip("Grounded", pos.x.toInt(), pos.y.toInt(), 0, "${unitTipId}w", TooltipStyle.TEXT)
    }
}
