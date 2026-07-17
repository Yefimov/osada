package org.osada.ui

import org.osada.PlayerType
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.terrainNames
import org.osada.uiSettings
import org.w3c.dom.events.MouseEvent

/**
 * Owns the cursor state and all map mouse-event logic: click dispatch (select / move / attack /
 * deploy), drag-to-scroll, cursor drawing, and location messages. Extracted from the former
 * [UI] god-class (SRP).
 */
internal class MapInputController(private val ui: UI) {

    private var curDown = false

    // Drag-to-scroll anchor: viewport (client) mouse position + #game's own scroll position at
    // drag start. clientX/Y deltas map 1:1 onto scrollLeft/scrollTop deltas regardless of zoom —
    // both are already expressed in #game's own rendered ("post-zoom") pixel space, so no zoom
    // division is needed here (unlike getClickPos, which converts INTO native canvas space for
    // hex hit-testing — a different destination space entirely).
    private var dragStartClientX = 0.0
    private var dragStartClientY = 0.0
    private var dragStartScrollLeft = 0.0
    private var dragStartScrollTop = 0.0

    private data class ClickInfo(val x: Double, val y: Double, val rightClick: Boolean)

    fun attachMapEventListeners() {
        val cursorCanvas = ui.render.getCursorCanvas() ?: return
        cursorCanvas.addEventListener("mousedown", { event: MouseEvent -> handleMapMouseDown(event) })
        cursorCanvas.addEventListener("mousemove", { event: MouseEvent -> handleMapMouseMove(event) })
        cursorCanvas.addEventListener("contextmenu", { event: MouseEvent -> event.preventDefault() })
        js("window").addEventListener("mouseup", { _: MouseEvent -> curDown = false })
        // Ctrl+wheel = map zoom toward the cursor. `{passive:false}` is required for
        // preventDefault() to actually suppress the browser's own page-zoom (Chrome defaults
        // wheel listeners to passive, which silently ignores preventDefault otherwise) — PM's
        // Ctrl+wheel WAS that unsuppressed browser zoom, which is why it enlarged the whole HUD;
        // capturing it here and scaling only the map wrapper is the fix.
        // cursorCanvas is ALREADY dynamic (Render.getCursorCanvas() returns dynamic) — calling
        // .asDynamic() on an already-dynamic receiver emits a broken REAL method call (no such
        // JS method), not a reinterpret-cast; it crashed UI construction outright at startup.
        cursorCanvas.addEventListener("wheel", { event: dynamic ->
            if (event.ctrlKey == true) {
                event.preventDefault()
                if (!uiSettings.strategicZoom) {
                    val deltaY = (event.deltaY as? Number)?.toDouble() ?: 0.0
                    val cx = (event.clientX as? Number)?.toDouble()
                    val cy = (event.clientY as? Number)?.toDouble()
                    if (deltaY < 0) {
                        MapZoom.stepIn(cx, cy)
                    } else if (deltaY > 0) {
                        MapZoom.stepOut(cx, cy)
                    }
                }
            }
        }, js("({passive: false})"))
    }

