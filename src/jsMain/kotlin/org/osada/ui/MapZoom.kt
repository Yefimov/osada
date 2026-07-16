package org.osada.ui

import org.osada.*

/**
 * Continuous map zoom: scales ONLY the map canvases (via the CSS `zoom` wrapper set up in
 * [RenderContext.initCanvases]/[RenderContext.positionLayers]), never the HUD. Backed by the
 * SAME `uiSettings.zoomLevel` field the old (non-functional) Settings "Game Map scale" slider
 * already persisted/restored, so saved games keep their zoom level; that slider now routes
 * through [set] too instead of just writing the number with no visual effect.
 *
 * Distinct from Strategic Map (`#zoom` button / [MenuController.toggleStrategicZoom]), which
 * applies its own CSS `zoom` directly to `#game` for a fit-everything overview. The two are
 * mutually exclusive by convention: callers should not invoke [set] while strategic zoom is
 * active (checked at the call sites — the minimap controls and Ctrl+wheel handler).
 */
internal object MapZoom {
    const val MIN = 0.5
    const val MAX = 2.0

    // ~1.25x multiplicative steps, including an exact 1.0 (100%) stop.
    val STEPS = listOf(0.5, 0.64, 0.8, 1.0, 1.25, 1.6, 2.0)

    val level: Double get() = uiSettings.zoomLevel

    fun clamp(value: Double): Double = value.coerceIn(MIN, MAX)

    /**
     * Sets the zoom level and re-lays-out the map, keeping one content point fixed on screen.
     * [focusClientX]/[focusClientY] are VIEWPORT pixel coordinates (e.g. straight from a
     * MouseEvent's clientX/clientY) — when given (Ctrl+wheel), the hex under that exact point
     * stays under it. Omitted (the +/-/reset controls, the Settings slider), the anchor is the
     * current viewport center, so zooming just "zooms in place" around what's already on screen.
     */
    fun set(newLevel: Double, focusClientX: Double? = null, focusClientY: Double? = null) {
        val ui = GameHolder.instance?.ui
        val clamped = clamp(newLevel)
        val oldZoom = uiSettings.zoomLevel
        if (ui == null) {
            uiSettings.zoomLevel = clamped
            return
        }
        if (clamped == oldZoom) return

        val gameDiv = byId("game")?.asDynamic()
        val oldRect = gameDiv?.getBoundingClientRect()
        val oldScrollLeft = (gameDiv?.scrollLeft as? Number)?.toDouble() ?: 0.0
        val oldScrollTop = (gameDiv?.scrollTop as? Number)?.toDouble() ?: 0.0
        val oldClientWidth = (gameDiv?.clientWidth as? Number)?.toDouble() ?: 0.0
        val oldClientHeight = (gameDiv?.clientHeight as? Number)?.toDouble() ?: 0.0
        val oldLeft = (oldRect?.left as? Number)?.toDouble() ?: 0.0
        val oldTop = (oldRect?.top as? Number)?.toDouble() ?: 0.0

        val anchorClientX = focusClientX ?: (oldLeft + oldClientWidth / 2.0)
        val anchorClientY = focusClientY ?: (oldTop + oldClientHeight / 2.0)
        // Content point under the anchor, in NATIVE (unzoomed) canvas pixel space — independent
        // of zoom, so it stays valid across the level change below.
        val nativeX = (anchorClientX - oldLeft + oldScrollLeft) / oldZoom
        val nativeY = (anchorClientY - oldTop + oldScrollTop) / oldZoom

        uiSettings.zoomLevel = clamped
        ui.render.positionLayers()
        ui.render.render()

        if (gameDiv != null) {
            val newRect = gameDiv.getBoundingClientRect()
            val newLeft = (newRect?.left as? Number)?.toDouble() ?: 0.0
            val newTop = (newRect?.top as? Number)?.toDouble() ?: 0.0
            gameDiv.scrollLeft = nativeX * clamped - (anchorClientX - newLeft)
            gameDiv.scrollTop = nativeY * clamped - (anchorClientY - newTop)
        }
        MinimapBuilder.refresh()
        AttackRingBuilder.refresh()
        // Hex-name/objective/low-ammo "small tooltips" are separate absolutely-positioned DOM
        // elements placed via cellToScreen(absolute=true) at the time they were ADDED — they do
        // NOT track later zoom changes on their own (they're plain divs, not redrawn on a
        // canvas), so without this they stay frozen at their pre-zoom position, drifting further
        // off their hex the more the zoom differs from whatever it was when they were placed.
        // Same call pair MenuController's hex-grid toggle already uses for the same reason.
        ui.removeAllSmallToolTips()
        ui.addSmallToolTips()
        refreshControls()
    }

    fun stepIn(focusClientX: Double? = null, focusClientY: Double? = null) =
        set(nextStep(level, 1), focusClientX, focusClientY)

    fun stepOut(focusClientX: Double? = null, focusClientY: Double? = null) =
        set(nextStep(level, -1), focusClientX, focusClientY)

    fun reset() = set(1.0)

    private fun nextStep(current: Double, direction: Int): Double {
        val sorted = STEPS.sorted()
        return if (direction > 0) sorted.firstOrNull { it > current + 0.001 } ?: MAX
        else sorted.lastOrNull { it < current - 0.001 } ?: MIN
    }

    fun refreshControls() {
        val pct = (level * 100).roundToIntSafe()
        byId("osadaZoomPct")?.textContent = "$pct%"
        // Plain divs (icon buttons, matching the rest of this HUD), not <button> — no native
        // `disabled`, so a CSS class marks the at-limit state instead.
        byId("osadaZoomOut")?.classList?.toggle("osada-zoom-btn--disabled", level <= MIN + 0.001)
        byId("osadaZoomIn")?.classList?.toggle("osada-zoom-btn--disabled", level >= MAX - 0.001)
    }

    private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
}
