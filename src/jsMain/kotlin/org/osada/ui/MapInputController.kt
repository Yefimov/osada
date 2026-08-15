package org.osada.ui

import org.osada.PlayerType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.ui.input.MapActivationKind
import org.osada.ui.input.MapPointerController
import org.osada.uiSettings

/**
 * Facade over the map's input layer (spec §31):
 *
 * ```
 * MapInputController
 *  ├─ MapPointerController   (Pointer Events -> gestures)
 *  ├─ desktop Ctrl+wheel zoom
 *  └─ MapClickHandler        (semantic routing into game rules)
 * ```
 *
 * It owns the coordinate bridge (client pixels -> native canvas pixels -> hex) and the post-action
 * HUD refresh; it deliberately owns no gesture recognition and no game rules. Mouse, pen and touch
 * all arrive here through the same two entry points, [onActivate] and [onHover], so there is no
 * separate touch code path to drift out of sync — and in particular no "skip dragging when the
 * device has a touchscreen" branch, which is what used to make the map unpannable on a phone.
 */
internal class MapInputController(
    private val ui: UI,
) {
    private val clickHandler = MapClickHandler(ui)
    private val pointerController = MapPointerController(this)

    fun attachMapEventListeners() {
        val cursorCanvas = ui.render.getCursorCanvas() ?: return
        pointerController.attach(cursorCanvas)
        attachDesktopWheelZoom(cursorCanvas)
    }

    /**
     * Ctrl+wheel = map zoom toward the cursor, unchanged desktop behaviour. `{passive:false}` is
     * required for preventDefault() to actually suppress the browser's own page zoom (Chrome
     * defaults wheel listeners to passive and silently ignores preventDefault otherwise) — PM's
     * Ctrl+wheel WAS that unsuppressed browser zoom, which is why it enlarged the whole HUD.
     */
    private fun attachDesktopWheelZoom(cursorCanvas: dynamic) {
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

    /**
     * A tap, a click or a long press resolved to a map position. [touch] separates the two kinds
     * of inspection: a long press must leave the selection alone (spec §26), while a desktop
     * right-click on empty ground keeps its long-standing deselect behaviour.
     */
    @Suppress("ReturnCount")
    fun onActivate(
        clientX: Double,
        clientY: Double,
        kind: MapActivationKind,
        touch: Boolean,
    ) {
        val map = resolvePlayableMap() ?: return
        val cell = cellAt(clientX, clientY) ?: return
        val hex = resolveHex(map, cell) ?: return
        if (uiSettings.strategicZoom) {
            ui.toggleStrategicZoom()
            ui.uiSetCellOnViewPort(cell)
            return
        }
        val currentPlayerSide = map.currentPlayer?.side ?: 0
        val currentPlayerId = map.currentPlayer?.id ?: -1
        val unit = clickHandler.resolveVisibleUnit(hex, currentPlayerSide, currentPlayerId)
        if (kind == MapActivationKind.INSPECT) {
            inspect(map, cell, unit, touch)
            return
        }
        // A tap anywhere other than the previewed target ends that preview (spec §17.2.7); the
        // tap itself then routes normally, so tapping a different enemy re-previews immediately.
        if (!TargetPreviewController.isPendingCell(cell.row, cell.col)) TargetPreviewController.cancel(ui)
        val handled =
            if (unit != null) {
                clickHandler.handleLeftClickWithUnit(map, cell, hex, unit)
            } else {
                clickHandler.handleLeftClickEmpty(map, cell, hex, currentPlayerSide)
            }
        if (!handled) updateMapLocationMessage(ui, cell.row, cell.col)
        finishClick(map, unit, handled, cell)
    }

    /** Fine-pointer motion over the map: cursor art, location line and the sidebar inspector. */
    fun onHover(
        clientX: Double,
        clientY: Double,
    ) {
        val cell = cellAt(clientX, clientY) ?: return
        if (ui.game.scenario
                ?.map
                ?.currentUnit != null &&
            !uiSettings.strategicZoom
        ) {
            ui.render.drawCursor(cell)
        }
        updateMapLocationMessage(ui, cell.row, cell.col)
        // Sidebar "Under Cursor" inspector (cell-change guarded inside, so cheap).
        ui.updateHoverInfo(cell.row, cell.col)
    }

    /**
     * Inspection only: show whose unit this is and what the ground is, and change nothing. Long
     * press must not move, attack or deselect — it is the safe half of what right-click does.
     */
    private fun inspect(
        map: GameMap,
        cell: Cell,
        unit: GameUnit?,
        touch: Boolean,
    ) {
        if (unit != null) {
            clickHandler.handleRightClick(map, unit)
        } else if (!touch) {
            // Desktop right-click on empty ground keeps deselecting, as it always has.
            clickHandler.handleRightClick(map, null)
        }
        updateMapLocationMessage(ui, cell.row, cell.col)
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
        if (!TargetPreviewController.hasPending) {
            if (!handled && unit != null && unit.player?.id != map.currentPlayer?.id) {
                ui.showEnemyCard(unit)
            } else {
                currentUnit?.let { ui.showUnitInfo(it) }
            }
        }
        currentUnit?.getPos()?.let { updateMapLocationMessage(ui, it.row, it.col) }
            ?: updateMapLocationMessage(ui, cell.row, cell.col)
    }

    /**
     * Viewport (client) pixels -> the hex under them.
     *
     * Built on `getBoundingClientRect()` rather than manual offset/scroll arithmetic, so it is
     * robust to CSS `zoom` on any ancestor (the map-zoom wrapper, strategic zoom's `#game`) and to
     * DOM nesting generally — and it works for pointer, mouse, tests and keyboard activation
     * alike, unlike the `MouseEvent`-typed helper it replaced.
     */
    @Suppress("ReturnCount")
    fun cellAt(
        clientX: Double,
        clientY: Double,
    ): Cell? {
        val canvas = ui.render.getCursorCanvas() ?: return null
        val rect = canvas.getBoundingClientRect()
        val totalZoom = MapZoom.level * (if (uiSettings.strategicZoom) 1.0 / uiSettings.strategicZoomLevel else 1.0)
        if (totalZoom <= 0.0) return null
        val x = (clientX - (rect.left as Double)) / totalZoom
        val y = (clientY - (rect.top as Double)) / totalZoom
        return ui.render.screenToCell(x.toInt(), y.toInt())
    }
}
