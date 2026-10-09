package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.PlayerType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.setMoveRange
import org.osada.rules.CautiousMove
import org.osada.rules.GameRules
import org.osada.rules.getShortestPath
import org.osada.ui.keyboard.CommandRouter
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * OG's Shift route marking (manual §6.13: *"if you press Shift with a unit already selected and
 * then click in some hex within range, the path that the unit will follow will be marked"*;
 * `docs/design/og-parity-followups.md` §1, built 2026-10-09).
 *
 * **Holding Shift** with a ground formation selected switches it into cautious move for as long as
 * the key is down ([ShiftCautiousHold]): the drawn range shrinks to spotted ground at once, so the
 * mode is visible before anything is clicked, and the hovered hex shows the exact route
 * ([route]). A click while Shift is still down therefore moves cautiously through the ordinary
 * move path -- `MoveExecutor` walks the same `currentMoveRange` this preview read, and a room order
 * picks the mode up from `GameMap.cautiousMoveUnitId` -- so the drawn route and the walked route
 * cannot disagree. Releasing Shift restores the normal range. An aircraft, or a formation the mode
 * does not apply to, still gets its ordinary route drawn.
 *
 * Desktop accelerator only. Touch has no Shift and no hover; there the Cautious chip remains the
 * whole feature. Local UI state: nothing is saved, and nothing is sent beyond the move order's
 * existing `cautious` field.
 */
internal object RoutePreview {
    /**
     * The route [map]'s selected formation would walk to [target]: its own hex first, the target
     * last. Empty when nothing is selected, the target is not a reachable move hex, is the unit's
     * own hex, or can only be reached by mounting (`rules/AutoMount` re-plans that route after
     * mounting, so a path drawn from the current range would be a guess).
     */
    fun route(
        map: GameMap,
        target: Cell,
    ): List<Cell> {
        val unit = map.currentUnit
        val from = unit?.getPos()
        val hex = map.map?.getOrNull(target.row)?.getOrNull(target.col)
        val reachable = hex != null && hex.isMoveSel && !hex.needsTransport && unit?.hasMoved == false
        val elsewhere = from != null && (from.row != target.row || from.col != target.col)
        return if (reachable && elsewhere && from != null) {
            GameRules.getShortestPath(from, target, map.currentMoveRange)
        } else {
            emptyList()
        }
    }
}

/**
 * The "while Shift is down" half of [RoutePreview]: a cautious mode that belongs to the key, not to
 * the selection. It only ever undoes what it set itself, so a formation the player made cautious by
 * the chip, `C` or Shift+click stays cautious when the key comes up.
 */
internal object ShiftCautiousHold {
    /** The formation this hold switched to cautious, or null. */
    var heldUnitId: Int? = null
        private set

    /** Shift went down: switch [map]'s selected formation to cautious if it is not already. Returns
     *  whether the move range changed (the caller repaints). */
    fun engage(map: GameMap): Boolean {
        val unit = map.currentUnit
        val eligible = unit != null && heldUnitId == null && CautiousMove.appliesTo(unit)
        if (!eligible || unit == null || CautiousMove.isOn(map, unit)) return false
        heldUnitId = unit.id
        CautiousMove.toggle(map, unit)
        return true
    }

    /** Shift came up (or focus left): undo this hold's own switch only. Returns the formation whose
     *  range changed, or null when nothing needs repainting. */
    fun release(map: GameMap?): GameUnit? {
        val id = heldUnitId
        heldUnitId = null
        if (id == null || map == null || map.cautiousMoveUnitId != id) return null
        // Cleared even when the formation is no longer selected (deselected with Shift down), or
        // selecting it again later would find it still cautious.
        map.cautiousMoveUnitId = null
        val unit = map.currentUnit?.takeIf { it.id == id }
        // A move made with Shift down has already spent the formation; recomputing an ordinary
        // range for it would draw one it can no longer use.
        if (unit != null && CautiousMove.appliesTo(unit)) map.setMoveRange(unit)
        return unit
    }

    /** A new scenario or a test starts with no hold. */
    fun reset() {
        heldUnitId = null
    }
}

/**
 * The DOM half: Shift key tracking, the hover hook and the drawing on the cursor canvas (the top
 * map layer, which only short animation sprites otherwise use; full map renders never touch it,
 * so the route survives them and is cleared here explicitly).
 */
internal object RoutePreviewController {
    private var installed = false
    private var ui: UI? = null
    private var shiftDown = false
    private var hoverCell: Cell? = null
    private var drawn = false

    fun install(ui: UI) {
        this.ui = ui
        if (installed) return
        installed = true
        document.addEventListener("keydown", { event -> onKey(event, down = true) })
        document.addEventListener("keyup", { event -> onKey(event, down = false) })
        window.addEventListener("blur", { setShift(false) })
    }

    /** Fine-pointer hover reached [cell]; [shiftKey] is the pointer event's own modifier state,
     *  which also catches a Shift pressed while focus was outside the page. */
    fun onHover(
        cell: Cell,
        shiftKey: Boolean,
    ) {
        hoverCell = cell
        if (shiftKey != shiftDown) setShift(shiftKey) else redraw()
    }

    /** A map click, a move or a selection change: the old route no longer describes anything. */
    fun clear() {
        val rc = ui?.render?.ctx ?: return
        if (drawn) rc.cursorCtx?.clearRect(0.0, 0.0, rc.canvasWidth, rc.canvasHeight)
        drawn = false
    }

