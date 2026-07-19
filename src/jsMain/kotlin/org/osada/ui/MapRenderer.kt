package org.osada.ui

import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.uiSettings

/**
 * Top-level map painter: clears and redraws hexes, grid, deploy/move/attack overlays,
 * flags and units for a region of the map. Extracted from the former `Render` god-class,
 * this is the coordinator of a single frame — it computes the shared [RenderFrame] context
 * once, then delegates the fog-of-war veil to [FogOfWarRenderer] and per-cell drawing to
 * [HexCellRenderer] (which in turn delegates unit sprites to [UnitRenderer], flags/victory
 * markers to [OverlayRenderer] and the touch attack preview to [CursorRenderer]), all drawing
 * through the shared [RenderContext].
 */
internal class MapRenderer(
    private val rc: RenderContext,
    unitRenderer: UnitRenderer,
    overlayRenderer: OverlayRenderer,
    cursorRenderer: CursorRenderer,
) {
    companion object {
        private const val FULL_REDRAW_RADIUS = -3

        // The terrain image is 65px shorter than the canvases (matches RenderContext's own
        // LAST_HEX_ROW_HEIGHT).
        private const val EXTRA_CANVAS_HEIGHT = 65.0
    }

    private val fogOfWarRenderer = FogOfWarRenderer(rc)
    private val hexCellRenderer = HexCellRenderer(rc, unitRenderer, overlayRenderer, cursorRenderer)

    /** Полный redraw всей карты (используется при старте, ресайзе и т.д.) */
    fun render() {
        rc.map ?: return
        // radius = -3 заставляет getBounds(radius+1/radius+2) вернуть всю карту
        render(0, 0, FULL_REDRAW_RADIUS)
    }

    fun render(
        centerRow: Int,
        centerCol: Int,
        radius: Int,
    ) {
        val q = rc.map ?: return
        val gameMap = q.map ?: return
        val frame = buildRenderFrame(q, gameMap, centerRow, centerCol, radius)

        val clearBounds = rc.getBounds(centerRow, centerCol, radius + 1, frame.rows, frame.cols)
        if (frame.hexGrid != rc.hexGridEnabled) {
            rc.hexGridEnabled = frame.hexGrid
            rc.mapCtx.clearRect(0.0, 0.0, rc.mapWidth, rc.mapHeight + EXTRA_CANVAS_HEIGHT)
        }

        val c1 = rc.cellToScreen(clearBounds.srow, clearBounds.scol, false)
        val c2 = rc.cellToScreen(clearBounds.erow, clearBounds.ecol, false)
        rc.hexesCtx.clearRect(c1.x, c1.y, c2.x - c1.x, c2.y - c1.y)

        // Fog of war: fill the (re)drawn area with a translucent veil, then ERASE the hexes the
        // current side can see so the terrain shows through. Filling-then-erasing (instead of
        // shading each unseen hex) means the veil never double-darkens where partial redraws
        // overlap (no seam frame around a moved unit) and it also covers the map margins where a
        // library map image overruns the logical grid by a partial row/column.
        fogOfWarRenderer.apply(frame, c1, c2)

        drawCells(frame)
    }

    private fun buildRenderFrame(
        q: GameMap,
        gameMap: Array<Array<Hex>>,
        centerRow: Int,
        centerCol: Int,
        radius: Int,
    ): RenderFrame {
        val rows = q.rows
        val cols = q.cols
        val deployMode = uiSettings.deployMode as? Boolean ?: false

        // When deploying an AIRCRAFT, airfields are valid deploy targets even outside the deploy
        // zone (OG rule, already honoured on click in MapInputController). Highlight them too — but
        // only for aircraft, so ground units don't see airfields lit up. Mirrors
        // selectedDeployUnitIsAir().
        val airDeploySelected =
            deployMode &&
                run {
                    val index = byId("eqUserSel")?.asDynamic()?.deployunit as? Int ?: -1
                    val unit = if (index >= 0) q.currentPlayer?.getCoreUnitList()?.getOrNull(index) else null
                    unit != null && GameRules.isAir(unit)
                }

        return RenderFrame(
            q = q,
            gameMap = gameMap,
            rows = rows,
            cols = cols,
            radius = radius,
            drawBounds = rc.getBounds(centerRow, centerCol, radius + 2, rows, cols),
            currentPos = q.currentUnit?.getPos(),
            airMode = uiSettings.airMode as? Boolean ?: false,
            hexGrid = uiSettings.hexGrid as? Boolean ?: false,
            deployMode = deployMode,
            strategicZoom = uiSettings.strategicZoom as? Boolean ?: false,
            showHiddenVictoryHexes = uiSettings.showHiddenVictoryHexes as? Boolean ?: false,
            markOwnUnits = uiSettings.markOwnUnits as? Boolean ?: false,
            hasTouch = uiSettings.hasTouch as? Boolean ?: false,
            airDeploySelected = airDeploySelected,
        )
    }

    private fun drawCells(frame: RenderFrame) {
        for (r in frame.drawBounds.srow until frame.drawBounds.erow) {
            for (c in frame.drawBounds.scol until frame.drawBounds.ecol) {
                val outOfBounds = r < 0 || c < 0 || r >= frame.rows || c >= frame.cols
                if (outOfBounds) continue
                val hex = frame.gameMap[r][c]
                val y = if (c % 2 == 1) 2.0 * r * rc.v + rc.v + rc.ca else 2.0 * r * rc.v + rc.ca
                val x = c * (rc.hexTopWidth + rc.hexSlantWidth) + rc.hexSlantWidth + rc.ba
                val isCurrentHex = frame.currentPos != null && frame.currentPos.row == r && frame.currentPos.col == c
                hexCellRenderer.drawCell(frame, hex, x, y, isCurrentHex)
            }
        }
    }

    fun drawHexByCell(
        cell: Cell,
        style: dynamic,
    ) {
        val p = rc.cellToScreen(cell.row, cell.col, false)
        rc.drawHex(rc.hexesCtx, p.x, p.y, style)
    }
}

/** Per-frame context shared by [MapRenderer], [FogOfWarRenderer] and [HexCellRenderer]. */
internal data class RenderFrame(
    val q: GameMap,
    val gameMap: Array<Array<Hex>>,
    val rows: Int,
    val cols: Int,
    val radius: Int,
    val drawBounds: RenderContext.Bounds,
    val currentPos: Cell?,
    val airMode: Boolean,
    val hexGrid: Boolean,
    val deployMode: Boolean,
    val strategicZoom: Boolean,
    val showHiddenVictoryHexes: Boolean,
    val markOwnUnits: Boolean,
    val hasTouch: Boolean,
    val airDeploySelected: Boolean,
)
