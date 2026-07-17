package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.Hex

/**
 * Draws hex flags and victory-hex markers. Extracted from the former `Render`
 * god-class; uses the cached flag image and geometry from the shared [RenderContext].
 */
internal class OverlayRenderer(private val rc: RenderContext) {

    fun drawFlags(ctx: dynamic, hex: Hex, x: Double, y: Double, q: GameMap) {
        if (hex.flag == -1) return
        if (rc.flagImage == null || rc.flagImage == undefined) return

        val fx = x + rc.S / 2.0 - rc.A / 2.0
        val fy = y + 2.0 * rc.v - rc.O - 2.0
        ctx.drawImage(rc.flagImage, rc.A * hex.flag, 0.0, rc.A, rc.O, fx, fy, rc.A, rc.O)

        if (hex.victorySide != -1) {
            ctx.beginPath()
            ctx.lineWidth = 2.0
            ctx.strokeStyle = "rgba(139,0,0,1)"
            ctx.strokeRect(fx - 1.0, fy - 1.0, rc.A + 2.0, rc.O + 2.0)
            ctx.strokeStyle = "rgba(127,255,0,1)"
            ctx.strokeRect(fx - 3.0, fy - 3.0, rc.A + 6.0, rc.O + 6.0)
            ctx.strokeStyle = "rgba(0,0,0,1)"
            ctx.lineWidth = 1.0
            ctx.strokeRect(fx - 4.0, fy - 4.0, rc.A + 8.0, rc.O + 8.0)
            ctx.closePath()
        }
    }

    fun drawVictoryHexes(ctx: dynamic, hex: Hex, x: Double, y: Double, q: GameMap) {
        if (rc.flagImage == null || rc.flagImage == undefined) return
        if (hex.flag == -1 && hex.victorySide == -1) return
        val fx = x + rc.S / 2.0 - rc.A / 2.0
        val fy = y + 2.0 * rc.v - rc.O - 2.0

        // With "show hidden victory hexes" on, we still draw the NORMAL flag on every owned hex
        // (matching PM) — the earlier early-return on non-victory hexes made all ordinary city
        // flags disappear the moment the setting was enabled. Victory hexes additionally get the
        // border; a flag-less owned victory hex is a HIDDEN objective revealed via the owner's flag.
        var flag = hex.flag
        if (hex.victorySide != -1) {
            var lineWidth = 2.0
            if (flag == -1 && hex.owner != -1) {
                flag = q.getPlayer(hex.owner).country
                lineWidth = 6.0
            }
            ctx.beginPath()
            ctx.lineWidth = lineWidth
            ctx.strokeStyle = "rgba(139,0,0,1)"
            ctx.strokeRect(fx - 1.0, fy - 1.0, rc.A + 2.0, rc.O + 2.0)
            ctx.strokeStyle = "rgba(127,255,0,1)"
            ctx.strokeRect(fx - 3.0, fy - 3.0, rc.A + 6.0, rc.O + 6.0)
            ctx.strokeStyle = "rgba(0,0,0,1)"
            ctx.lineWidth = 1.0
            ctx.strokeRect(fx - 4.0, fy - 4.0, rc.A + 8.0, rc.O + 8.0)
            ctx.closePath()
        }
        if (flag != -1) {
            ctx.drawImage(rc.flagImage, rc.A * flag, 0.0, rc.A, rc.O, fx, fy, rc.A, rc.O)
        }
    }
}
