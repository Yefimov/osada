package org.osada.ui

import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.disembarkUnit
import org.osada.model.embarkUnit
import org.osada.model.mountUnit
import org.osada.model.reinforceUnit
import org.osada.model.resupplyUnit
import org.osada.model.undoLastMove
import org.osada.model.unmountUnit
import org.osada.rules.SupplyRules

/**
 * [UnitInfoPanel]'s per-unit action context menu action execution (Mount/Embark/Resupply/
 * Reinforce/Undo/Sleep). Split out purely to keep [UnitInfoPanel] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere. The button
 * strip itself lives in [UnitContextButtons].
 */
internal class UnitContextMenu(
    private val ui: UI,
) {
    private val contextButtons = UnitContextButtons(ui) { action, unit -> executeUnitContext(action, unit) }

    fun buildUnitContext(unit: GameUnit?) {
        clearTag("unit-context")
        val map = ui.game.scenario?.map
        val currentPlayer = map?.currentPlayer
        if (unit == null || map == null || currentPlayer == null) {
            hideUnitContext()
            return
        }
        if (unit.player?.id != currentPlayer.id) {
            hideUnitContext()
            return
        }
        val count = contextButtons.build(unit, map, currentPlayer)
        if (count > 0) makeVisible("unit-context") else makeHidden("unit-context")
    }

    /** Nothing of the player's own is selected — bottom zone fully hidden (spec), unless an
     *  enemy-alone inspection is what's currently showing (that call happens AFTER this one in
     *  the click handlers, so it correctly overrides this hide). */
    private fun hideUnitContext() {
        makeHidden("unit-context")
        BottomZoneBuilder.setState("hidden")
        AttackRingBuilder.clear()
    }

    private fun executeUnitContext(
        action: String,
        unit: GameUnit,
    ) {
        val map = ui.game.scenario?.map ?: return
        val radius = getUnitRenderRadius(unit)
        val pos = unit.getPos() ?: return
        performAction(action, map, unit, pos)
        buildUnitContext(unit)
        ui.showUnitInfo(unit)
        ui.render.render(pos.row, pos.col, radius)
        if (movedUnitAction(action)) rerenderAtNewPosition(unit, pos)
    }

    private fun movedUnitAction(action: String): Boolean = action == "mount" || action == "embark" || action == "undo"

    private fun performAction(
        action: String,
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
    ) {
        when (action) {
            "mount" -> if (unit.isMounted) map.unmountUnit(unit) else map.mountUnit(unit)
            "embark" -> if (unit.carrier > 0) map.disembarkUnit(unit) else map.embarkUnit(unit)
            "resupply" -> performResupply(map, unit, pos)
            "reinforce", "overstrength" -> performReinforce(map, unit, pos, action == "overstrength")
            "undo" -> map.undoLastMove()
            "sleep" -> ui.toggleUnitSleep(unit)
        }
    }

    /** These three all can move the unit to a DIFFERENT hex than `pos` (undo teleports it back to
     *  its pre-move origin) — the render already done, centered on the pre-action position, only
     *  partially redraws around the unit's NEW position, chopping its move-range overlay at the
     *  edge of that box. A second render centered on the actual new position fixes it, same as
     *  mount/embark already did. */
    private fun rerenderAtNewPosition(
        unit: GameUnit,
        pos: Cell,
    ) {
        val newPos = unit.getPos() ?: pos
        val newRadius = getUnitRenderRadius(unit)
        ui.render.render(newPos.row, newPos.col, newRadius)
    }

    private fun performResupply(
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
    ) {
        val supply = map.resupplyUnit(unit)
        val parts = mutableListOf<String>()
        if (supply.ammo > 0) parts.add("+${supply.ammo} ammo")
        if (supply.fuel > 0) parts.add("+${supply.fuel} fuel")
        val context = SupplyRules.getSupplyContext(map, unit)
        val message =
            if (parts.isEmpty()) {
                "Can't resupply"
            } else {
                "${parts.joinToString(" ")} · ${context.label} (${context.efficiencyPercent}%)"
            }
        ui.showAlert(pos.row, pos.col, message, true)
    }

    private fun performReinforce(
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
        overStrength: Boolean,
    ) {
        val result = map.reinforceUnit(unit, overStrength)
        val parts = mutableListOf<String>()
        val strength = result.strength as? Int ?: 0
        val ammo = result.ammo as? Int ?: 0
        val fuel = result.fuel as? Int ?: 0
        if (strength > 0) parts.add("+$strength units")
        if (ammo > 0) parts.add("+$ammo ammo")
        if (fuel > 0) parts.add("+$fuel fuel")
        val message =
            if (parts.isEmpty()) {
                if (overStrength) "No overstrength" else "Can't reinforce"
            } else {
                val context = SupplyRules.getSupplyContext(map, unit)
                "${parts.joinToString(" ")} · ${context.label} (${context.efficiencyPercent}%)"
            }
        ui.showAlert(pos.row, pos.col, message, true)
    }
}
