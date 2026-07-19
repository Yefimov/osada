package org.osada.ui

import org.osada.GameHolder
import org.osada.model.ScreenPos

/**
 * Fog-of-war veil pre-pass for [MapRenderer]: fills the drawn area with a translucent veil then
 * erases it over hexes the current side can see, so terrain shows through only where spotted.
 * isSpotted() already returns true when the noFOW setting is on, so this whole pass produces
 * nothing then. Split out purely to keep [MapRenderer] within the project's
 * function-count/complexity limits.
 */
internal class FogOfWarRenderer(
    private val rc: RenderContext,
) {
    companion object {
        private const val EXTRA_CANVAS_HEIGHT = 65.0
    }

    fun apply(
        frame: RenderFrame,
        c1: ScreenPos,
        c2: ScreenPos,
    ) {
        val ctx = rc.hexesCtx
        val spotSide = GameHolder.instance?.spotSide ?: 0
        ctx.save()
        ctx.fillStyle = "rgba(20,24,28,0.38)"
        // On a full render (radius < 0) fog the whole canvas to catch the map margins where a
        // library map image overruns the logical grid; on a partial render fog only the cleared
        // box so the translucent layer can't stack outside it.
        if (frame.radius < 0) {
            ctx.fillRect(0.0, 0.0, rc.mapWidth, rc.mapHeight + EXTRA_CANVAS_HEIGHT)
        } else {
            ctx.fillRect(c1.x, c1.y, c2.x - c1.x, c2.y - c1.y)
        }
        // Erase the veil over SEEN hexes. The erase must be FULLY opaque: destination-out removes
        // dst alpha in proportion to src alpha, so a translucent fill here would only half-erase
        // and leave seen terrain darkened. Use solid black (only alpha matters for destination-out).
        ctx.globalCompositeOperation = "destination-out"
        ctx.fillStyle = "rgba(0,0,0,1)"
        // Also STROKE each erased hex (opaque, ~2px): adjacent hexes in offset columns share
        // slanted edges where fill()-only leaves a hairline veil seam (the thin grey line between
        // visible hexes). Stroking clears ~1px on each side of the edge so neighbours meet cleanly.
        ctx.strokeStyle = "rgba(0,0,0,1)"
        ctx.lineWidth = 2.0
        for (r in frame.drawBounds.srow until frame.drawBounds.erow) {
            for (c in frame.drawBounds.scol until frame.drawBounds.ecol) {
                val outOfBounds = r < 0 || c < 0 || r >= frame.rows || c >= frame.cols
                if (outOfBounds || !frame.gameMap[r][c].isSpotted(spotSide)) continue
                eraseHex(ctx, r, c)
            }
        }
        ctx.restore()
    }

    private fun eraseHex(
        ctx: dynamic,
        r: Int,
        c: Int,
    ) {
        val fy = if (c % 2 == 1) 2.0 * r * rc.v + rc.v + rc.ca else 2.0 * r * rc.v + rc.ca
        val fx = c * (rc.hexTopWidth + rc.hexSlantWidth) + rc.hexSlantWidth + rc.ba
        ctx.beginPath()
        ctx.moveTo(fx, fy)
        ctx.lineTo(fx + rc.hexTopWidth, fy)
        ctx.lineTo(fx + rc.hexTopWidth + rc.hexSlantWidth, fy + rc.v)
        ctx.lineTo(fx + rc.hexTopWidth, fy + 2 * rc.v)
        ctx.lineTo(fx, fy + 2 * rc.v)
        ctx.lineTo(fx - rc.hexSlantWidth, fy + rc.v)
        ctx.closePath()
        ctx.fill()
        ctx.stroke()
    }
}
