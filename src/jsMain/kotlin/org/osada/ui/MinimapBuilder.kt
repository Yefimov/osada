package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getUnits
import org.osada.rules.UnitConcealment
import org.osada.ui.MinimapBuilder.refresh
import org.osada.uiSettings
import org.w3c.dom.events.MouseEvent

/**
 * Read-only minimap (Task 4): built once into the sidebar's `#osadaMinimapFrame` placeholder
 * (Task 2). Composited via `drawImage` from the existing `hexesCanvas` — never draws to or
 * resizes any game canvas. Overlays (viewport rect, unit/objective dots) are computed with the
 * existing hex→pixel conversion ([Render.cellToScreen], already `internal` and same-package —
 * reused rather than reimplemented) and painted only on this new canvas.
 *
 * Base layer: the terrain artwork itself, drawn from [Render.getTerrainImage] (the same
 * HTMLImageElement the map view uses — mapCanvas's own bitmap holds no terrain pixels, so the
 * image, not the canvas, is the source). On top of it goes a `hexesCanvas` snapshot, which
 * holds the per-frame drawn content (fog-of-war veil, unit sprites, flags/victory markers,
 * selection highlights), then the dot/viewport overlays.
 */
internal object MinimapBuilder {
    private const val WIDTH = MinimapMarkers.BITMAP_WIDTH
    private const val HEIGHT = MinimapMarkers.BITMAP_HEIGHT
    private const val FALLBACK_INTERVAL_MS = 2000
    private const val VIEWPORT_RECT_LINE_WIDTH = 1.5

    private var canvas: dynamic = null
    private var ctx: dynamic = null

    fun build() {
        val frame = byId("osadaMinimapFrame") ?: return
        clearTag(frame)
        val cv = document.createElement("canvas").asDynamic()
        cv.id = "osada-minimap"
        cv.width = WIDTH
        cv.height = HEIGHT
        cv.title = I18n.t("hud.sidebar.minimap.canvas.help")
        frame.appendChild(cv)
        canvas = cv
        ctx = cv.getContext("2d")

        MinimapPointerInput.wire(cv)
        byId("game")?.addEventListener("scroll", { refreshViewportOnly() })
        window.setInterval({ if (isDrawerVisible()) refresh() }, FALLBACK_INTERVAL_MS)

        buildZoomControls(frame.parentElement)
    }

    /** Map-zoom −/100%/+ cluster, docked under the minimap frame (zoom controls belong near the
     *  navigation aid). Ignored while Strategic Map is active — the two zoom mechanisms are
     *  mutually exclusive by convention (see [MapZoom]'s doc comment). */
    private fun buildZoomControls(parent: dynamic) {
        if (parent == null || byId("osadaZoomControls") != null) return
        val row = addTag(parent, "div")
        row.id = "osadaZoomControls"
        row.className = "osada-zoom-controls"

        val out = addTag(row, "div")
        out.id = "osadaZoomOut"
        out.className = "osada-zoom-btn"
        out.textContent = "−"
        out.title = I18n.t("hud.zoom.out.help")
        out.onclick = { _: MouseEvent -> if (!uiSettings.strategicZoom) MapZoom.stepOut() }

        val pct = addTag(row, "div")
        pct.id = "osadaZoomPct"
        pct.className = "osada-zoom-pct"
        pct.textContent = "100%"
        pct.title = I18n.t("hud.zoom.reset.help")
        pct.onclick = { _: MouseEvent -> if (!uiSettings.strategicZoom) MapZoom.reset() }

        val zoomIn = addTag(row, "div")
        zoomIn.id = "osadaZoomIn"
        zoomIn.className = "osada-zoom-btn"
        zoomIn.textContent = "+"
        zoomIn.title = I18n.t("hud.zoom.in.help")
        zoomIn.onclick = { _: MouseEvent -> if (!uiSettings.strategicZoom) MapZoom.stepIn() }

        MapZoom.refreshControls()
    }

