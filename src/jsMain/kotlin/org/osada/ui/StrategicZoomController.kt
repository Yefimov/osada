package org.osada.ui

import org.osada.uiSettings

/**
 * Strategic (zoomed-out) map view toggle. Split from the former `MenuController` god-class to
 * stay within the project's function-count/class-size limits.
 */
internal class StrategicZoomController(
    private val ui: UI,
) {
    private val strategicZoomPercentBase = 100.0

    fun toggleStrategicZoom() {
        val gameDiv = byId("game") ?: return
        val metrics = ViewportMetricsService.refresh()
        if (uiSettings.strategicZoom) {
            gameDiv.style.width = "${metrics.usableWidth.toInt()}px"
            gameDiv.style.height = "${metrics.usableHeight.toInt()}px"
            // .style.zoom, NOT a bare .zoom on the element: `zoom` isn't a standard reflected
            // IDL attribute, so `gameDiv.asDynamic().zoom = ...` (the pre-existing code here)
            // only ever set a meaningless custom expando property — no CSS zoom was EVER applied,
            // so Strategic Map has been visually non-functional (confirmed via getComputedStyle:
            // stayed at 1/100% regardless). `.style` is a real CSSStyleDeclaration; `zoom` isn't
            // in Kotlin's typed binding for it (non-standard property), so it needs .asDynamic()
            // on THAT (a legitimate typed→dynamic cast, unlike casting the element itself).
            gameDiv.style.asDynamic().zoom = "100%"
            gameDiv.style.transform = ""
            uiSettings.strategicZoom = false
            uiSettings.strategicZoomLevel = 1.0
            // Drop the backdrop and hand #game's geometry (width/height/left/top) back to its
            // owner — positionLayers is the single place that computes the normal-view layout,
            // so reverting means re-running it, not re-deriving those numbers here.
            byId("mainbody")?.classList?.remove("osada-strategic")
            ui.render.positionLayers()
        } else {
            val mapCanvas: dynamic = ui.render.getMapCanvas()
            // The overview must fit the area the map actually gets — not the whole window. The
            // top bar, the bottom dock and any device cutout are all excluded by usableWidth/
            // usableHeight, so a phone in landscape shows the whole theatre rather than sliding a
            // third of it under the HUD.
            val mapWidth = mapCanvas?.width as? Int ?: metrics.usableWidth.toInt()
            val mapHeight = mapCanvas?.height as? Int ?: metrics.usableHeight.toInt()
            val scaleX = 100.0 * metrics.usableWidth / (mapWidth * uiSettings.zoomLevel)
            val scaleY = 100.0 * metrics.usableHeight / (mapHeight * uiSettings.zoomLevel)
            val percent = minOf(scaleX, scaleY)
            gameDiv.style.asDynamic().zoom = "${percent.toInt()}%"
            // Size #game to the MAP, not to the viewport (which is what left the shrunken map in
            // the corner of a full-viewport box, with the rest of it reading as a gray void). These
            // are pre-zoom lengths: CSS `zoom` multiplies them, so the rendered box comes out at
            // map * zoomLevel * percent — which fits the viewport by construction of `percent`.
            gameDiv.style.width = "${(mapWidth * uiSettings.zoomLevel).toInt()}px"
            gameDiv.style.height = "${(mapHeight * uiSettings.zoomLevel).toInt()}px"
            uiSettings.strategicZoom = true
            uiSettings.strategicZoomLevel = strategicZoomPercentBase / percent
            byId("mainbody")?.classList?.add("osada-strategic")
            centerStrategicMap(gameDiv)
        }
        byId("zoom")?.let { toggleButton(it, uiSettings.strategicZoom) }
    }

    /**
     * Centers #game (the whole map box) in the viewport for strategic view.
     *
     * Centering the box itself — rather than the canvases inside it — is deliberate: the attack-ring
     * overlay and the hex-name tooltips are absolutely positioned children of #game in MAP
     * coordinates, so anything that shifts the canvases alone would desync them. Moving #game moves
     * all of them together.
     *
     * The offset is MEASURED, not derived: #game carries a CSS `zoom`, and how zoom scales an
     * element's own `left`/`top` (as opposed to its width/height) is exactly the kind of detail that
     * differs between engines. So probe it — read the rendered rect at left/top = 0, again at a known
     * offset, and solve for the px-per-css-px factor. Two forced layouts, once per toggle.
     */
    private fun centerStrategicMap(gameDiv: org.w3c.dom.HTMLElement) {
        val metrics = ViewportMetricsService.current
        val originLeft = metrics.safeLeft
        val originTop = metrics.safeTop + metrics.topBarHeight
        val style = gameDiv.style
        style.left = "0px"
        style.top = "0px"
        val base = gameDiv.getBoundingClientRect()
        val probe = 100.0
        style.left = "${probe}px"
        style.top = "${probe}px"
        val moved = gameDiv.getBoundingClientRect()
        val scaleX = (moved.left - base.left) / probe
        val scaleY = (moved.top - base.top) / probe
        // A zero factor would mean `left`/`top` don't move the box at all (some future engine
        // quirk) — leave it where positionLayers put it rather than dividing by zero.
        if (scaleX == 0.0 || scaleY == 0.0) {
            style.left = "${originLeft}px"
            style.top = "${originTop}px"
            return
        }
        val targetLeft = originLeft + maxOf(0.0, (metrics.usableWidth - base.width) / 2.0)
        val targetTop = originTop + maxOf(0.0, (metrics.usableHeight - base.height) / 2.0)
        style.left = "${(targetLeft - base.left) / scaleX}px"
        style.top = "${(targetTop - base.top) / scaleY}px"
    }
}
