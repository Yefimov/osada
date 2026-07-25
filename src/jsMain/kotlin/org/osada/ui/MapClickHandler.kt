package org.osada.ui

import org.osada.TerrainType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.delCurrentUnit
import org.osada.model.getAttackableUnit
import org.osada.model.getPlayer
import org.osada.rules.AttackEligibility
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.uiSettings

/**
 * [MapInputController]'s map-click routing: right-click inspect, left-click select/move/attack/
 * deploy dispatch. Split out purely to keep [MapInputController] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal class MapClickHandler(
    private val ui: UI,
) {
    /** The unit under the cursor, or null if it's an enemy hidden by fog-of-war (never surfaced
     *  to click handling — same visibility rule as the map tooltips). */
    fun resolveVisibleUnit(
        hex: Hex,
        currentPlayerSide: Int,
    ): GameUnit? {
        val unit = hex.getUnit(uiSettings.airMode) ?: return null
        val isHiddenEnemy =
            !hex.isSpotted(currentPlayerSide) && !unit.tempSpotted && unit.player?.side != currentPlayerSide
        return if (isHiddenEnemy) null else unit
    }

    fun handleRightClick(
        map: GameMap,
        unit: GameUnit?,
    ) {
        if (unit == null) {
            deselectCurrentUnit()
            return
        }
        if (unit.player?.id == map.currentPlayer?.id) {
            if (!isVisible("unit-info")) {
                makeVisible("unit-info")
                uiSettings.unitInfoVisibility = true
                byId("inspectunit")?.let { toggleButton(it, true) }
            }
            ui.showUnitInfo(unit)
        } else {
            // Foreign unit inspected: the enemy card, not the player card (Task 3).
            ui.showEnemyCard(unit)
        }
    }

    // TODO(detekt): CyclomaticComplexMethod (22) — the worst offender in this file; dispatches
    // every left-click-with-a-selected-unit outcome (move/attack/capture/deploy/mount/etc).
    // Deliberately deferred rather than rushed — this is a map-input hot path.
    @Suppress("CyclomaticComplexMethod")
    fun handleLeftClickWithUnit(
        map: GameMap,
        cell: Cell,
        hex: Hex,
        unit: GameUnit,
    ): Boolean {
        if (unit.player?.id == map.currentPlayer?.id) {
            if (uiSettings.unitInfoVisibility) {
                makeVisible("unit-info")
                byId("inspectunit")?.let { toggleButton(it, true) }
            }
            ui.showUnitInfo(unit)
        }

        val currentUnit = map.currentUnit
        val selectedReserve = DeploymentSelection.selectedUnit(ui)
        val selectedDeployLayerFree =
            selectedReserve != null &&
                if (GameRules.isAir(selectedReserve)) {
                    hex.airunit == null
                } else {
                    hex.unit == null
                }
        val canDeploySelected =
            currentUnit == null &&
                uiSettings.deployMode &&
                selectedReserve != null &&
                selectedDeployLayerFree &&
                isValidDeployTarget(hex, map, map.currentPlayer?.side ?: 0)

        logIfEnemyUnattackable(map, hex, currentUnit, unit)
        return when {
            currentUnit != null && hex.isAttackSel && !currentUnit.hasFired ->
                tryAttackAt(cell.row, cell.col)
            currentUnit != null && hex.isMoveSel && !currentUnit.hasMoved -> {
                ui.uiUnitMove(currentUnit, cell.row, cell.col)
                true
            }
            canDeploySelected ->
                DeploymentSelection.deploySelected(ui, cell.row, cell.col)
            currentUnit != null && currentUnit.id == unit.id -> {
                deselectCurrentUnit()
                false
            }
            unit.player?.id == map.currentPlayer?.id ->
                selectOtherUnit(cell.row, cell.col)
            else -> false
        }
    }

    fun handleLeftClickEmpty(
        map: GameMap,
        cell: Cell,
        hex: Hex,
        currentPlayerSide: Int,
    ): Boolean {
        val currentUnit = map.currentUnit
        return when {
            // Movement must win over deployment. In particular, aircraft may occupy the air layer
            // above a hex containing an enemy ground unit.
            hex.isMoveSel && currentUnit != null && !currentUnit.hasMoved -> {
                ui.uiUnitMove(currentUnit, cell.row, cell.col)
                true
            }
            uiSettings.deployMode && isValidDeployTarget(hex, map, currentPlayerSide) -> {
                if (DeploymentSelection.selectedUnit(ui) != null) {
                    DeploymentSelection.deploySelected(ui, cell.row, cell.col)
                } else {
                    DeploymentSelection.openForTarget(ui, cell.row, cell.col)
                    true
                }
            }
            else -> {
                // Same as the re-click-deselect above: never clear the Inspect pin here.
                deselectCurrentUnit()
                false
            }
        }
    }

    private fun isValidDeployTarget(
        hex: Hex,
        map: GameMap,
        currentPlayerSide: Int,
    ): Boolean {
        val onDeploymentHex = hex.isDeployment != -1 && map.getPlayer(hex.isDeployment).side == currentPlayerSide
        // Aircraft can always be based on an airfield, even outside the deploy zone (OG)
        // — but only a FRIENDLY one. hex.owner is a player id, not a side (getPlayer(-1)
        // falls back to player 0, so an unowned/-1 airfield must be excluded explicitly
        // or it silently read as "belongs to player 0"), and this clause never checked it
        // at all: any airfield anywhere, including the enemy's, was a legal deploy target.
        val onFriendlyAirfield =
            hex.terrain == TerrainType.AIRFIELD.value &&
                selectedDeployUnitIsAir() &&
                hex.owner != -1 &&
                map.getPlayer(hex.owner).side == currentPlayerSide
        return onDeploymentHex || onFriendlyAirfield
    }

    private fun selectOtherUnit(
        row: Int,
        col: Int,
    ): Boolean {
        val map = ui.game.scenario?.map
        val hex = map?.map?.get(row)?.get(col)
        val primary = hex?.getUnit(uiSettings.airMode)
        val secondary = hex?.getUnit(!uiSettings.airMode)
        val ownUnit =
            when {
                map == null || hex == null -> null
                primary != null && primary.player?.id == map.currentPlayer?.id -> primary
                secondary != null && secondary.player?.id == map.currentPlayer?.id -> secondary
                else -> null
            }
        if (map == null || ownUnit == null) return false
        DeploymentSelection.reset()
        ui.removeUnitToolTip(ownUnit.id)
        val eqUserSel = byId("eqUserSel")?.asDynamic()
        eqUserSel?.userunit = ownUnit.id
        eqUserSel?.deployunit = -1
        ui.updateEquipmentWindow(ownUnit.unitData(true).uclass)
        return ui.uiUnitSelect(ownUnit)
    }

    private fun deselectCurrentUnit() {
        val map = ui.game.scenario?.map ?: return
        val unit = map.currentUnit
        if (unit == null) {
            ui.buildUnitContext(null)
            return
        }
        finishDeselect(map, unit)
    }

    private fun finishDeselect(
        map: GameMap,
        unit: GameUnit,
    ) {
        val pos = unit.getPos() ?: return
        val radius = getUnitRenderRadius(unit)
        map.delCurrentUnit()
        ui.buildUnitContext(null)
        ui.render.render(pos.row, pos.col, radius)
    }

    private fun tryAttackAt(
        row: Int,
        col: Int,
    ): Boolean {
        val map = ui.game.scenario?.map
        val currentUnit = map?.currentUnit
        val hex = map?.map?.get(row)?.get(col)
        val target =
            if (currentUnit != null && hex != null) hex.getAttackableUnit(currentUnit, uiSettings.airMode) else null
        if (currentUnit == null || target == null) return false
        ui.uiUnitAttack(currentUnit, target)
        return true
    }

    /** True if the unit currently picked in the deploy/equipment window is an air unit (so it may
     *  be placed on an airfield outside the deploy zone). */
    private fun selectedDeployUnitIsAir(): Boolean = DeploymentSelection.selectedUnit(ui)?.let(GameRules::isAir) == true
}

