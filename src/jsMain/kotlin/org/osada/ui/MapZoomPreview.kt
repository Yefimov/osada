package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.uiSettings

/**
 * The cheap half of pinch-to-zoom (spec §25, §33).
 *
 * [MapZoom.set] is far too expensive to run per pointer move: it re-lays-out the canvases,
 * re-renders the whole map, recomposites the minimap, recomputes attack rings and tears down and
 * rebuilds every small map tooltip. At two-finger frame rates that is the difference between a
 * zoom that tracks the fingers and one that stutters.
 *
 * So a pinch runs in two phases. **Preview** only rescales the `#game-zoom-wrap` wrapper and
 * corrects `#game`'s scroll so the map point under the pinch midpoint stays there — one style
 * write and one scroll write per animation frame. **Commit** then performs the single heavy
 * refresh, through the same [MapZoom.applyLevel] path the +/- controls use.
 *
 * Preview state is local UI state: it is never persisted and never reaches a multiplayer peer
 * (spec §54.1). Only the committed level is written to `uiSettings.zoomLevel`.
 */
internal object MapZoomPreview {
    private const val ZOOMING_CLASS = "osada-map-zooming"
    private const val PERCENT = 100.0

    private var active = false
    private var startZoom = 1.0
    private var startScrollLeft = 0.0
    private var startScrollTop = 0.0
    private var anchorNativeX = 0.0
    private var anchorNativeY = 0.0
    private var pendingLevel = 1.0
    private var pendingAnchorX = 0.0
    private var pendingAnchorY = 0.0
    private var frameHandle = 0

    /** The zoom level the pinch started from; the gesture layer scales relative to it. */
    val baseLevel: Double get() = startZoom

    fun begin(
        anchorClientX: Double,
        anchorClientY: Double,
    ) {
        val game = byId("game")?.asDynamic() ?: return
        startZoom = MapZoom.level
        if (startZoom <= 0.0) return
        val rect = game.getBoundingClientRect()
        val left = (rect?.left as? Number)?.toDouble() ?: 0.0
        val top = (rect?.top as? Number)?.toDouble() ?: 0.0
        startScrollLeft = (game.scrollLeft as? Number)?.toDouble() ?: 0.0
        startScrollTop = (game.scrollTop as? Number)?.toDouble() ?: 0.0
        anchorNativeX = (anchorClientX - left + startScrollLeft) / startZoom
        anchorNativeY = (anchorClientY - top + startScrollTop) / startZoom
        pendingLevel = startZoom
        pendingAnchorX = anchorClientX
        pendingAnchorY = anchorClientY
        active = true
        // Lets CSS freeze DOM-positioned map labels for the duration: they are placed once in map
        // coordinates and cannot cheaply track a preview, so they are hidden rather than drifting.
        document.body?.classList?.add(ZOOMING_CLASS)
    }

    /** [scale] is relative to [baseLevel]; clamping stays in [MapZoom] so limits live in one place. */
    fun update(
        scale: Double,
        anchorClientX: Double,
        anchorClientY: Double,
    ) {
        if (!active) return
        pendingLevel = MapZoom.clamp(startZoom * scale)
        pendingAnchorX = anchorClientX
        pendingAnchorY = anchorClientY
        if (frameHandle != 0) return
        frameHandle =
            window.requestAnimationFrame {
                frameHandle = 0
                applyPreview()
            }
    }

    /** Ends the pinch and pays for the one full refresh. Safe to call when no preview is running. */
    fun commit() {
        if (!active) return
        cancelFrame()
        active = false
        document.body?.classList?.remove(ZOOMING_CLASS)
        MapZoom.applyLevel(pendingLevel, pendingAnchorX, pendingAnchorY, anchorNativeX, anchorNativeY)
    }

    /** Interrupted pinch (pointercancel, modal, layout change): restore the pre-pinch view exactly. */
    fun cancel() {
        if (!active) return
        cancelFrame()
        active = false
        document.body?.classList?.remove(ZOOMING_CLASS)
        pendingLevel = startZoom
        uiSettings.zoomLevel = startZoom
        applyWrapperZoom(startZoom)
        val game = byId("game")?.asDynamic()
        game?.scrollLeft = startScrollLeft
        game?.scrollTop = startScrollTop
        MapZoom.refreshControls()
    }

    private fun applyPreview() {
        if (!active) return
        applyWrapperZoom(pendingLevel)
        scrollToAnchor(pendingLevel)
        byId("osadaZoomPct")?.textContent = "${(pendingLevel * PERCENT).toInt()}%"
    }

    private fun applyWrapperZoom(level: Double) {
        byId("game-zoom-wrap")?.style?.asDynamic()?.zoom = level.toString()
    }

    private fun scrollToAnchor(level: Double) {
        val game = byId("game")?.asDynamic() ?: return
        val rect = game.getBoundingClientRect()
        val left = (rect?.left as? Number)?.toDouble() ?: 0.0
        val top = (rect?.top as? Number)?.toDouble() ?: 0.0
        game.scrollLeft = anchorNativeX * level - (pendingAnchorX - left)
        game.scrollTop = anchorNativeY * level - (pendingAnchorY - top)
    }

    private fun cancelFrame() {
        if (frameHandle != 0) {
            window.cancelAnimationFrame(frameHandle)
            frameHandle = 0
        }
    }
}
