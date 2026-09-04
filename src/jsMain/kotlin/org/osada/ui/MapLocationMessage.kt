package org.osada.ui

import org.osada.RoadType
import org.osada.i18n.I18n
import org.osada.rules.UnitConcealment
import org.osada.terrainNames
import org.osada.uiSettings

/**
 * The top-bar location line ("Forest, Road - Kalach (12,7)"). Lifted out of [MapInputController]
 * so that class stays inside the project's function-count limit after taking on the pointer
 * pipeline; the text and its fog-of-war rule are unchanged.
 */
internal fun updateMapLocationMessage(
    ui: UI,
    row: Int,
    col: Int,
) {
    val map = ui.game.scenario?.map ?: return
    val hex = map.map?.get(row)?.get(col) ?: return
    val currentSide = map.currentPlayer?.side ?: 0
    val sb = StringBuilder()
    sb.append(terrainNames.getOrNull(hex.terrain) ?: "Unknown")
    if (hex.road != RoadType.NONE.value) sb.append(", Road")
    if (hex.name.isNotEmpty()) sb.append(" - ${hex.name}")
    val unit = hex.getUnit(uiSettings.airMode)
    val unitVisible = unit != null && UnitConcealment.isVisibleTo(unit, currentSide)
    if (unitVisible) {
        val data = unit.unitData()
        sb.append(" (${data.name}")
        if (unit.strength > 0) sb.append(" ${unit.strength}")
        sb.append(")")
    }
    sb
        .append(" (")
        .append(col)
        .append(",")
        .append(row)
        .append(")")
    // §1.15: the dashed ring alone doesn't say the danger is conditional on landing here (as
    // opposed to merely flying over it on the way to a farther, unmarked hex) -- spell it out.
    if (hex.isAaThreat) sb.append(" — ").append(I18n.t("hud.status.location.aa_threat"))
    // Same reasoning as the AA line: the dashed ring and the truck cursor both say "only by
    // riding", but neither says what it costs you — arriving mounted (`rules/AutoMount`).
    if (hex.needsTransport) sb.append(" — ").append(I18n.t("hud.status.location.needs_transport"))
    byId("locmsg")?.innerHTML = sb.toString()
    MobileContextDock.updateLocation(sb.toString())
    // When a unit card replaces the passive context rail, keep the old transient strip as the
    // fallback. With no card open the persistent rail already shows this text, so duplicating it
    // in a toast would only cover more map.
    if (byId("osada-bottomzone")?.classList?.contains("bz--visible") == true) {
        MobileStatusStrip.show(sb.toString())
    }
}
