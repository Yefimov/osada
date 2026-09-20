package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.json

/**
 * Paints the Artillery toggle's two hatched envelopes: red for the hexes our own guns reach, black
 * for the hexes visible enemy guns reach ([ArtilleryRangeOverlay]).
 *
 * Hatching rather than a flat wash because the two envelopes overlap over most of a front line and
 * a fill would simply hide whichever was drawn second. The strokes run in OPPOSITE diagonals, so a
 * contested hex reads as a cross-hatch without either layer needing transparency tricks, and the
 * pattern is anchored to the canvas rather than to each hex, so the lines continue straight across
 * the envelope instead of restarting at every hex boundary.
 *
 * Drawn by [MapRenderer] before [HexCellRenderer], i.e. under the unit sprites and under every
 * selection overlay: this is a planning aid, and it must never obscure the thing being planned.
 */
internal class ArtilleryRangeRenderer(
    private val rc: RenderContext,
) {
    companion object {
        private const val TILE = 10.0
        private const val STROKE_WIDTH = 2.0

        /** Own guns: the HUD's warning red, at the weight the minefield fill uses. */
        private const val OWN_COLOR = "rgba(214, 64, 52, 0.55)"

        /** Enemy guns: black, per the request -- and the one ink on this map that no other overlay
         *  claims, so it cannot be mistaken for a selection or a threat ring. */
        private const val ENEMY_COLOR = "rgba(8, 8, 10, 0.55)"
    }

    private var ownStyle: dynamic = null
    private var enemyStyle: dynamic = null

    /** Hatches ([row], [col]) if either side's guns cover it. [x]/[y] are the hex's screen anchor. */
    fun drawCell(
        frame: RenderFrame,
        row: Int,
        col: Int,
        x: Double,
        y: Double,
    ) {
        val coverage = frame.artilleryCoverage ?: return
        val key = coverage.key(row, col, frame.cols)
        if (key in coverage.own) {
            rc.drawHex(rc.hexesCtx, x, y, style(own = true))
        }
        if (key in coverage.enemy) {
            rc.drawHex(rc.hexesCtx, x, y, style(own = false))
        }
    }

    /** The cached fill style for one side, built on first use (it needs a live 2D context). */
    private fun style(own: Boolean): dynamic {
        val cached = if (own) ownStyle else enemyStyle
        if (cached != null) return cached
        val built =
            json(
                "fillColor" to hatchPattern(if (own) OWN_COLOR else ENEMY_COLOR, forward = own),
                "lineColor" to null,
                // 0 makes [HexDrawer.stroke] return before touching the context: the hatch is a
                // fill only, so it composites under whatever ring the hex already carries.
                "lineWidth" to 0,
                "lineJoin" to "miter",
            )
        if (own) ownStyle = built else enemyStyle = built
        return built
    }

    /**
     * A repeating diagonal-stripe `CanvasPattern` in [color], leaning `/` when [forward].
     *
     * The tile carries the main diagonal plus the two corner stubs that complete the stripes its
     * neighbouring tiles start, which is what makes the repeat seamless.
     */
    private fun hatchPattern(
        color: String,
        forward: Boolean,
    ): dynamic {
        val tile = document.createElement("canvas") as HTMLCanvasElement
        tile.width = TILE.toInt()
        tile.height = TILE.toInt()
        val ctx = tile.getContext("2d").asDynamic()
        ctx.strokeStyle = color
        ctx.lineWidth = STROKE_WIDTH
        ctx.beginPath()
        if (forward) {
            ctx.moveTo(0.0, TILE)
            ctx.lineTo(TILE, 0.0)
            ctx.moveTo(-1.0, 1.0)
            ctx.lineTo(1.0, -1.0)
            ctx.moveTo(TILE - 1.0, TILE + 1.0)
            ctx.lineTo(TILE + 1.0, TILE - 1.0)
        } else {
            ctx.moveTo(0.0, 0.0)
            ctx.lineTo(TILE, TILE)
            ctx.moveTo(-1.0, TILE - 1.0)
            ctx.lineTo(1.0, TILE + 1.0)
            ctx.moveTo(TILE - 1.0, -1.0)
            ctx.lineTo(TILE + 1.0, 1.0)
        }
        ctx.stroke()
        return rc.hexesCtx.createPattern(tile, "repeat")
    }
}