/** DIAGNOSTIC (DEFERRED: T-34/ZP-40 "can't attack an adjacent enemy"). When the player clicks an
 *  enemy unit while one of their own is selected but no attack cursor is offered on that hex, print
 *  the single gate that blocked it — an eligibility predicate (via
 *  [AttackEligibility.attackBlockReason]) or, if the unit is actually eligible, the spotting state
 *  that kept it out of the attack set. One line per click, so no per-frame spam. Top-level (not a
 *  method) to keep [MapClickHandler] within the detekt function-per-class limit. */
private fun logIfEnemyUnattackable(
    map: GameMap,
    hex: Hex,
    currentUnit: GameUnit?,
    clicked: GameUnit,
) {
    val side = map.currentPlayer?.side ?: return
    if (currentUnit == null || isNotAFailedAttack(hex, clicked, side)) return
    val reason =
        AttackEligibility.attackBlockReason(currentUnit, clicked)
            ?: "eligible but not in attack set — spotted=${hex.isSpotted(side)} tempSpotted=${clicked.tempSpotted}"
    logAttackBlockReason(clicked, currentUnit, reason)
}

// A move-highlighted hex is a movement destination, not a failed attack. This is essential for
// aircraft moving over a ground unit on the other occupancy layer.
private fun isNotAFailedAttack(
    hex: Hex,
    clicked: GameUnit,
    side: Int,
): Boolean = clicked.player?.side == side || hex.isAttackSel || hex.isMoveSel

private fun logAttackBlockReason(
    clicked: GameUnit,
    currentUnit: GameUnit,
    reason: String,
) {
    console.log(
        "[osada] no attack cursor on ${clicked.unitData().name} (eqid=${clicked.getEqid()}) from " +
            "${currentUnit.unitData().name}: $reason",
    )
}
