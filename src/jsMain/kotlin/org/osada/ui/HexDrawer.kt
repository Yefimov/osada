package org.osada.ui

import org.osada.TerrainType
import org.osada.uiSettings

/**
 * Draws the shared hexagon outline + fill/stroke + optional terrain/deploy/move-hint glyphs.
 * Split from [RenderContext] purely to keep that class within the project's
 * function-count/complexity limits.
 */
internal class HexDrawer(
    private val rc: RenderContext,
) {
    companion object {
        private const val MOVE_GLYPH_X_OFFSET = 4.0
    }

    fun draw(
        ctx: dynamic,
        x: Double,
        y: Double,
        style: dynamic,
        terrain: Int? = null,
    ) {
        val showGridTerrain = uiSettings.showGridTerrain

        drawOutline(ctx, x, y)
        fill(ctx, style)
        ctx.closePath()
        stroke(ctx, style)

        if (showGridTerrain && terrain != null && terrain != TerrainType.CLEAR.value) {
            drawTerrainGlyph(ctx, x, y, terrain)
        }
        if (style === hexStyles["deploy"]) {
            drawDeployGlyph(ctx, x, y)
        }
        if (showGridTerrain && style === hexStyles["move"]) {
            drawMoveGlyph(ctx, x, y)
        }
    }

    private fun drawOutline(
        ctx: dynamic,
        x: Double,
        y: Double,
    ) {
        ctx.beginPath()
        ctx.moveTo(x, y)
        ctx.lineTo(x + rc.hexTopWidth, y)
        ctx.lineTo(x + rc.hexTopWidth + rc.hexSlantWidth, y + rc.v)
        ctx.lineTo(x + rc.hexTopWidth, y + 2 * rc.v)
        ctx.lineTo(x, y + 2 * rc.v)
        ctx.lineTo(x - rc.hexSlantWidth, y + rc.v)
    }

    private fun fill(
        ctx: dynamic,
        style: dynamic,
    ) {
        val fillColor = style.fillColor
        if (fillColor != null) {
            ctx.fillStyle = fillColor
            ctx.fill()
        }
    }

    private fun stroke(
        ctx: dynamic,
        style: dynamic,
    ) {
        val lw = (style.lineWidth as? Number)?.toDouble() ?: 0.0
        if (lw <= 0.0) return
        ctx.lineWidth = lw
        ctx.lineJoin = style.lineJoin as? String ?: "miter"
        ctx.strokeStyle = style.lineColor as? String ?: "transparent"
        applyShadow(ctx, style)
        ctx.stroke()
    }

    private fun applyShadow(
        ctx: dynamic,
        style: dynamic,
    ) {
        val shadowColor = style.shadowColor
        if (shadowColor != null) {
            ctx.shadowOffsetX = (style.shadowOffsetX as? Number)?.toDouble() ?: 0.0
            ctx.shadowOffsetY = (style.shadowOffsetY as? Number)?.toDouble() ?: 0.0
            ctx.shadowColor = shadowColor as String
        } else {
            ctx.shadowOffsetX = 0.0
            ctx.shadowOffsetY = 0.0
            ctx.shadowColor = "transparent"
        }
    }

    private fun drawTerrainGlyph(
        ctx: dynamic,
        x: Double,
        y: Double,
        terrain: Int,
    ) {
        val tx = x + rc.hexSlantWidth + rc.hexSlantWidth / 2.0 - 2.0
        val ty = y + 2.0 * rc.v - 12.0
        ctx.font = "24px osada, sans-serif"
        ctx.strokeStyle = unitStyles["terrainTextStroke"] as? String ?: "#f8e064"
        ctx.lineWidth = 1.0
        ctx.strokeText(terrainEncoding.getOrElse(terrain) { "?" }, tx - 1.0, ty - 1.0)
        ctx.fillStyle = unitStyles["terrainText"] as? String ?: "#333333"
        ctx.fillText(terrainEncoding.getOrElse(terrain) { "?" }, tx, ty)
    }

    private fun drawDeployGlyph(
        ctx: dynamic,
        x: Double,
        y: Double,
    ) {
        val tx = x + 4.0
        val ty = y + 2.0 * rc.v - 12.0
        ctx.font = "24px osada, sans-serif"
        ctx.strokeStyle = unitStyles["terrainTextStroke"] as? String ?: "#f8e064"
        ctx.lineWidth = 1.0
        ctx.strokeText("Z", tx - 1.0, ty - 1.0)
        ctx.fillStyle = unitStyles["terrainText"] as? String ?: "#333333"
        ctx.fillText("Z", tx, ty)
    }

    private fun drawMoveGlyph(
        ctx: dynamic,
        x: Double,
        y: Double,
    ) {
        val ty = y + 2.0 * rc.v - 12.0
        ctx.font = "26px osada-menu, sans-serif"
        ctx.strokeStyle = unitStyles["terrainText"] as? String ?: "#333333"
        ctx.lineWidth = 1.0
        ctx.strokeText("'", x + MOVE_GLYPH_X_OFFSET - 1.0, ty - 1.0)
    }
}
