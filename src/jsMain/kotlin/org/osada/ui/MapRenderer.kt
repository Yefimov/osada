package org.osada.ui

import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.ScreenPos
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.uiSettings
import kotlin.math.max
import kotlin.math.min

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
        // Hexes the model has just switched an overlay off on, which the requested square may not
        // contain ([GameMap.pendingRepaint]). Taken before the frame is built and consumed here:
        // this is the one place that can honour it, and honouring it twice would repaint a region
        // nothing is stale in any more.
        val stale = q.pendingRepaint
        q.pendingRepaint = null
        val frame = buildRenderFrame(q, gameMap, centerRow, centerCol, radius, stale)

        val clearBounds =
            uniteRepaintRegion(
                rc.getBounds(centerRow, centerCol, radius + 1, frame.rows, frame.cols),
                stale,
                1,
                radius,
                frame.rows,
                frame.cols,
            )
        if (frame.hexGrid != rc.hexGridEnabled) {
            rc.hexGridEnabled = frame.hexGrid
            rc.mapCtx.clearRect(0.0, 0.0, rc.canvasWidth, rc.canvasHeight)
        }

        // cellToScreen returns a hex's top-left ANCHOR, but the hex itself overhangs it: 15px to
        // the left (its slant) and, because odd columns sit half a hex lower, 25px above and below
        // the anchor of a cell in the OTHER column parity. The raw anchor box therefore does not
        // cover every pixel of the cells it nominally spans. Padding it closes that gap; the pad
        // stays well inside `drawBounds`, a whole cell ring wider, so everything cleared is
        // repainted in the same pass. (Defensive: a select/deselect sweep measures no residue
        // either way today, because `pendingRepaint` unions the vacated cells in as well.)
        val c1 = padTopLeft(rc.cellToScreen(clearBounds.srow, clearBounds.scol, false))
        val c2 = padBottomRight(rc.cellToScreen(clearBounds.erow, clearBounds.ecol, false))
        if (radius < 0) {
            // Clear exactly the full region FogOfWarRenderer fills. Otherwise the extra bottom
            // strip receives another translucent veil on every Air/Grid/full redraw.
            rc.hexesCtx.clearRect(0.0, 0.0, rc.canvasWidth, rc.canvasHeight)
        } else {
            rc.hexesCtx.clearRect(c1.x, c1.y, c2.x - c1.x, c2.y - c1.y)
        }

        fogOfWarRenderer.apply(frame, c1, c2)
        drawCells(frame)
    }

    /** [render]'s clear/fog box, grown to cover the hex overhang above and left of the anchor. */
    private fun padTopLeft(pos: ScreenPos): ScreenPos = ScreenPos(pos.x - rc.hexSlantWidth, pos.y - rc.v)

    /** The same for the far corner: the last row's hexes in the other column parity end [v] lower
     *  than that corner's own anchor. The right edge needs no pad — the anchor of column `ecol`
     *  already sits exactly on the right tip of column `ecol - 1`. */
    private fun padBottomRight(pos: ScreenPos): ScreenPos = ScreenPos(pos.x, pos.y + rc.v)

    private fun buildRenderFrame(
        q: GameMap,
        gameMap: Array<Array<Hex>>,
        centerRow: Int,
        centerCol: Int,
        radius: Int,
        stale: GameMap.RepaintBox?,
    ): RenderFrame {
        val rows = q.rows
        val cols = q.cols
        // The deploy overlay is drawn against `currentPlayer`'s side ([HexCellRenderer]), and
        // `uiSettings.deployMode` is a UI-global with no owner binding: it survives the ✕
        // (EquipmentWindowBuilder deliberately leaves it set so a picked reserve can still be
        // placed) and it is persisted, so it also survives a reload. Left set through the turn
        // hand-off it therefore lit up the AI's deploy zone -- including ports it had just
        // captured -- while the AI moved. EndTurnFlow now clears it unconditionally; this is the
        // second half, at the point of use, so no other path can reintroduce the leak. Hotseat is
        // unaffected: both human players are HUMAN_LOCAL and each sees their own zone on their own
        // turn. Reported 2026-08-01, "it's the enemy's turn now and I was seeing hexes for the enemy".
        val deployMode = uiSettings.deployMode && q.currentPlayer?.type == PlayerType.HUMAN_LOCAL

        // The reserve unit awaiting placement, resolved exactly as the click path resolves it
        // (DeploymentSelection.selectedUnit) so the highlight cannot promise a hex the click would
        // refuse. Null when nothing is picked yet — the zone is then drawn in full, which is right:
        // some unit in the reserve can go there, we just don't know which one yet.
        val deployUnit = if (deployMode) DeploymentSelection.selectedUnit(q.currentPlayer) else null

        // When deploying an AIRCRAFT, airfields are valid deploy targets even outside the deploy
        // zone (OG rule, already honoured on click in MapInputController). Highlight them too — but
        // only for aircraft, so ground units don't see airfields lit up. Mirrors
        // `GameMap.isOutOfZoneDeployTarget`.
        val airDeploySelected = deployUnit != null && GameRules.isAir(deployUnit)

        return RenderFrame(
            q = q,
            gameMap = gameMap,
            rows = rows,
            cols = cols,
            radius = radius,
            drawBounds =
                uniteRepaintRegion(
                    rc.getBounds(centerRow, centerCol, radius + 2, rows, cols),
                    stale,
                    2,
                    radius,
                    rows,
                    cols,
                ),
            currentPos = q.currentUnit?.getPos(),
            airMode = uiSettings.airMode,
            markInactiveLayer = !uiSettings.reducedEffects,
            hexGrid = uiSettings.hexGrid,
            deployMode = deployMode,
            strategicZoom = uiSettings.strategicZoom,
            showHiddenVictoryHexes = uiSettings.showHiddenVictoryHexes,
            markOwnUnits = uiSettings.markOwnUnits,
            enhancedSideMarkers = uiSettings.enhancedSideMarkers,
            // Allegiance is read from the SPOTTING side, not `currentPlayer`: during an AI turn and
            // in observer mode the current player is not the person looking at the map, and a badge
            // that flipped every hand-off would be worse than no badge at all.
            spotSide = GameHolder.instance?.spotSide ?: 0,
            hasTouch = uiSettings.hasTouch,
            airDeploySelected = airDeploySelected,
            deployUnit = deployUnit,
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

/**
 * [base] grown to also contain [stale], the region [GameMap.pendingRepaint] named, with the same
 * [margin] of extra hexes the caller already allowed itself.
 *
 * A null [stale] and a full redraw (negative [radius], already the whole map) both return [base]
 * unchanged. At file level rather than inside [MapRenderer] because it is pure arithmetic with a
 * regression test of its own ([org.osada.MapRepaintRegionTest]) and because that class is at its
 * function budget.
 */
internal fun uniteRepaintRegion(
    base: RenderContext.Bounds,
    stale: GameMap.RepaintBox?,
    margin: Int,
    radius: Int,
    rows: Int,
    cols: Int,
): RenderContext.Bounds {
    if (stale == null || radius < 0) return base
    return RenderContext.Bounds(
        max(0, min(base.srow, stale.srow - margin)),
        max(0, min(base.scol, stale.scol - margin)),
        min(rows, max(base.erow, stale.erow + margin)),
        min(cols, max(base.ecol, stale.ecol + margin)),
    )
}

// Plain class (not data class): gameMap's Array property would give a structural equals()/
// hashCode() built on reference identity for that field, which is misleading and unused —
// every RenderFrame is a fresh per-frame parameter bundle, never compared or copied.

/** Per-frame context shared by [MapRenderer], [FogOfWarRenderer] and [HexCellRenderer]. */
internal class RenderFrame(
    val q: GameMap,
    val gameMap: Array<Array<Hex>>,
    val rows: Int,
    val cols: Int,
    val radius: Int,
    val drawBounds: RenderContext.Bounds,
    val currentPos: Cell?,
    val airMode: Boolean,
    /** Whether to recess the unit on the layer Air Mode is not commanding, and mark the hexes where
     *  that ambiguity exists at all. Polish, so it follows the reduced-effects setting; the
     *  targeting rule itself is `Hex.getActiveLayerTarget` and does not depend on it. */
    val markInactiveLayer: Boolean,
    val hexGrid: Boolean,
    val deployMode: Boolean,
    val strategicZoom: Boolean,
    val showHiddenVictoryHexes: Boolean,
    val markOwnUnits: Boolean,
    /** Opt-in star/skull allegiance badges on strategic unit flags (accessibility, off by default). */
    val enhancedSideMarkers: Boolean,
    /** The side whose point of view the map is drawn from -- `game.spotSide`, not the current player. */
    val spotSide: Int,
    val hasTouch: Boolean,
    val airDeploySelected: Boolean,
    /** Reserve unit awaiting placement, or null when none is picked yet. */
    val deployUnit: GameUnit? = null,
)