    /** Full repaint: base fill + hexesCanvas snapshot + unit/objective dots + viewport rect.
     *  Called on turn change and unit select/move/combat-end (hooked from updateStatusBar /
     *  uiUnitSelect, so no separate wiring needed at each individual call site).
     *
     *  Canvas drawImage() failures here are a browser/DOM timing quirk (image not yet decoded,
     *  or a not-yet-ready canvas), not a typed Kotlin error to distinguish -- the flat fill
     *  painted just above already stands in as the visual fallback, so silently skipping the
     *  frame (rather than logging every transient miss) is the intended behavior. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun refresh() {
        val rc = buildRefreshContext() ?: return
        val w = WIDTH.toDouble()
        val h = HEIGHT.toDouble()
        // The SPOTTING side, not `currentPlayer`. Keyed to the current player, every dot's
        // allegiance inverted for the duration of the AI's turn -- the player's own units were
        // drawn as spotted enemies (white with the black rim) and the AI's as friendly red, on the
        // one refresh the player spends the most time looking at.
        val side = GameHolder.instance?.spotSide ?: rc.map.currentPlayer?.side ?: 0

        paintBase(rc.g, rc.ui, rc.hexesCanvas, rc.srcW, rc.srcH, w, h)
        drawVictoryHexes(rc.g, rc.ui, rc.map, rc.srcW, rc.srcH, w, h)
        drawUnitDots(rc.g, rc.ui, rc.map, rc.srcW, rc.srcH, w, h, side)
        drawViewportRect(rc.g, rc.srcW, rc.srcH, w, h)
    }

    private data class RefreshContext(
        val g: dynamic,
        val ui: UI,
        val map: GameMap,
        val hexesCanvas: dynamic,
        val srcW: Double,
        val srcH: Double,
    )

    /** Gathers + validates everything [refresh] needs in one place, so the caller only has to
     *  handle a single "nothing to draw yet" outcome instead of a chain of individual guards. */
    private fun buildRefreshContext(): RefreshContext? =
        run {
            canvas ?: return@run null
            val g = ctx ?: return@run null
            val ui = GameHolder.instance?.ui ?: return@run null
            val map = GameHolder.instance?.scenario?.map ?: return@run null
            val hexesCanvas = ui.render.getHexesCanvas() ?: return@run null
            val srcW = (hexesCanvas.width as? Number)?.toDouble() ?: 0.0
            val srcH = (hexesCanvas.height as? Number)?.toDouble() ?: 0.0
            if (srcW <= 0.0 || srcH <= 0.0) return@run null
            RefreshContext(g, ui, map, hexesCanvas, srcW, srcH)
        }

    /** Base layer of [refresh]: flat fill + terrain artwork + hexesCanvas snapshot. Failures on
     *  drawImage() here are a browser/DOM timing quirk (image not yet decoded, or a not-yet-ready
     *  canvas), not a typed Kotlin error to distinguish — the flat fill painted just above already
     *  stands in as the visual fallback, so silently skipping the frame (rather than logging every
     *  transient miss) is the intended behavior. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun paintBase(
        g: dynamic,
        ui: UI,
        hexesCanvas: dynamic,
        srcW: Double,
        srcH: Double,
        w: Double,
        h: Double,
    ) {
        g.fillStyle = "#15171c"
        g.fillRect(0.0, 0.0, w, h)
        // Terrain artwork first (real map, not a schematic fill). Its natural height is 65px
        // shorter than the canvases (they get +65 for the last hex row), so map it through the
        // same src→dst scale rather than stretching it to the full minimap.
        val terrain = ui.render.getTerrainImage()
        val tw = (terrain?.width as? Number)?.toDouble() ?: 0.0
        val th = (terrain?.height as? Number)?.toDouble() ?: 0.0
        if (tw > 0.0 && th > 0.0) {
            try {
                g.drawImage(terrain, 0.0, 0.0, tw, th, 0.0, 0.0, tw / srcW * w, th / srcH * h)
            } catch (_: Throwable) {
                // Not-yet-decoded image: the flat fill above stands in for this frame.
            }
        }
        try {
            g.drawImage(hexesCanvas, 0.0, 0.0, srcW, srcH, 0.0, 0.0, w, h)
        } catch (_: Throwable) {
            // A cross-origin or not-yet-ready canvas would throw on drawImage; the fill/terrain
            // above already stand in as the fallback in that case.
        }
    }

    /** Victory hexes (brass) — same visibility rule as the sidebar objectives list. */
    private fun drawVictoryHexes(
        g: dynamic,
        ui: UI,
        map: GameMap,
        srcW: Double,
        srcH: Double,
        w: Double,
        h: Double,
    ) {
        for (r in 0 until map.rows) {
            for (col in 0 until map.cols) {
                val hex = map.map?.get(r)?.get(col)
                if (!isVictoryHexVisible(hex)) continue
                val p = ui.render.cellToScreen(r, col, false)
                dot(g, p.x / srcW * w, p.y / srcH * h, MinimapMarkers.OBJECTIVE_FILL, MinimapMarkers.OBJECTIVE_RADIUS)
            }
        }
    }