    private fun handleMapMouseDown(event: MouseEvent) {
        if (!ui.game.gameStarted || ui.game.gameEnded || ui.game.waitUIAnimation) return
        val map = ui.game.scenario?.map ?: return
        if (map.currentPlayer?.type != PlayerType.HUMAN_LOCAL) return

        val canvas = ui.render.getCursorCanvas() ?: return
        val info = getClickPos(canvas, event)

        if (!uiSettings.hasTouch) {
            curDown = true
            dragStartClientX = event.clientX.toDouble()
            dragStartClientY = event.clientY.toDouble()
            val gameDiv = byId("game")?.asDynamic()
            dragStartScrollLeft = (gameDiv?.scrollLeft as? Number)?.toDouble() ?: 0.0
            dragStartScrollTop = (gameDiv?.scrollTop as? Number)?.toDouble() ?: 0.0
        }

        val cell = ui.render.screenToCell(info.x.toInt(), info.y.toInt())
        if (cell.row < 0 || cell.row >= map.rows || cell.col < 0 || cell.col >= map.cols) return
        val hex = map.map?.get(cell.row)?.get(cell.col) ?: return

        if (uiSettings.strategicZoom) {
            ui.toggleStrategicZoom()
            ui.uiSetCellOnViewPort(cell)
            return
        }

        val currentPlayerSide = map.currentPlayer?.side ?: 0
        var unit = hex.getUnit(uiSettings.airMode)
        if (unit != null &&
            !hex.isSpotted(currentPlayerSide) &&
            !unit.tempSpotted &&
            unit.player?.side != currentPlayerSide
        ) {
            unit = null
        }

        if (info.rightClick) {
            if (unit != null) {
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
            } else {
                deselectCurrentUnit()
            }
            return
        }

        var handled = false
        if (unit != null) {
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
            when {
                currentUnit == null || uiSettings.deployMode -> handled = selectOtherUnit(cell.row, cell.col)
                hex.isAttackSel && !currentUnit.hasFired -> {
                    console.log(
                        "[osada] click: attack at ${cell.row},${cell.col} attacker=${currentUnit.id} hasFired=${currentUnit.hasFired}",
                    )
                    tryAttackAt(cell.row, cell.col)
                    handled = true
                }
                hex.isMoveSel -> {
                    ui.uiUnitMove(currentUnit, cell.row, cell.col)
                    handled = true
                }
                currentUnit.id == unit.id -> {
                    // Deselect only. Do NOT clear uiSettings.unitInfoVisibility here: that flag
                    // is the explicit Inspect pin, and clearing it as a deselect side effect
                    // meant showUnitInfo() early-returned forever after — selecting any unit
                    // (e.g. right after an attack) left the bottom zone permanently hidden.
                    deselectCurrentUnit()
                }
                else -> handled = selectOtherUnit(cell.row, cell.col)
            }
        } else {
            when {
                uiSettings.deployMode &&
                    (
                        (hex.isDeployment != -1 && map.getPlayer(hex.isDeployment).side == currentPlayerSide) ||
                            // Aircraft can always be based on an airfield, even outside the deploy zone (OG)
                            // — but only a FRIENDLY one. hex.owner is a player id, not a side (getPlayer(-1)
                            // falls back to player 0, so an unowned/-1 airfield must be excluded explicitly
                            // or it silently read as "belongs to player 0"), and this clause never checked it
                            // at all: any airfield anywhere, including the enemy's, was a legal deploy target.
                            (
                                hex.terrain == TerrainType.AIRFIELD.value &&
                                    selectedDeployUnitIsAir() &&
                                    hex.owner != -1 &&
                                    map.getPlayer(hex.owner).side == currentPlayerSide
                                )
                        ) -> {
                    deployAt(cell.row, cell.col)
                    handled = true
                }
                hex.isMoveSel && map.currentUnit != null && !map.currentUnit!!.hasMoved -> {
                    ui.uiUnitMove(map.currentUnit!!, cell.row, cell.col)
                    handled = true
                }
                else -> {
                    // Same as the re-click-deselect above: never clear the Inspect pin here.
                    deselectCurrentUnit()
                }
            }
        }

        if (!handled && uiSettings.hasTouch) updateLocationMessage(cell.row, cell.col)

        val currentUnit = map.currentUnit
        val isAir = currentUnit?.let { GameRules.isAir(it) } ?: false
        uiSettings.airMode = isAir
        byId("air")?.let { toggleButton(it, isAir) }
        // A pure click on a spotted foreign (enemy/allied) unit is an inspection: keep its info on
        // screen instead of reverting to the selected own unit. Attacks/moves are `handled`, so those
        // still refresh the acting own unit. Unconditional (not gated on currentUnit == null): it
        // used to only show the enemy card when NOTHING own was selected, so clicking a
        // non-attackable enemy while an own unit was still selected (easy to have happen without
        // noticing — a unit stays "selected" until explicitly deselected) silently did nothing,
        // which read as "the enemy card doesn't always appear." Right-click already showed it
        // unconditionally (see the rightClick branch above); this makes left-click consistent
        // with that instead of a hidden special case.
        if (!handled && unit != null && unit.player?.id != map.currentPlayer?.id) {
            ui.showEnemyCard(unit)
        } else {
            currentUnit?.let { ui.showUnitInfo(it) }
        }
        currentUnit?.getPos()?.let { updateLocationMessage(it.row, it.col) }
            ?: updateLocationMessage(cell.row, cell.col)

        event.preventDefault()
    }

    private fun handleMapMouseMove(event: MouseEvent) {
        val canvas = ui.render.getCursorCanvas()
        if (canvas != null) {
            val info = getClickPos(canvas, event)
            val cell = ui.render.screenToCell(info.x.toInt(), info.y.toInt())
            if (ui.game.scenario?.map?.currentUnit != null && uiSettings.strategicZoom != true) {
                ui.render.drawCursor(cell)
            }
            updateLocationMessage(cell.row, cell.col)
            // Sidebar "Under Cursor" inspector (cell-change guarded inside, so cheap).
            ui.updateHoverInfo(cell.row, cell.col)
        }
        if (!curDown) return
        val gameDiv = byId("game") ?: return
        gameDiv.asDynamic().scrollLeft = dragStartScrollLeft + (dragStartClientX - event.clientX)
        gameDiv.asDynamic().scrollTop = dragStartScrollTop + (dragStartClientY - event.clientY)
    }

