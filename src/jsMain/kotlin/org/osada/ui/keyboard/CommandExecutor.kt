package org.osada.ui.keyboard

import org.osada.ui.EquipmentWindowBuilder
import org.osada.ui.MapZoom
import org.osada.ui.UI
import org.osada.ui.UICombatLog
import org.osada.ui.addSmallToolTips
import org.osada.ui.byId
import org.osada.ui.cycleReadyUnit
import org.osada.ui.isVisible
import org.osada.ui.mainMenuButton
import org.osada.ui.removeAllSmallToolTips
import org.osada.uiSettings

/**
 * Turns a resolved [GameCommand] id into the same call the corresponding on-screen control makes.
 *
 * No rule lives here (`docs/design/keyboard-shortcuts-and-help.md` §4): unit commands go through
 * [UnitCommandBridge] and therefore through the action chip's own availability result, and every
 * map/panel command calls the existing `mainMenuButton` / `MapZoom` / `UICombatLog` entry point.
 *
 * Returns whether the key was consumed. `false` leaves the browser default alone -- the design
 * forbids preventing a default for a command that did nothing (§5).
 */
internal object CommandExecutor {
    /** One arrow-key press scrolls a fixed slice of the map viewport. */
    private const val PAN_STEP_PX = 80.0

    fun run(
        id: String,
        ui: UI,
    ): Boolean {
        CommandCatalog.unitActionFor[id]?.let { action ->
            return UnitCommandBridge.activate(action) != UnitCommandBridge.Outcome.ABSENT
        }
        return runNonUnit(id, ui)
    }

    private fun runNonUnit(
        id: String,
        ui: UI,
    ): Boolean =
        when (id) {
            CommandCatalog.NEXT_UNIT -> consume { ui.cycleReadyUnit(1) }
            CommandCatalog.PREV_UNIT -> consume { ui.cycleReadyUnit(-1) }
            CommandCatalog.AIR_MODE -> consume { ui.mainMenuButton("air") }
            CommandCatalog.HEX_GRID -> consume { ui.mainMenuButton("hex") }
            CommandCatalog.STRATEGIC_MAP -> consume { ui.mainMenuButton("zoom") }
            CommandCatalog.MAP_LABELS -> consume { toggleMapLabels(ui) }
            CommandCatalog.ZOOM_IN -> consume { MapZoom.stepIn() }
            CommandCatalog.ZOOM_OUT -> consume { MapZoom.stepOut() }
            CommandCatalog.EQUIPMENT -> consume { ui.mainMenuButton("buy") }
            CommandCatalog.INSPECTOR -> consume { ui.mainMenuButton("inspectunit") }
            CommandCatalog.RESERVES -> consume { toggleReserves(ui) }
            CommandCatalog.TURN_REPORT -> consume { UICombatLog.toggleCombatLog(fromStatusBar = true) }
            else -> pan(id)
        }

    private fun pan(id: String): Boolean {
        val game = byId("game")?.asDynamic() ?: return false
        val dx =
            when (id) {
                CommandCatalog.PAN_LEFT -> -PAN_STEP_PX
                CommandCatalog.PAN_RIGHT -> PAN_STEP_PX
                else -> 0.0
            }
        val dy =
            when (id) {
                CommandCatalog.PAN_UP -> -PAN_STEP_PX
                CommandCatalog.PAN_DOWN -> PAN_STEP_PX
                else -> 0.0
            }
        val handled = dx != 0.0 || dy != 0.0
        if (handled) {
            game.scrollLeft = scroll(game.scrollLeft) + dx
            game.scrollTop = scroll(game.scrollTop) + dy
        }
        return handled
    }

    private fun scroll(value: dynamic): Double = (value as? Number)?.toDouble() ?: 0.0

    /**
     * The optional-objective hex labels. Same flag and same refresh pair the Settings checkbox uses,
     * and the checkbox's `checked` class is kept in sync so the two surfaces cannot disagree.
     */
    private fun toggleMapLabels(ui: UI) {
        val next = !uiSettings.showDetailInfoToolTips
        uiSettings.setFlag("showDetailInfoToolTips", next)
        byId("showDetailInfoToolTips")?.classList?.toggle("checked", next)
        ui.removeAllSmallToolTips()
        ui.addSmallToolTips()
        ui.render.render()
    }

    /** Reserves is the buy/deploy window opened on its Reserve tab -- the same window `B` toggles,
     *  so an already-open window closes rather than silently switching tabs under the player. */
    private fun toggleReserves(ui: UI) {
        val wasOpen = isVisible("equipment")
        ui.mainMenuButton("buy")
        if (!wasOpen && isVisible("equipment")) EquipmentWindowBuilder.setEquipmentMode("reserve")
    }

    private inline fun consume(action: () -> Unit): Boolean {
        action()
        return true
    }
}