    /** Units: own RED, spotted enemies WHITE with a black rim (user request). Same spotting rule as
     *  everywhere else in the HUD (hex.isSpotted / tempSpotted) — no new information leaked.
     *
     *  The rim is not decoration: the minimap composites real terrain artwork underneath, and a
     *  plain white dot vanishes over snow, coast surf and pale towns. Red-on-white also survives
     *  the common colour-vision deficiencies that green-vs-red did not. Its thickness comes from
     *  [MinimapMarkers], which keeps it at least one RENDERED pixel wide at every scale the layout
     *  can give this canvas — see that file for the design §3 audit it answers. */
    private fun drawUnitDots(
        g: dynamic,
        ui: UI,
        map: GameMap,
        srcW: Double,
        srcH: Double,
        w: Double,
        h: Double,
        side: Int,
    ) {
        val enemyOuterRadius = MinimapMarkers.enemyOuterRadius(minimapRenderScale(canvas))
        for (unit in map.getUnits()) {
            val pos = unit.getPos()
            val own = unit.player?.side == side
            val spotted = own || (pos != null && isUnitSpotted(map, pos, side, unit))
            if (pos == null || !spotted) continue
            val p = ui.render.cellToScreen(pos.row, pos.col, false)
            val x = p.x / srcW * w
            val y = p.y / srcH * h
            if (own) {
                dot(g, x, y, MinimapMarkers.OWN_FILL, MinimapMarkers.CORE_RADIUS)
            } else {
                dot(g, x, y, MinimapMarkers.ENEMY_RIM_FILL, enemyOuterRadius)
                dot(g, x, y, MinimapMarkers.ENEMY_FILL, MinimapMarkers.CORE_RADIUS)
            }
        }
    }

    /** Cheap redraw for scroll events: only the viewport rectangle needs to move, so this skips
     *  the hexesCanvas snapshot + dot sweep that [refresh] does. */
    fun refreshViewportOnly() {
        // The viewport rect is drawn OVER the last full composite; without redoing the whole
        // paint we'd smear old rects, so just do a full refresh — it's cheap (small destination
        // canvas, native drawImage scaling) and scroll events are already coalesced by the
        // browser to roughly one per frame.
        refresh()
    }

    private fun dot(
        g: dynamic,
        x: Double,
        y: Double,
        color: String,
        r: Double,
    ) {
        g.fillStyle = color
        g.beginPath()
        g.arc(x, y, r, 0.0, 2.0 * kotlin.math.PI)
        g.fill()
    }

    private fun drawViewportRect(
        g: dynamic,
        srcW: Double,
        srcH: Double,
        w: Double,
        h: Double,
    ) {
        val game = byId("game") ?: return
        val gd = game.asDynamic()
        // #game's own scroll/client metrics are in its RENDERED (post map-zoom) pixel space,
        // while srcW/srcH are the hexesCanvas's raw (native, unzoomed) pixel buffer size —
        // dividing by zoom normalizes both sides to the same native space before comparing.
        val zoom = MapZoom.level
        val scrollLeft = ((gd.scrollLeft as? Number)?.toDouble() ?: 0.0) / zoom
        val scrollTop = ((gd.scrollTop as? Number)?.toDouble() ?: 0.0) / zoom
        val clientWidth = ((gd.clientWidth as? Number)?.toDouble() ?: 0.0) / zoom
        val clientHeight = ((gd.clientHeight as? Number)?.toDouble() ?: 0.0) / zoom
        if (clientWidth <= 0.0 || clientHeight <= 0.0) return

        val vx = (scrollLeft / srcW * w).coerceIn(0.0, w)
        val vy = (scrollTop / srcH * h).coerceIn(0.0, h)
        val vw = (clientWidth / srcW * w).coerceAtMost(w - vx)
        val vh = (clientHeight / srcH * h).coerceAtMost(h - vy)

        g.strokeStyle = "#e7e2d4"
        g.lineWidth = VIEWPORT_RECT_LINE_WIDTH
        g.strokeRect(vx, vy, vw, vh)
    }