    /** Converts a mouse event to a native (unzoomed) canvas pixel coordinate — the space
     *  [Render.screenToCell]'s hex math expects. Built on `getBoundingClientRect()` +
     *  `event.clientX/Y`, which is robust to CSS `zoom` on any ancestor (map zoom's wrapper,
     *  strategic zoom's `#game`) and to DOM nesting generally, unlike the old manual
     *  offsetLeft/scrollLeft arithmetic this replaced — that version pre-dates zoom entirely and
     *  had no way to account for it (screenToCell used to compensate separately/redundantly). */
    private fun getClickPos(canvas: dynamic, event: MouseEvent): ClickInfo {
        val which = event.asDynamic().which as? Int ?: 0
        val rightClick = which == 3 || event.button.toInt() == 2
        val rect = canvas.getBoundingClientRect()
        val totalZoom = MapZoom.level * (if (uiSettings.strategicZoom) 1.0 / uiSettings.strategicZoomLevel else 1.0)
        val x = (event.clientX.toDouble() - (rect.left as Double)) / totalZoom
        val y = (event.clientY.toDouble() - (rect.top as Double)) / totalZoom
        return ClickInfo(x, y, rightClick)
    }

    private fun selectOtherUnit(row: Int, col: Int): Boolean {
        val map = ui.game.scenario?.map ?: return false
        val hex = map.map?.get(row)?.get(col) ?: return false
        var unit = hex.getUnit(uiSettings.airMode)
        if (unit == null || unit.player?.id != map.currentPlayer?.id) {
            unit = hex.getUnit(!uiSettings.airMode)
            if (unit == null || unit.player?.id != map.currentPlayer?.id) return false
        }
        ui.removeUnitToolTip(unit.id)
        val eqUserSel = byId("eqUserSel")?.asDynamic()
        eqUserSel?.userunit = unit.id
        eqUserSel?.deployunit = -1
        ui.updateEquipmentWindow(unit.unitData(true).uclass)
        return ui.uiUnitSelect(unit)
    }

    private fun deselectCurrentUnit() {
        val map = ui.game.scenario?.map ?: return
        val unit = map.currentUnit
        if (unit == null) {
            ui.buildUnitContext(null)
            return
        }
        val pos = unit.getPos() ?: return
        val radius = getUnitRenderRadius(unit)
        map.delCurrentUnit()
        ui.buildUnitContext(null)
        ui.render.render(pos.row, pos.col, radius)
    }

    private fun tryAttackAt(row: Int, col: Int): Boolean {
        val map = ui.game.scenario?.map ?: return false
        val currentUnit = map.currentUnit ?: return false
        val hex = map.map?.get(row)?.get(col) ?: return false
        val target = hex.getAttackableUnit(currentUnit, uiSettings.airMode) ?: return false
        ui.uiUnitAttack(currentUnit, target)
        return true
    }

    /** True if the unit currently picked in the deploy/equipment window is an air unit (so it may
     be placed on an airfield outside the deploy zone). */
    private fun selectedDeployUnitIsAir(): Boolean {
        val player = ui.game.scenario?.map?.currentPlayer ?: return false
        val index = byId("eqUserSel")?.asDynamic()?.deployunit as? Int ?: return false
        if (index < 0) return false
        val unit = player.getCoreUnitList().getOrNull(index) ?: return false
        return GameRules.isAir(unit)
    }

    private fun deployAt(row: Int, col: Int) {
        val map = ui.game.scenario?.map ?: return
        val player = map.currentPlayer ?: return
        val eqUserSel = byId("eqUserSel")?.asDynamic()
        val index = eqUserSel?.deployunit as? Int ?: -1
        if (index < 0) return
        val unit = player.getCoreUnitList().getOrNull(index) ?: return
        if (!map.deployPlayerUnit(player, unit, row, col)) return
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

    private fun updateLocationMessage(row: Int, col: Int) {
        val map = ui.game.scenario?.map ?: return
        val hex = map.map?.get(row)?.get(col) ?: return
        val currentSide = map.currentPlayer?.side ?: 0
        val sb = StringBuilder()
        sb.append(terrainNames.getOrNull(hex.terrain) ?: "Unknown")
        if (hex.road != RoadType.NONE.value) sb.append(", Road")
        if (hex.name.isNotEmpty()) sb.append(" - ${hex.name}")
        val unit = hex.getUnit(uiSettings.airMode)
        if (unit != null && (hex.isSpotted(currentSide) || unit.tempSpotted || unit.player?.side == currentSide)) {
            val data = unit.unitData()
            sb.append(" (${data.name}")
            if (unit.strength > 0) sb.append(" ${unit.strength}")
            sb.append(")")
        }
        sb.append(" (").append(col).append(",").append(row).append(")")
        byId("locmsg")?.innerHTML = sb.toString()
    }
}
