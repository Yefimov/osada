package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.clearHangarLaunchTargeting
import org.osada.model.landInHangar
import org.osada.model.launchFromHangar
import org.osada.rules.HangarStowage
import org.osada.uiSettings

/**
 * The map side of the hangar (2026-10-07): the two unit chips, the take-off hex click, and placing a
 * reserve aircraft straight into a carrier or airbase at deployment. The window itself is
 * [HangarDialog]. Beside [UnitContextMenu] and [MapClickHandler] rather than inside them, because
 * both are at their function budget -- the same reason `MapClickBarrage.kt` exists.
 */
internal val HANGAR_ACTION_IDS = setOf("hangar_land", "hangar", "cover_enter", "cover", "aboard")

internal fun UnitContextMenu.performHangarAction(
    action: String,
    map: GameMap,
    unit: GameUnit,
    pos: Cell,
) {
    if (action == "hangar" || action == "cover" || action == "aboard") {
        HangarDialog.open(ui, map, unit)
        return
    }
    val cover = action == "cover_enter"
    val container = if (cover) HangarStowage.coverContainer(map, unit) else HangarStowage.landingContainer(map, unit)
    val name = unit.customName ?: unit.unitData(true).name
    if (container == null || !map.landInHangar(unit, container)) return
    ui.removeUnitToolTip(unit.id)
    ui.buildUnitContext(null)
    ui.showUnitInfo(null)
    ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit))
    val home = container.getPos() ?: pos
    val text =
        I18n.t(
            if (cover) "garrison.entered" else "hangar.landed",
            mapOf("unit" to name, "container" to container.unitData(true).name),
        )
    ui.showAlert(home.row, home.col, text, true)
    HudLog.addAt(home.row, home.col, text)
}

/**
 * A click while a take-off hex is being chosen. An offered hex launches there; anything else closes
 * the choice and leaves the formation in its hangar -- the same rule the Barrage targeting mode keeps.
 */
internal fun MapClickHandler.resolveHangarLaunchClick(
    map: GameMap,
    cell: Cell,
    hex: Hex,
): Boolean {
    val launch = map.hangarLaunch
    if (launch == null || !hex.isMoveSel) {
        map.clearHangarLaunchTargeting()
        ui.render.render(cell.row, cell.col, 2)
        return false
    }
    val launched = map.launchFromHangar(launch.container, launch.passenger, hex)
    if (launched) {
        val passenger = launch.passenger
        ui.render.cacheImages {
            ui.showUnitInfo(passenger)
            ui.buildUnitContext(passenger)
            ui.render.render(cell.row, cell.col, getUnitRenderRadius(passenger))
        }
    }
    return launched
}

/** Whether the reserve aircraft selected for deployment could go into [hex]'s container. */
internal fun canStowSelectedReserve(
    ui: UI,
    map: GameMap,
    hex: Hex,
): Boolean {
    val reserve = DeploymentSelection.selectedUnit(ui)
    val container = hex.unit
    val side = map.currentPlayer?.side
    return reserve != null &&
        container != null &&
        side != null &&
        HangarStowage.canStowAtDeployment(reserve, container, side)
}

/** Places the selected reserve aircraft into [hex]'s carrier or airbase. */
internal fun stowSelectedReserve(
    ui: UI,
    map: GameMap,
    hex: Hex,
): Boolean {
    val reserve = DeploymentSelection.selectedUnit(ui)
    val container = hex.unit
    if (reserve == null || container == null || !HangarStowage.stowAtDeployment(map, reserve, container)) return false
    DeploymentSelection.reset()
    val pos = hex.getPos()
    ui.updateEquipmentWindow(reserve.unitData(true).uclass)
    ui.updateStatusBar()
    ui.render.render(pos.row, pos.col, 1)
    ui.showAlert(
        pos.row,
        pos.col,
        I18n.t(
            "hangar.stowed",
            mapOf("unit" to reserve.unitData(true).name, "container" to container.unitData(true).name),
        ),
        true,
    )
    ui.game.state?.save()
    hideEquipmentWindow()
    makeHidden("container-unitlist")
    return true
}

/**
 * The clicks a mode takes over before ordinary selection, movement and deployment see them: the
 * Barrage target, the take-off hex, and a reserve aircraft dropped onto a carrier or airbase. Null
 * when no mode wants the click.
 */
internal fun MapClickHandler.modalClick(
    map: GameMap,
    cell: Cell,
    hex: Hex,
): Boolean? =
    when {
        uiSettings.barrageMode -> resolveBarrageClick(map, cell, hex)
        map.hangarLaunch != null -> resolveHangarLaunchClick(map, cell, hex)
        // Before the ordinary placement, so an airbase standing on an airfield still takes it in.
        map.currentUnit == null && uiSettings.deployMode && canStowSelectedReserve(ui, map, hex) ->
            stowSelectedReserve(ui, map, hex)
        else -> null
    }
