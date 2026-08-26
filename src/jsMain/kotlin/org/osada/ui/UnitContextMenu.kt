package org.osada.ui

import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.EngineeringActionResult
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.MineActionResult
import org.osada.model.beginEngineering
import org.osada.model.clearMinefield
import org.osada.model.disembarkUnit
import org.osada.model.embarkUnit
import org.osada.model.layMinefield
import org.osada.model.mountUnit
import org.osada.model.reinforceUnit
import org.osada.model.resupplyUnit
import org.osada.model.undoLastMove
import org.osada.model.unmountUnit
import org.osada.rules.Engineering
import org.osada.rules.SupplyContextRules

/**
 * [UnitInfoPanel]'s per-unit action context menu action execution (Mount/Embark/Resupply/
 * Reinforce/Undo/Sleep). Split out purely to keep [UnitInfoPanel] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere. The button
 * strip itself lives in [UnitContextButtons].
 */
internal class UnitContextMenu(
    // Internal, not private: `MapClickBarrage.kt` opens the targeting mode from here.
    internal val ui: UI,
) {
    private companion object {
        /** The six Build-and-Repair chip ids, matching `UnitActionId`'s own strings. Kept as a set
         *  so the `when` above stays one branch rather than six identical ones. */
        val ENGINEERING_ACTION_IDS =
            setOf("build_bridge", "build_fortification", "build_airfield", "build_port", "repair", "demolish")
    }

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
        val map = ui.game.scenario?.map
        val pos = unit.getPos()
        if (map == null || pos == null) return
        val radius = getUnitRenderRadius(unit)
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
            "lay_mines" -> performMineAction(map.layMinefield(unit), map, unit, pos)
            "barrage" -> toggleBarrageTargeting(map, unit, pos)
            "clear_mines" -> performMineAction(map.clearMinefield(unit), map, unit, pos)
            in ENGINEERING_ACTION_IDS -> performEngineering(action, map, unit, pos)
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
        if (supply.ammo > 0) parts.add(I18n.t("unit_info.action.gain.ammo", mapOf("value" to supply.ammo)))
        if (supply.fuel > 0) parts.add(I18n.t("unit_info.action.gain.fuel", mapOf("value" to supply.fuel)))
        val message =
            if (parts.isEmpty()) {
                I18n.t("unit_info.action.resupply.blocked")
            } else {
                gainAlert(map, unit, parts)
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
        if (strength > 0) parts.add(I18n.t("unit_info.action.gain.strength", mapOf("value" to strength)))
        if (ammo > 0) parts.add(I18n.t("unit_info.action.gain.ammo", mapOf("value" to ammo)))
        if (fuel > 0) parts.add(I18n.t("unit_info.action.gain.fuel", mapOf("value" to fuel)))
        val message =
            if (parts.isEmpty()) {
                I18n.t(
                    if (overStrength) "unit_info.action.overstrength.blocked" else "unit_info.action.reinforce.blocked",
                )
            } else {
                gainAlert(map, unit, parts)
            }
        ui.showAlert(pos.row, pos.col, message, true)
    }

    /**
     * Reports what a minefield command actually did. Laying and clearing both change the MAP rather
     * than the unit, so without a message the player would have to infer the outcome from an overlay
     * they may not be looking at -- and a failed clearing attempt would be indistinguishable from a
     * misclick.
     */
    private fun performMineAction(
        result: MineActionResult,
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
    ) {
        val key =
            when (result) {
                MineActionResult.LAID -> "unit_info.action.lay_mines.done"
                MineActionResult.CLEARED -> "unit_info.action.clear_mines.done"
                MineActionResult.FAILED_ATTEMPT -> "unit_info.action.clear_mines.failed"
                MineActionResult.NOT_ALLOWED -> "unit_info.action.lay_mines.blocked"
            }
        ui.showAlert(pos.row, pos.col, I18n.t(key), true)
        // The minefield overlay is a per-hex fact, so the surrounding cells have to be repainted --
        // the caller only re-renders around the unit itself.
        map.map?.let { ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit)) }
    }

    /**
     * Starts the engineering job the chip names (OG 9.3).
     *
     * The chip id maps to an [org.osada.rules.EngineeringWork] the same way
     * `UnitActionAvailability.engineering` resolves it, so the order carried out is the order the
     * chip offered -- `DEMOLISH` re-asks the rules layer which of the two demolitions this hex
     * allows rather than deciding again here.
     */
    private fun performEngineering(
        action: String,
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
    ) {
        val available = Engineering.availableWork(unit)
        val work =
            if (action == "demolish") {
                available.firstOrNull { it.demolition }
            } else {
                available.firstOrNull { !it.demolition && it.name.lowercase() == action.removePrefix("build_") }
            }
        if (work == null) {
            ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.engineering.blocked"), true)
            return
        }
        val result = map.beginEngineering(unit, work)
        val key =
            when (result) {
                EngineeringActionResult.DEMOLISHED -> "unit_info.action.demolish.done"
                EngineeringActionResult.STARTED -> "unit_info.action.build.started"
                EngineeringActionResult.NOT_ALLOWED -> "unit_info.action.engineering.blocked"
            }
        ui.showAlert(pos.row, pos.col, I18n.t(key, mapOf("turns" to work.turns)), true)
        // Terrain and roads are per-hex facts the neighbours are drawn against, so the surrounding
        // cells are repainted -- the caller only re-renders around the unit itself.
        map.map?.let { ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit)) }
    }

    /** The committed gain plus the very context that produced it -- read from the same
     *  [SupplyContextRules.getSupplyContext] the action tooltip showed, so the two can never disagree. */
    private fun gainAlert(
        map: GameMap,
        unit: GameUnit,
        parts: List<String>,
    ): String {
        val context = SupplyContextRules.getSupplyContext(map, unit)
        return I18n.t(
            "unit_info.action.gain.summary",
            mapOf(
                "gains" to parts.joinToString(" "),
                "context" to GameText.supplyContextSummary(context),
                "efficiency" to context.efficiencyPercent,
            ),
        )
    }
}
