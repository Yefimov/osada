package org.osada.ui

import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.EngineeringActionResult
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.MineActionResult
import org.osada.model.UndoInvalidation
import org.osada.model.beginEngineering
import org.osada.model.clearMinefield
import org.osada.model.delAttackSel
import org.osada.model.delCurrentUnit
import org.osada.model.delMoveSel
import org.osada.model.disembarkUnit
import org.osada.model.embarkUnit
import org.osada.model.layMinefield
import org.osada.model.mountUnit
import org.osada.model.reinforceUnit
import org.osada.model.resupplyUnit
import org.osada.model.undeployUnit
import org.osada.model.undoLastMove
import org.osada.model.unmountUnit
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.BeginEngineering
import org.osada.multiplayer.command.ClearMines
import org.osada.multiplayer.command.DetrainUnit
import org.osada.multiplayer.command.DisembarkUnit
import org.osada.multiplayer.command.DismissLeader
import org.osada.multiplayer.command.EmbarkUnit
import org.osada.multiplayer.command.EntrainUnit
import org.osada.multiplayer.command.GameCommand
import org.osada.multiplayer.command.GreenReinforceUnit
import org.osada.multiplayer.command.LayMines
import org.osada.multiplayer.command.MountUnit
import org.osada.multiplayer.command.ReinforceUnit
import org.osada.multiplayer.command.ResupplyUnit
import org.osada.multiplayer.command.UndeployUnit
import org.osada.multiplayer.command.UnmountUnit
import org.osada.rules.CautiousMove
import org.osada.rules.Engineering
import org.osada.rules.EngineeringWork
import org.osada.rules.GreenReplacements
import org.osada.rules.LeaderDismissal
import org.osada.rules.RailTransport
import org.osada.rules.SupplyContextRules
import org.osada.rules.SupplyRules
import org.osada.rules.turnsForBuilder

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
    internal companion object {
        /** The Build-and-Repair chip ids, matching `UnitActionId`'s own strings. Kept as a set
         *  so the `when` above stays one branch rather than one per chip.
         *
         *  `build_station` was missing from this set until 2026-09-20, which made that chip dead:
         *  the strip offered it, `performAction`'s `when` matched nothing, and the click did
         *  nothing at all. */
        val ENGINEERING_ACTION_IDS =
            setOf(
                "build_bridge",
                "build_fortification",
                "build_airfield",
                "build_port",
                "build_station",
                "repair",
                "demolish",
                "blow_rail",
            )
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
        val sentToHost = OsadaMultiplayer.active && routeToHost(action, unit, pos)
        if (!sentToHost && action == "withdraw") ui.withdrawToTray(map, unit, pos)
        // Landing takes the formation off the map, and the hangar window takes over from the strip.
        if (!sentToHost && action in HANGAR_ACTION_IDS) performHangarAction(action, map, unit, pos)
        if (sentToHost || action == "withdraw" || action in HANGAR_ACTION_IDS) return
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
            in SUPPLY_ACTION_IDS -> performSupplyAction(action, map, unit, pos)
            "lay_mines" -> performMineAction(map.layMinefield(unit), map, unit, pos)
            "barrage" -> toggleBarrageTargeting(map, unit, pos)
            "rail_move" -> performRailAction(map, unit)
            "clear_mines" -> performMineAction(map.clearMinefield(unit), map, unit, pos)
            in ENGINEERING_ACTION_IDS -> performEngineering(action, map, unit, pos)
            "undo" -> map.undoLastMove()
            "sleep" -> ui.toggleUnitSleep(unit)
            "cautious" -> CautiousMove.toggle(map, unit)
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

    internal fun performResupply(
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
                gainAlert(map, unit, parts, reinforcement = false)
            }
        ui.showAlert(pos.row, pos.col, message, true)
    }

    internal fun performReinforce(
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
                gainAlert(map, unit, parts, reinforcement = true)
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
        val work = engineeringWorkFor(action, unit)
        if (work == null) {
            ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.engineering.blocked"), true)
            return
        }
        // Read before the order: the active efile's duration, the same figure the chip promised
        // (`work.turns` is only the fallback for an efile that names none).
        val turns = work.turnsForBuilder(unit)
        val message =
            when (map.beginEngineering(unit, work)) {
                EngineeringActionResult.DEMOLISHED -> I18n.t("unit_info.action.demolish.done")
                EngineeringActionResult.STARTED -> I18n.plural("unit_info.action.build.started", turns)
                EngineeringActionResult.NOT_ALLOWED -> I18n.t("unit_info.action.engineering.blocked")
            }
        ui.showAlert(pos.row, pos.col, message, true)
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
        reinforcement: Boolean,
    ): String {
        val context = SupplyContextRules.getSupplyContext(map, unit, reinforcement)
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

/**
 * OG's cheap replacement (`rules/GreenReplacements`).
 *
 * Reported like the ordinary one plus the experience it cost — a player who is not told what the
 * discount bought would have no way to tell the two actions apart after the fact.
 *
 * At file level because [UnitContextMenu] is at its function budget.
 */
private fun UI.performGreenReinforce(
    map: GameMap,
    unit: GameUnit,
    pos: Cell,
) {
    val before = unit.experience
    val available = SupplyRules.getReinforceValue(map, unit, false)
    val gained = GreenReplacements.apply(unit, available)
    val message =
        if (gained <= 0) {
            I18n.t("unit_info.action.reinforce.blocked")
        } else {
            I18n.t(
                "unit_info.action.green_reinforce.done",
                mapOf("value" to gained, "from" to before, "to" to unit.experience),
            )
        }
    map.undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
    map.delMoveSel()
    map.delAttackSel()
    showAlert(pos.row, pos.col, message, true)
    showUnitInfo(unit)
}

/** The prestige-spending self-actions [performSupplyAction] owns. */
private val SUPPLY_ACTION_IDS =
    setOf("resupply", "reinforce", "overstrength", "green_reinforce", "dismiss_leader")

/**
 * The four ways a formation spends prestige on itself, dispatched together to keep
 * [UnitContextMenu.performAction] inside detekt's complexity budget as OG's green replacements
 * joined them. At file level because that class is at its function budget too.
 */
private fun UnitContextMenu.performSupplyAction(
    action: String,
    map: GameMap,
    unit: GameUnit,
    pos: Cell,
) {
    when (action) {
        "resupply" -> performResupply(map, unit, pos)
        "green_reinforce" -> ui.performGreenReinforce(map, unit, pos)
        "dismiss_leader" ->
            if (LeaderDismissal.dismiss(unit)) {
                ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.dismiss_leader.done"), true)
                ui.showUnitInfo(unit)
            }
        else -> performReinforce(map, unit, pos, action == "overstrength")
    }
}

/**
 * Lifts a formation placed during the opening deployment back into the reserve tray
 * (`UnitActionAvailability.withdraw` decides when that is offered).
 *
 * Handled apart from [UnitContextMenu]'s ordinary actions because the formation leaves the map:
 * there is no unit left to rebuild the strip or the info card for, so it finishes the way
 * `DeploymentSelection.deploySelected` does -- nothing selected, the tray recounted, and the
 * position saved, so a refresh does not put the formation back on the hex.
 */
internal fun UI.withdrawToTray(
    map: GameMap,
    unit: GameUnit,
    pos: Cell,
) {
    if (!map.undeployUnit(unit)) return
    map.delMoveSel()
    map.delAttackSel()
    map.delCurrentUnit()
    buildUnitContext(null)
    render.render(pos.row, pos.col, getUnitRenderRadius(unit))
    updateEquipmentWindow(unit.unitData(true).uclass)
    updateStatusBar()
    game.state?.save()
}

/**
 * The job an engineering chip names, resolved exactly as `UnitActionAvailability.engineering` offers
 * it -- shared by the local action and the order sent to a room's host.
 */
private fun engineeringWorkFor(
    action: String,
    unit: GameUnit,
): EngineeringWork? {
    val available = Engineering.availableWork(unit)
    return when (action) {
        // Whichever of OG's two demolitions this hex allows -- cutting rail is a chip of
        // its own, for the reason `UnitActionAvailability.ENGINEERING_ACTIONS` gives.
        "demolish" -> available.firstOrNull { it.demolition && it != EngineeringWork.BLOW_RAIL }
        "blow_rail" -> available.firstOrNull { it == EngineeringWork.BLOW_RAIL }
        else ->
            available.firstOrNull {
                !it.demolition && it.name.lowercase() == action.removePrefix("build_")
            }
    }
}

/**
 * In a room, a unit action is an ORDER for the host (`OsadaMultiplayer.submitOrder`), which applies
 * it and publishes the result; changing this client's copy instead was overwritten by the next
 * snapshot. Returns false for the chips that only open a local targeting mode (barrage)
 * or change nothing shared (sleep): those continue locally, and their CLICK becomes the order.
 *
 * Undo is refused in a room: the host keeps no per-client undo record, and a move another
 * participant has already seen cannot be taken back quietly.
 */
private fun UnitContextMenu.routeToHost(
    action: String,
    unit: GameUnit,
    pos: Cell,
): Boolean {
    val order = unitActionOrder(action, unit)
    when {
        action == "undo" -> ui.showAlert(pos.row, pos.col, I18n.t("unit_info.action.undo.multiplayer"), true)
        order != null -> OsadaMultiplayer.submitOrder(order)
    }
    return action == "undo" || order != null
}

/** The command a unit-action chip stands for, or null when the chip is local-only. */
@Suppress("CyclomaticComplexMethod") // one arm per chip; a table would only hide the mapping
private fun unitActionOrder(
    action: String,
    unit: GameUnit,
): GameCommand? {
    val id = unit.id
    val actor = unit.owner
    return when (action) {
        "mount" -> if (unit.isMounted) UnmountUnit(id, actor) else MountUnit(id, null, actor)
        "embark" -> if (unit.carrier > 0) DisembarkUnit(id, actor) else EmbarkUnit(id, actor)
        "rail_move" -> if (RailTransport.isEntrained(unit)) DetrainUnit(id, actor) else EntrainUnit(id, actor)
        "resupply" -> ResupplyUnit(id, actor)
        "reinforce" -> ReinforceUnit(id, null, actor)
        // The applier reads a non-null strength as "over strength", which is all this carries.
        "overstrength" -> ReinforceUnit(id, 1, actor)
        "green_reinforce" -> GreenReinforceUnit(id, actor)
        "dismiss_leader" -> DismissLeader(id, actor)
        "lay_mines" -> LayMines(id, actor)
        "clear_mines" -> ClearMines(id, actor)
        "withdraw" -> UndeployUnit(id, actor)
        in UnitContextMenu.ENGINEERING_ACTION_IDS ->
            engineeringWorkFor(action, unit)?.let {
                BeginEngineering(id, it.name, actor)
            }
        else -> null
    }
}