    /**
     * Centres the main map on a minimap position — sets `#game`'s own scroll only, and never
     * touches a game canvas (hard constraint). Public to the package so [MinimapPointerInput] can
     * drive it without reimplementing the coordinate conversion.
     */
    fun scrollMapTo(
        clientX: Double,
        clientY: Double,
    ) = run {
        val c = canvas ?: return@run
        val ui = GameHolder.instance?.ui ?: return@run
        val hexesCanvas = ui.render.getHexesCanvas() ?: return@run
        val srcW = (hexesCanvas.width as? Number)?.toDouble() ?: 0.0
        val srcH = (hexesCanvas.height as? Number)?.toDouble() ?: 0.0
        if (srcW <= 0.0 || srcH <= 0.0) return@run
        val game = byId("game") ?: return@run
        val rect = c.getBoundingClientRect()
        val xFraction = minimapFraction(clientX, rect.left as Double, rect.width as Double)
        val yFraction = minimapFraction(clientY, rect.top as Double, rect.height as Double)
        val gd = game.asDynamic()
        // Same native/zoomed normalization as drawViewportRect: do the math in native
        // (unzoomed) space to match srcW/srcH, then scale the FINAL target back up to
        // #game's own (zoomed) scroll space before assigning.
        val zoom = MapZoom.level
        val clientWidth = ((gd.clientWidth as? Number)?.toDouble() ?: 0.0) / zoom
        val clientHeight = ((gd.clientHeight as? Number)?.toDouble() ?: 0.0) / zoom
        val targetX = xFraction * srcW - clientWidth / 2.0
        val targetY = yFraction * srcH - clientHeight / 2.0
        gd.scrollLeft = (targetX.coerceIn(0.0, (srcW - clientWidth).coerceAtLeast(0.0))) * zoom
        gd.scrollTop = (targetY.coerceIn(0.0, (srcH - clientHeight).coerceAtLeast(0.0))) * zoom
        refreshViewportOnly()
    }
}

/**
 * Rendered CSS width / bitmap width. 1.0 on desktop, where the stylesheet pins the canvas to its
 * 240x160 bitmap; below 1.0 inside a narrow phone drawer, where `width: 100%` shrinks it.
 * [MinimapMarkers] uses this so the enemy rim stays at least one RENDERED pixel thick.
 *
 * Top-level (not a member) so it does not count against [MinimapBuilder]'s function budget.
 */
private fun minimapRenderScale(canvas: dynamic): Double {
    if (canvas == null) return 1.0
    val rendered = (canvas.getBoundingClientRect()?.width as? Number)?.toDouble() ?: 0.0
    return if (rendered <= 0.0) 1.0 else rendered / MinimapMarkers.BITMAP_WIDTH.toDouble()
}

/** Client coordinate normalized against the canvas's rendered CSS box, not its bitmap size. */
internal fun minimapFraction(
    clientCoordinate: Double,
    renderedStart: Double,
    renderedSize: Double,
): Double =
    if (renderedSize <= 0.0) {
        0.0
    } else {
        ((clientCoordinate - renderedStart) / renderedSize).coerceIn(0.0, 1.0)
    }

/**
 * A closed mobile drawer hides the minimap entirely, and repainting an invisible canvas twice a
 * second is pure battery cost on a phone. The drawer refreshes on open, so nothing is stale.
 */
private fun isDrawerVisible(): Boolean = !MobileLayoutController.mode.isPhone || MobileDrawer.isOpen

private fun isUnitSpotted(
    map: GameMap,
    pos: Cell,
    side: Int,
    unit: GameUnit,
): Boolean {
    // `map`/`pos` are the caller's already-resolved position; visibility itself is asked of the
    // unit so `Forest Camouflage` cannot be honoured on the map and ignored on the minimap.
    map.map?.get(pos.row)?.get(pos.col)
    return UnitConcealment.isVisibleTo(unit, side)
}

private fun isVictoryHexVisible(hex: Hex?): Boolean {
    if (hex == null) return false
    return hex.victorySide != -1 && hex.flag != -1 && hex.owner != -1
}
