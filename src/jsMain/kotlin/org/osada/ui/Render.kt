package org.osada.ui

import org.osada.model.GameMap

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
class Render(
    map: GameMap?,
) {
    internal val ctx = RenderContext(map)
    private val unitRenderer = UnitRenderer(ctx)
    private val overlayRenderer = OverlayRenderer(ctx)
    internal val cursorRenderer = CursorRenderer(ctx)
    internal val mapRenderer = MapRenderer(ctx, unitRenderer, overlayRenderer, cursorRenderer)
    internal val animator = MapAnimator(ctx, unitRenderer)

    fun cacheImages(callback: () -> Unit) = ctx.cacheImages(callback)

    fun positionLayers() = ctx.positionLayers()

    fun setNewMap(newMap: GameMap) = ctx.setNewMap(newMap)

    fun render() = mapRenderer.render()

    fun render(
        centerRow: Int,
        centerCol: Int,
        radius: Int,
    ) = mapRenderer.render(centerRow, centerCol, radius)
}
