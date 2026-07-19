package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.uiSettings
import org.w3c.dom.Element
import org.w3c.dom.HTMLMetaElement

/**
 * Device pixel-ratio / retina scaling and `<meta name="viewport">` tag management. Split from
 * [DOM] purely to keep that file within the project's function-count limits.
 */
fun changeViewPort() {
    val gameState = js("new GameState(null)")
    gameState.restoreSettings()

    var scale = 1.0
    var maxScale = 1.0
    var prefix = "width=device-width, "
    val pixelRatio = window.devicePixelRatio

    val metas = document.getElementsByTagName("meta").asDynamic()
    var viewport: HTMLMetaElement? = null
    for (i in 0 until document.getElementsByTagName("meta").length) {
        val meta = metas[i] as? HTMLMetaElement
        if (meta?.name == "viewport") {
            viewport = meta
            break
        }
    }
    if (viewport == null) {
        val head = document.getElementsByTagName("head").asDynamic()[0] as? Element
        viewport = document.createElement("meta") as HTMLMetaElement
        viewport.id = "viewport"
        viewport.name = "viewport"
        head?.appendChild(viewport)
    }

    val ua = window.navigator.userAgent
    if (ua.contains(Regex("(iPhone|iPod)", RegexOption.IGNORE_CASE))) {
        prefix = ""
        if (pixelRatio == 1.0) {
            @Suppress("UNUSED_VARIABLE")
            val ratio = 2.0
        }
    }

    var d = scale
    var l = maxScale
    var k = pixelRatio
    if (k > 1 && k < 2) k = 2.0
    if (uiSettings.useRetina) {
        l = 1 / k
        d = 1 / k
    }
    viewport.content = "${prefix}initial-scale=$d,maximum-scale=$l, user-scalable=1"
}
