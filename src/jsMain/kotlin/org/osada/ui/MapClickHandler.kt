package org.osada.ui

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.delCurrentUnit
import org.osada.model.deployPlayerUnit
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

    fun handleLeftClickWithUnit(
        map: GameMap,
        cell: Cell,
        hex: Hex,
        unit: GameUnit,
    ): Boolean {
        // #unit-info (the player card) is reserved for the player's OWN units going forward;
        // a foreign unit under the cursor is previewed via the enemy card only once the
        // click is resolved below (it may turn out to be an attack instead of an inspect).
        if (unit.player?.id == map.currentPlayer?.id) {
            if (uiSettings.unitInfoVisibility) {
                makeVisible("unit-info")
                byId("inspectunit")?.let { toggleButton(it, true) }
            }
            ui.showUnitInfo(unit)
        }
        val currentUnit = map.currentUnit
        logIfEnemyUnattackable(map, hex, currentUnit, unit)
        return when {
            currentUnit == null || uiSettings.deployMode -> selectOtherUnit(cell.row, cell.col)
            hex.isAttackSel && !currentUnit.hasFired -> {
                console.log(
                    "[osada] click: attack at ${cell.row},${cell.col} attacker=${currentUnit.id} " +
                        "hasFired=${currentUnit.hasFired}",
                )
                tryAttackAt(cell.row, cell.col)
                true
            }
            hex.isMoveSel -> {
                ui.uiUnitMove(currentUnit, cell.row, cell.col)
                true
            }
            currentUnit.id == unit.id -> {
                // Deselect only. Do NOT clear uiSettings.unitInfoVisibility here: that flag
                // is the explicit Inspect pin, and clearing it as a deselect side effect
                // meant showUnitInfo() early-returned forever after — selecting any unit
                // (e.g. right after an attack) left the bottom zone permanently hidden.
                deselectCurrentUnit()
                false
            }
            else -> selectOtherUnit(cell.row, cell.col)
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
            // A selected deployed unit ordered onto one of its reachable hexes MOVES, even when
            // that hex is a deploy hex and the player still has reserves (deployMode stays on for
            // the whole turn while any reserve is unplaced). Deploying is initiated from the
            // reserve tray, which clears the map selection (EquipmentUnitStrip) — so a live move
            // highlight here unambiguously means "move", and must win over the deploy branch.
            hex.isMoveSel && currentUnit != null && !currentUnit.hasMoved -> {
                ui.uiUnitMove(currentUnit, cell.row, cell.col)
                true
            }
            uiSettings.deployMode && isValidDeployTarget(hex, map, currentPlayerSide) -> {
                deployAt(cell.row, cell.col)
                true
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
    private fun selectedDeployUnitIsAir(): Boolean {
        val player =
            ui.game.scenario
                ?.map
                ?.currentPlayer
        val index = byId("eqUserSel")?.asDynamic()?.deployunit as? Int
        val unit =
            if (player != null && index != null && index >= 0) player.getCoreUnitList().getOrNull(index) else null
        return unit != null && GameRules.isAir(unit)
    }

    private fun deployAt(
        row: Int,
        col: Int,
    ) {
        val map = ui.game.scenario?.map
        val player = map?.currentPlayer
        if (map == null || player == null) return
        val eqUserSel = byId("eqUserSel")?.asDynamic()
        val index = eqUserSel?.deployunit as? Int ?: -1
        val unit = if (index >= 0) player.getCoreUnitList().getOrNull(index) else null
        if (unit == null || !map.deployPlayerUnit(player, unit, row, col)) return
        ui.render.cacheImages { ui.render.render(row, col, 1) }
        deselectCurrentUnit()
        val eqclass = eqUserSel?.eqclass as? Int ?: UnitClass.TANK.value
        ui.updateEquipmentWindow(eqclass)
        ui.updateStatusBar() // reserve pool shrank (unit now deployed) — refresh the Reserves badge
        if (!player.hasUndeployedUnits()) {
            makeHidden("container-unitlist")
            makeHidden("equipment")
            byId("buy")?.let { toggleButton(it, false) }
        } else {
            // More reserves to place: reopen the window on the Reserve tab so the deploy
            // loop (pick → place → pick) continues without hunting for the button.
            byId("equipment")?.style?.display = "grid"
            EquipmentWindowBuilder.setEquipmentMode("reserve")
        }
    }
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
    if (currentUnit == null || clicked.player?.side == side || hex.isAttackSel) return
    val reason =
        AttackEligibility.attackBlockReason(currentUnit, clicked)
            ?: "eligible but not in attack set — spotted=${hex.isSpotted(side)} tempSpotted=${clicked.tempSpotted}"
    console.log(
        "[osada] no attack cursor on ${clicked.unitData().name} (eqid=${clicked.getEqid()}) from " +
            "${currentUnit.unitData().name}: $reason",
    )
}
