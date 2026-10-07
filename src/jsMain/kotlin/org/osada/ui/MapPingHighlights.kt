package org.osada.ui

import kotlinx.browser.document
import org.osada.GameHolder
import org.w3c.dom.HTMLElement

/**
 * A teammate's ping outlined on the battle map itself, for everyone in the room. A DOM layer inside
 * `#game` rather than a canvas pass, so no renderer has to know about presence; [MinimapPings]
 * re-places it on each tick, which also follows zoom changes.
 *
 * Pings travel as a point normalized to the whole map (the minimap's coordinates), so a hex is sent
 * as its CENTRE and found again on the other side as the hex whose centre is nearest.
 */
internal object MapPingHighlights {
    private const val LAYER_ID = "osadaPingLayer"
    private const val LABEL_LENGTH = 18
    private const val OUTLINE_MARGIN = 2.0

    data class Spot(
        val row: Int,
        val col: Int,
        val label: String,
    )

    /** The hex's centre as a fraction of the whole map, or null before a map is drawn. */
    fun normalizedCentre(
        row: Int,
        col: Int,
    ): Pair<Double, Double>? {
        val render = GameHolder.instance?.ui?.render
        val size = render?.let(::canvasSize)
        if (render == null || size == null) return null
        val p = render.cellToScreen(row, col, false)
        return (p.x + render.ctx.hexTopWidth / 2.0) / size.first to (p.y + render.ctx.v) / size.second
    }

    fun nearestCell(
        fx: Double,
        fy: Double,
    ): Pair<Int, Int>? {
        val render = GameHolder.instance?.ui?.render
        val map = GameHolder.instance?.scenario?.map
        val size = render?.let(::canvasSize)
        if (render == null || map == null || size == null) return null
        val px = fx * size.first
        val py = fy * size.second
        var best: Pair<Int, Int>? = null
        var bestDistance = Double.MAX_VALUE
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                val p = render.cellToScreen(r, c, false)
                val dx = p.x + render.ctx.hexTopWidth / 2.0 - px
                val dy = p.y + render.ctx.v - py
                val d = dx * dx + dy * dy
                if (d < bestDistance) {
                    bestDistance = d
                    best = r to c
                }
            }
        }
        return best
    }

    fun draw(spots: List<Spot>) {
        val render = GameHolder.instance?.ui?.render
        val layer = layer()
        if (render == null || layer == null) return
        layer.innerHTML = ""
        val zoom = MapZoom.level
        val rc = render.ctx
        val s = rc.hexSlantWidth
        val t = rc.hexTopWidth
        val v = rc.v
        val m = OUTLINE_MARGIN
        spots.forEach { spot ->
            val p = render.cellToScreen(spot.row, spot.col, true)
            val mark = document.createElement("div") as HTMLElement
            mark.className = "osada-ping-hex"
            mark.style.left = "${p.x - s * zoom}px"
            mark.style.top = "${p.y}px"
            mark.style.width = "${(t + 2.0 * s) * zoom}px"
            mark.style.height = "${2.0 * v * zoom}px"
            // Built only from the renderer's own numbers; the label goes in through textContent.
            mark.innerHTML =
                "<svg viewBox=\"${-m} ${-m} ${t + 2 * s + 2 * m} ${2 * v + 2 * m}\" preserveAspectRatio=\"none\">" +
                "<polygon points=\"$s,0 ${s + t},0 ${2 * s + t},$v ${s + t},${2 * v} $s,${2 * v} 0,$v\"/></svg>"
            val label = document.createElement("span") as HTMLElement
            label.className = "osada-ping-hex-label"
            label.textContent = spot.label.take(LABEL_LENGTH)
            mark.appendChild(label)
            layer.appendChild(mark)
        }
    }

    fun clear() {
        document.getElementById(LAYER_ID)?.remove()
    }

    private fun layer(): HTMLElement? =
        document.getElementById(LAYER_ID) as? HTMLElement
            ?: document.getElementById("game")?.let { game ->
                (document.createElement("div") as HTMLElement).also {
                    it.id = LAYER_ID
                    it.className = "osada-ping-layer"
                    it.setAttribute("aria-hidden", "true")
                    game.appendChild(it)
                }
            }

    private fun canvasSize(render: Render): Pair<Double, Double>? {
        val canvas = render.getHexesCanvas()
        val w = (canvas?.width as? Number)?.toDouble() ?: 0.0
        val h = (canvas?.height as? Number)?.toDouble() ?: 0.0
        return if (w > 0.0 && h > 0.0) w to h else null
    }
}
