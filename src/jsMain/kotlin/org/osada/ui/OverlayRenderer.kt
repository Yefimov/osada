package org.osada.ui

import org.osada.TerrainType
import org.osada.uiSettings
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.model.getPlayer

/**
 * Draws hex flags and victory-hex markers. Extracted from the former `Render`
 * god-class; uses the cached flag image and geometry from the shared [RenderContext].
 */
internal class OverlayRenderer(
    private val rc: RenderContext,
) {
    companion object {
        // The victory-hex marker is three concentric strokeRects around the flag icon (red,
        // green, black), each pair (inset, pad) growing the rect by pad = 2 * inset on every side.
        private const val BORDER_INNER_INSET = 1.0
        private const val BORDER_INNER_PAD = 2.0
        private const val BORDER_MIDDLE_INSET = 3.0
        private const val BORDER_MIDDLE_PAD = 6.0
        private const val BORDER_OUTER_INSET = 4.0
        private const val BORDER_OUTER_PAD = 8.0

        // A hidden objective (flag-less owned victory hex, revealed via the owner's flag rather
        // than its own) gets a thicker border than a normally-flagged victory hex.
        private const val HIDDEN_OBJECTIVE_BORDER_WIDTH = 6.0
    }

    /** A map-independent facility sign for painted maps whose artwork omits the runway. */
    @Suppress("MagicNumber")
    fun drawTerrainFacility(
        ctx: dynamic,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        if (!uiSettings.airMode || hex.terrain != TerrainType.AIRFIELD.value) return
        val cx = x + 3.0
        val cy = y + 13.0
        ctx.save()
        ctx.beginPath()
        ctx.arc(cx, cy, 9.0, 0.0, 2.0 * kotlin.math.PI)
        ctx.fillStyle = "rgba(15,18,20,.78)"
        ctx.fill()
        ctx.strokeStyle = "rgba(221,174,68,.95)"
        ctx.lineWidth = 2.0
        ctx.stroke()
        ctx.translate(cx, cy)
        ctx.rotate(-0.55)
        ctx.beginPath()
        ctx.moveTo(-5.0, -2.0)
        ctx.lineTo(5.0, -2.0)
        ctx.moveTo(-5.0, 2.0)
        ctx.lineTo(5.0, 2.0)
        ctx.moveTo(-5.0, -4.0)
        ctx.lineTo(-5.0, 4.0)
        ctx.moveTo(5.0, -4.0)
        ctx.lineTo(5.0, 4.0)
        ctx.strokeStyle = "#f2d184"
        ctx.lineWidth = 1.5
        ctx.stroke()
        ctx.restore()
    }

    /** Draws the red/green/black concentric victory-hex border around the flag at ([fx], [fy]). */
    private fun drawVictoryBorder(
        ctx: dynamic,
        fx: Double,
        fy: Double,
        lineWidth: Double,
    ) {
        ctx.beginPath()
        ctx.lineWidth = lineWidth
        ctx.strokeStyle = "rgba(139,0,0,1)"
        ctx.strokeRect(
            fx - BORDER_INNER_INSET,
            fy - BORDER_INNER_INSET,
            rc.flagIconWidth + BORDER_INNER_PAD,
            rc.flagIconHeight + BORDER_INNER_PAD,
        )
        ctx.strokeStyle = "rgba(127,255,0,1)"
        ctx.strokeRect(
            fx - BORDER_MIDDLE_INSET,
            fy - BORDER_MIDDLE_INSET,
            rc.flagIconWidth + BORDER_MIDDLE_PAD,
            rc.flagIconHeight + BORDER_MIDDLE_PAD,
        )
        ctx.strokeStyle = "rgba(0,0,0,1)"
        ctx.lineWidth = 1.0
        ctx.strokeRect(
            fx - BORDER_OUTER_INSET,
            fy - BORDER_OUTER_INSET,
            rc.flagIconWidth + BORDER_OUTER_PAD,
            rc.flagIconHeight + BORDER_OUTER_PAD,
        )
        ctx.closePath()
    }

    fun drawFlags(
        ctx: dynamic,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        if (hex.flag == -1) return
        if (rc.flagImage == null || rc.flagImage == undefined) return

        val fx = x + rc.hexTopWidth / 2.0 - rc.flagIconWidth / 2.0
        val fy = y + 2.0 * rc.v - rc.flagIconHeight - 2.0
        ctx.drawImage(
            rc.flagImage,
            rc.flagIconWidth * hex.flag,
            0.0,
            rc.flagIconWidth,
            rc.flagIconHeight,
            fx,
            fy,
            rc.flagIconWidth,
            rc.flagIconHeight,
        )

        if (hex.victorySide != -1) {
            drawVictoryBorder(ctx, fx, fy, lineWidth = 2.0)
        }
    }

    fun drawVictoryHexes(
        ctx: dynamic,
        hex: Hex,
        x: Double,
        y: Double,
        q: GameMap,
    ) {
        if (rc.flagImage == null || rc.flagImage == undefined) return
        if (hex.flag == -1 && hex.victorySide == -1) return
        val fx = x + rc.hexTopWidth / 2.0 - rc.flagIconWidth / 2.0
        val fy = y + 2.0 * rc.v - rc.flagIconHeight - 2.0

        // With "show hidden victory hexes" on, we still draw the NORMAL flag on every owned hex
        // (matching PM) — the earlier early-return on non-victory hexes made all ordinary city
        // flags disappear the moment the setting was enabled. Victory hexes additionally get the
        // border; a flag-less owned victory hex is a HIDDEN objective revealed via the owner's flag.
        var flag = hex.flag
        if (hex.victorySide != -1) {
            var lineWidth = 2.0
            if (flag == -1 && hex.owner != -1) {
                flag = q.getPlayer(hex.owner).country
                lineWidth = HIDDEN_OBJECTIVE_BORDER_WIDTH
            }
            drawVictoryBorder(ctx, fx, fy, lineWidth)
        }
        if (flag != -1) {
            ctx.drawImage(
                rc.flagImage,
                rc.flagIconWidth * flag,
                0.0,
                rc.flagIconWidth,
                rc.flagIconHeight,
                fx,
                fy,
                rc.flagIconWidth,
                rc.flagIconHeight,
            )
        }
    }
}
