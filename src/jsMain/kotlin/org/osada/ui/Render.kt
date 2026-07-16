package org.osada.ui

import org.osada.model.*

/**
 * Canvas rendering entry point used by the UI layer.
 *
 * This used to be a ~1000-line god-class mixing canvas lifecycle, geometry, hex/unit/flag
 * drawing, the attack cursor and animations. It has been split (Single Responsibility) into:
 * - [RenderContext] — canvases, images, geometry and the low-level primitives,
 * - [UnitRenderer] — unit sprites + stat overlays,
 * - [OverlayRenderer] — flags and victory-hex markers,
 * - [CursorRenderer] — the attack-preview cursor,
 * - [MapRenderer] — the per-frame map painter,
 * - [MapAnimator] — fire/explosion and unit-move animations.
 *
 * This class is now a thin coordinator that wires those collaborators over one shared
 * [RenderContext] and preserves the exact public surface the UI/Game layer calls.
 */
class Render(map: GameMap?) {

    private val ctx = RenderContext(map)
    private val unitRenderer = UnitRenderer(ctx)
    private val overlayRenderer = OverlayRenderer(ctx)
    private val cursorRenderer = CursorRenderer(ctx)
    private val mapRenderer = MapRenderer(ctx, unitRenderer, overlayRenderer, cursorRenderer)
    private val animator = MapAnimator(ctx, unitRenderer)

    fun cacheImages(callback: () -> Unit) = ctx.cacheImages(callback)
    fun positionLayers() = ctx.positionLayers()
    fun setIconsetTint(iconset: Int) = ctx.setIconsetTint(iconset)
    fun setNewMap(newMap: GameMap) = ctx.setNewMap(newMap)

    fun cellToScreen(row: Int, col: Int, absolute: Boolean): ScreenPos = ctx.cellToScreen(row, col, absolute)
    fun screenToCell(x: Int, y: Int): Cell = ctx.screenToCell(x, y)

    fun render() = mapRenderer.render()
    fun render(centerRow: Int, centerCol: Int, radius: Int) = mapRenderer.render(centerRow, centerCol, radius)
    fun drawHexByCell(cell: Cell, style: dynamic) = mapRenderer.drawHexByCell(cell, style)

    fun drawCursor(cell: Cell) = cursorRenderer.drawCursor(cell)

    fun runAnimation(callback: dynamic) = animator.runAnimation(callback)
    fun addAnimation(row: Int, col: Int, type: String, direction: Int): Boolean =
        animator.addAnimation(row, col, type, direction)
    fun moveAnimation(params: dynamic) = animator.moveAnimation(params)

    fun getHexesCanvas(): dynamic = ctx.getHexesCanvas()
    fun getMapCanvas(): dynamic = ctx.getMapCanvas()
    fun getCursorCanvas(): dynamic = ctx.getCursorCanvas()
    /** The loaded terrain artwork (HTMLImageElement) — the minimap composites it as its base layer. */
    fun getTerrainImage(): dynamic = ctx.terrainImage
}
