package org.osada.ui

import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.delCurrentUnit
import org.osada.model.deployPlayerUnit

/**
 * Stable reserve-unit selection plus the optional deployment hex chosen before the unit card.
 *
 * Supports both UX orders:
 *  1. choose a reserve unit, then click a deployment hex;
 *  2. click a deployment hex, then choose a reserve unit.
 */
internal object DeploymentSelection {
    private const val NO_SELECTION = -1

    fun selectedUnit(ui: UI): GameUnit? =
        selectedUnit(
            ui.game.scenario
                ?.map
                ?.currentPlayer,
        )

    // Guard-clause early returns read more clearly here than nesting or chaining ?.let; detekt's
    // default ReturnCount budget (2) is not a good fit for this style.
    //
    // Takes the player rather than the UI so MapRenderer can ask the same question while building
    // a frame (it has the GameMap, not the UI) — the deploy highlight must agree with what a click
    // would actually do, and it previously read `eqUserSel.deployunit` directly, missing the
    // formation-id path below entirely.
    @Suppress("ReturnCount")
    fun selectedUnit(player: Player?): GameUnit? {
        if (player == null) return null
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return null
        val formationId = eqUserSel.deployformation as? String
        val byFormation =
            formationId
                ?.takeIf(String::isNotBlank)
                ?.let { selectedId ->
                    player.getCoreUnitList().firstOrNull { unit ->
                        !unit.isDeployed && unit.formationId == selectedId
                    }
                }
        if (byFormation != null) return byFormation
        val index = eqUserSel.deployunit as? Int ?: NO_SELECTION
        return player.getCoreUnitList().getOrNull(index)?.takeUnless { it.isDeployed }
    }

    @Suppress("ReturnCount")
    fun selectUnit(
        player: Player,
        unit: GameUnit,
    ): Boolean {
        val index = player.getCoreUnitList().indexOf(unit)
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return false
        if (index < 0 || unit.isDeployed) return false
        eqUserSel.deployunit = index
        eqUserSel.deployformation = unit.formationId
        eqUserSel.userunit = NO_SELECTION
        return true
    }

    fun pendingTarget(): Cell? {
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return null
        val row = eqUserSel.deployrow as? Int ?: NO_SELECTION
        val col = eqUserSel.deploycol as? Int ?: NO_SELECTION
        return if (row >= 0 && col >= 0) Cell(row, col) else null
    }

    fun openForTarget(
        ui: UI,
        row: Int,
        col: Int,
    ) {
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return
        clearSelected(eqUserSel)
        eqUserSel.deployrow = row
        eqUserSel.deploycol = col
        ui.game.scenario
            ?.map
            ?.delCurrentUnit()
        ui.buildUnitContext(null)
        makeVisible("container-unitlist")
        byId("equipment")?.style?.display = "grid"
        EquipmentWindowBuilder.setEquipmentMode("reserve")
    }

    fun deployPending(ui: UI): Boolean {
        val target = pendingTarget() ?: return false
        return deploySelected(ui, target.row, target.col)
    }

    @Suppress("ReturnCount")
    fun deploySelected(
        ui: UI,
        row: Int,
        col: Int,
    ): Boolean {
        val map = ui.game.scenario?.map ?: return false
        val player = map.currentPlayer ?: return false
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return false
        val unit = selectedUnit(ui) ?: return false
        if (!map.deployPlayerUnit(player, unit, row, col)) return false

        val unitClass = unit.unitData(true).uclass
        clearSelected(eqUserSel)
        clearPending(eqUserSel)
        map.delCurrentUnit()
        ui.buildUnitContext(null)
        ui.render.cacheImages { ui.render.render(row, col, 1) }
        ui.updateEquipmentWindow(unitClass)
        ui.updateStatusBar()

        // After a successful placement return focus to the map. The next unit may be selected
        // first, or the next deployment hex may be clicked first.
        hideEquipmentWindow()
        makeHidden("container-unitlist")
        if (!player.hasUndeployedUnits()) {
            byId("buy")?.let { toggleButton(it, false) }
        }
        return true
    }

    fun clearSelected() {
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return
        clearSelected(eqUserSel)
    }

    fun reset() {
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return
        clearSelected(eqUserSel)
        clearPending(eqUserSel)
    }

    private fun clearSelected(eqUserSel: dynamic) {
        eqUserSel.deployunit = NO_SELECTION
        eqUserSel.deployformation = null
    }

    private fun clearPending(eqUserSel: dynamic) {
        eqUserSel.deployrow = NO_SELECTION
        eqUserSel.deploycol = NO_SELECTION
    }
}
