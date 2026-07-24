package org.osada.ui

import org.osada.PlayerType
import org.osada.RoadType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.terrainNames
import org.osada.uiSettings
import org.w3c.dom.events.MouseEvent

/**
 * Owns the cursor state and all map mouse-event logic: click dispatch (select / move / attack /
 * deploy), drag-to-scroll, cursor drawing, and location messages. Extracted from the former
 * `UI` god-class (SRP). Click routing itself lives in [MapClickHandler].
 */
internal class MapInputController(
    private val ui: UI,
) {
    private val clickHandler = MapClickHandler(ui)

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

    private data class ClickInfo(
        val x: Double,
        val y: Double,
        val rightClick: Boolean,
    )

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
        val map = resolvePlayableMap()
        val canvas = ui.render.getCursorCanvas()
        if (map == null || canvas == null) return

        val info = getClickPos(canvas, event)
        trackDragStart(event)

        val cell = ui.render.screenToCell(info.x.toInt(), info.y.toInt())
        val hex = resolveHex(map, cell)
        if (hex != null) dispatchClick(map, cell, hex, info, event)
    }

    private fun resolvePlayableMap(): GameMap? {
        val notPlayable = !ui.game.gameStarted || ui.game.gameEnded || ui.game.waitUIAnimation
        val map = ui.game.scenario?.map
        val isHumanTurn = map?.currentPlayer?.type == PlayerType.HUMAN_LOCAL
        return if (notPlayable || map == null || !isHumanTurn) null else map
    }

    private fun resolveHex(
        map: GameMap,
        cell: Cell,
    ): org.osada.model.Hex? {
        val inBounds = cell.row in 0 until map.rows && cell.col in 0 until map.cols
        return if (inBounds) map.map?.get(cell.row)?.get(cell.col) else null
    }

    private fun dispatchClick(
        map: GameMap,
        cell: Cell,
        hex: org.osada.model.Hex,
        info: ClickInfo,
        event: MouseEvent,
    ) {
        if (uiSettings.strategicZoom) {
            ui.toggleStrategicZoom()
            ui.uiSetCellOnViewPort(cell)
            return
        }

        val currentPlayerSide = map.currentPlayer?.side ?: 0
        val unit = clickHandler.resolveVisibleUnit(hex, currentPlayerSide)

        if (info.rightClick) {
            clickHandler.handleRightClick(map, unit)
            return
        }

        val handled =
            if (unit != null) {
                clickHandler.handleLeftClickWithUnit(map, cell, hex, unit)
            } else {
                clickHandler.handleLeftClickEmpty(map, cell, hex, currentPlayerSide)
            }
        if (!handled && uiSettings.hasTouch) updateLocationMessage(cell.row, cell.col)
        finishClick(map, unit, handled, cell)
        event.preventDefault()
    }

    private fun trackDragStart(event: MouseEvent) {
        if (uiSettings.hasTouch) return
        curDown = true
        dragStartClientX = event.clientX.toDouble()
        dragStartClientY = event.clientY.toDouble()
        val gameDiv = byId("game")?.asDynamic()
        dragStartScrollLeft = (gameDiv?.scrollLeft as? Number)?.toDouble() ?: 0.0
        dragStartScrollTop = (gameDiv?.scrollTop as? Number)?.toDouble() ?: 0.0
    }

    /** Post-click refresh: air-mode toggle from the (possibly changed) selection, enemy-card vs
     *  own-unit-info display, and the location message. A pure click on a spotted foreign
     *  (enemy/allied) unit is an inspection: keep its info on screen instead of reverting to the
     *  selected own unit. Attacks/moves are `handled`, so those still refresh the acting own
     *  unit. Unconditional (not gated on currentUnit == null): it used to only show the enemy
     *  card when NOTHING own was selected, so clicking a non-attackable enemy while an own unit
     *  was still selected (easy to have happen without noticing — a unit stays "selected" until
     *  explicitly deselected) silently did nothing, which read as "the enemy card doesn't always
     *  appear." Right-click already showed it unconditionally; this makes left-click consistent
     *  with that instead of a hidden special case. */
    private fun finishClick(
        map: GameMap,
        unit: GameUnit?,
        handled: Boolean,
        cell: Cell,
    ) {
        val currentUnit = map.currentUnit
        val isAir = currentUnit?.let { GameRules.isAir(it) } ?: false
        uiSettings.airMode = isAir
        byId("air")?.let { toggleButton(it, isAir) }
        if (!handled && unit != null && unit.player?.id != map.currentPlayer?.id) {
            ui.showEnemyCard(unit)
        } else {
            currentUnit?.let { ui.showUnitInfo(it) }
        }
        currentUnit?.getPos()?.let { updateLocationMessage(it.row, it.col) }
            ?: updateLocationMessage(cell.row, cell.col)
    }

    private fun handleMapMouseMove(event: MouseEvent) {
        val canvas = ui.render.getCursorCanvas()
        if (canvas != null) {
            val info = getClickPos(canvas, event)
            val cell = ui.render.screenToCell(info.x.toInt(), info.y.toInt())
            if (ui.game.scenario
                    ?.map
                    ?.currentUnit != null &&
                uiSettings.strategicZoom != true
            ) {
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
    private fun getClickPos(
        canvas: dynamic,
        event: MouseEvent,
    ): ClickInfo {
        val which = event.asDynamic().which as? Int ?: 0
        val rightClick = which == 3 || event.button.toInt() == 2
        val rect = canvas.getBoundingClientRect()
        val totalZoom = MapZoom.level * (if (uiSettings.strategicZoom) 1.0 / uiSettings.strategicZoomLevel else 1.0)
        val x = (event.clientX.toDouble() - (rect.left as Double)) / totalZoom
        val y = (event.clientY.toDouble() - (rect.top as Double)) / totalZoom
        return ClickInfo(x, y, rightClick)
    }

    private fun updateLocationMessage(
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
        val unitVisible =
            unit != null && (hex.isSpotted(currentSide) || unit.tempSpotted || unit.player?.side == currentSide)
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
        byId("locmsg")?.innerHTML = sb.toString()
    }
}
