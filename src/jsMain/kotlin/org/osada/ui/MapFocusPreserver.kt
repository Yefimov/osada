package org.osada.ui

/**
 * Keeps the same piece of map under the middle of the screen across a layout change (spec §11.3).
 *
 * Without this, every address-bar collapse, rotation, drawer-mode switch or fullscreen toggle
 * silently slides the map, so the hex the player was about to tap is no longer where they aimed.
 * Coordinates are NATIVE (unzoomed) canvas pixels, which stay valid across a zoom change too.
 */
@Suppress("ReturnCount")
internal fun captureMapCenterNative(): DoubleArray? {
    val game = byId("game")?.asDynamic() ?: return null
    val zoom = MapZoom.level
    if (zoom <= 0.0) return null
    val clientWidth = (game.clientWidth as? Number)?.toDouble() ?: return null
    val clientHeight = (game.clientHeight as? Number)?.toDouble() ?: return null
    if (clientWidth <= 0.0 || clientHeight <= 0.0) return null
    val scrollLeft = (game.scrollLeft as? Number)?.toDouble() ?: 0.0
    val scrollTop = (game.scrollTop as? Number)?.toDouble() ?: 0.0
    return doubleArrayOf((scrollLeft + clientWidth / 2.0) / zoom, (scrollTop + clientHeight / 2.0) / zoom)
}

/** Puts [center] (native canvas pixels) back under the middle of the map viewport, clamped. */
@Suppress("ReturnCount")
internal fun restoreMapCenterNative(center: DoubleArray?) {
    if (center == null || center.size < 2) return
    val game = byId("game")?.asDynamic() ?: return
    val zoom = MapZoom.level
    val clientWidth = (game.clientWidth as? Number)?.toDouble() ?: return
    val clientHeight = (game.clientHeight as? Number)?.toDouble() ?: return
    val scrollWidth = (game.scrollWidth as? Number)?.toDouble() ?: 0.0
    val scrollHeight = (game.scrollHeight as? Number)?.toDouble() ?: 0.0
    val maxLeft = (scrollWidth - clientWidth).coerceAtLeast(0.0)
    val maxTop = (scrollHeight - clientHeight).coerceAtLeast(0.0)
    game.scrollLeft = (center[0] * zoom - clientWidth / 2.0).coerceIn(0.0, maxLeft)
    game.scrollTop = (center[1] * zoom - clientHeight / 2.0).coerceIn(0.0, maxTop)
}