    private fun onKey(
        event: Event,
        down: Boolean,
    ) {
        val tag = (event.target as? HTMLElement)?.tagName
        val typing = down && (tag == "INPUT" || tag == "TEXTAREA")
        if (event.asDynamic().key == "Shift" && !typing) setShift(down)
    }

    private fun setShift(down: Boolean) {
        if (down == shiftDown) return
        shiftDown = down
        val ui = ui ?: return
        val map = ui.game.scenario?.map
        val changed: GameUnit? =
            if (down) {
                map?.takeIf { playable(ui, it) && ShiftCautiousHold.engage(it) }?.currentUnit
            } else {
                ShiftCautiousHold.release(map)
            }
        changed?.let { unit -> repaintSelection(ui, unit) }
        redraw()
    }

    private fun repaintSelection(
        ui: UI,
        unit: GameUnit,
    ) {
        val pos = unit.getPos() ?: return
        ui.buildUnitContext(unit)
        ui.render.render(pos.row, pos.col, getUnitRenderRadius(unit))
    }

    private fun playable(
        ui: UI,
        map: GameMap,
    ): Boolean =
        ui.game.gameStarted &&
            !ui.game.gameEnded &&
            !ui.game.waitUIAnimation &&
            map.currentPlayer?.type == PlayerType.HUMAN_LOCAL &&
            CommandRouter.scope() == CommandRouter.Scope.GAMEPLAY

    private fun redraw() {
        clear()
        val ui = ui ?: return
        val map =
            ui.game.scenario
                ?.map
                ?.takeIf { shiftDown && playable(ui, it) }
        val path = map?.let { m -> hoverCell?.let { RoutePreview.route(m, it) } }.orEmpty()
        if (map == null || path.size < 2) return
        RoutePainter.draw(ui.render.ctx, path, map.currentUnit?.let { CautiousMove.isOn(map, it) } == true)
        drawn = true
    }
}

/** Paints a route on the cursor canvas: a brass line over a dark underlay, a dot per step and a
 *  ring on the destination; dashed while the route keeps to spotted hexes. */
private object RoutePainter {
    fun draw(
        rc: RenderContext,
        path: List<Cell>,
        cautious: Boolean,
    ) {
        val ctx = rc.cursorCtx ?: return
        val points = path.map { cell -> centre(rc, cell) }
        ctx.save()
        ctx.lineCap = "round"
        ctx.lineJoin = "round"
        // A dark underlay keeps the brass line readable on snow, desert and sea alike.
        stroke(ctx, points, ROUTE_SHADOW, ROUTE_SHADOW_WIDTH, cautious)
        stroke(ctx, points, ROUTE_COLOR, ROUTE_WIDTH, cautious)
        ctx.setLineDash(arrayOf<Double>())
        points.drop(1).dropLast(1).forEach { (x, y) -> dot(ctx, x, y, STEP_RADIUS) }
        val (endX, endY) = points.last()
        ctx.beginPath()
        ctx.arc(endX, endY, END_RADIUS, 0.0, FULL_TURN)
        ctx.lineWidth = ROUTE_WIDTH
        ctx.strokeStyle = ROUTE_SHADOW
        ctx.stroke()
        ctx.lineWidth = ROUTE_WIDTH - 1.0
        ctx.strokeStyle = ROUTE_COLOR
        ctx.stroke()
        ctx.restore()
    }

    private fun stroke(
        ctx: dynamic,
        points: List<Pair<Double, Double>>,
        color: String,
        width: Double,
        dashed: Boolean,
    ) {
        ctx.setLineDash(if (dashed) arrayOf(DASH, DASH_GAP) else arrayOf<Double>())
        ctx.beginPath()
        points.forEachIndexed { i, (x, y) -> if (i == 0) ctx.moveTo(x, y) else ctx.lineTo(x, y) }
        ctx.lineWidth = width
        ctx.strokeStyle = color
        ctx.stroke()
    }

    private fun dot(
        ctx: dynamic,
        x: Double,
        y: Double,
        radius: Double,
    ) {
        ctx.beginPath()
        ctx.arc(x, y, radius + 1.0, 0.0, FULL_TURN)
        ctx.fillStyle = ROUTE_SHADOW
        ctx.fill()
        ctx.beginPath()
        ctx.arc(x, y, radius, 0.0, FULL_TURN)
        ctx.fillStyle = ROUTE_COLOR
        ctx.fill()
    }

    /** A hex's centre in canvas pixels: `cellToScreen` gives the upright core's top-left corner. */
    private fun centre(
        rc: RenderContext,
        cell: Cell,
    ): Pair<Double, Double> {
        val pos = rc.cellToScreen(cell.row, cell.col, false)
        return (pos.x + rc.hexTopWidth / 2.0) to (pos.y + rc.v)
    }

    private const val ROUTE_COLOR = "rgba(232, 196, 104, 0.95)"
    private const val ROUTE_SHADOW = "rgba(20, 16, 10, 0.75)"
    private const val ROUTE_WIDTH = 3.0
    private const val ROUTE_SHADOW_WIDTH = 6.0
    private const val STEP_RADIUS = 2.5
    private const val END_RADIUS = 8.0
    private const val DASH = 7.0
    private const val DASH_GAP = 5.0
    private const val FULL_TURN = 2.0 * kotlin.math.PI
}
