package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.WeatherCondition
import org.osada.ui.WeatherRenderer.start

/**
 * Runtime precipitation overlay. Draws OG's rain/snow scroll textures (keyed transparent in
 * tools/og-import/make_weather_assets.py) over a fixed full-viewport canvas and animates them
 * falling, driven by the scenario's `atmospheric` condition (see [start]; 2=Rain, 3=Snow). Also
 * loops the matching ambient sound. Independent of the on-demand map render — it owns its own
 * setInterval tick so the rain keeps falling between game actions. Fair(0)/Overcast(1) show
 * nothing.
 */
internal object WeatherRenderer {
    private const val TICK_INTERVAL_MS = 45
    private const val PHASE_INCREMENT = 0.08
    private const val RAIN_ALPHA = 0.5
    private const val SNOW_ALPHA = 0.9

    private var canvas: dynamic = null
    private var ctx: dynamic = null
    private var img: dynamic = null
    private var timer: Int = 0
    private var mode: Int = 0 // 2=rain, 3=snow, 0=off
    private var offY: Double = 0.0
    private var phase: Double = 0.0

    fun start(atmospheric: Int) {
        stop()
        if (atmospheric != WeatherCondition.RAIN.value && atmospheric != WeatherCondition.SNOW.value) return
        mode = atmospheric
        ensureCanvas()
        val src =
            if (atmospheric == WeatherCondition.RAIN.value) {
                "resources/ui/weather/rain.png"
            } else {
                "resources/ui/weather/snow.png"
            }
        val image = js("new Image()")
        image.onload = { onLoaded() }
        image.onerror = { console.error("WeatherRenderer: failed to load $src") }
        image.src = src
        img = image
        Sound.startAmbient(
            if (atmospheric == WeatherCondition.RAIN.value) {
                "resources/sounds/weather/rain.mp3"
            } else {
                "resources/sounds/weather/winter.mp3"
            },
        )
    }

    fun stop() {
        if (timer != 0) {
            window.clearInterval(timer)
            timer = 0
        }
        if (canvas != null) {
            ctx.clearRect(0.0, 0.0, canvas.width, canvas.height)
            canvas.style.display = "none"
        }
        Sound.stopAmbient()
        mode = 0
    }

    private fun ensureCanvas() {
        if (canvas != null) return
        val c = document.createElement("canvas")
        c.id = "weather"
        val s = c.asDynamic().style
        s.position = "fixed"
        s.left = "0"
        s.top = "0"
        s.pointerEvents = "none" // never intercept clicks/menus
        s.zIndex = "6"
        s.display = "none"
        document.body?.appendChild(c)
        canvas = c.asDynamic()
        ctx = canvas.getContext("2d")
    }

    private fun resize() {
        if (canvas.width != window.innerWidth) canvas.width = window.innerWidth
        if (canvas.height != window.innerHeight) canvas.height = window.innerHeight
    }

    private fun onLoaded() {
        resize()
        canvas.style.display = "block"
        val ih = (img.height as? Number)?.toDouble() ?: 256.0
        val fall = if (mode == WeatherCondition.RAIN.value) 26.0 else 4.0 // rain falls fast, snow drifts slowly
        timer =
            window.setInterval({
                resize()
                offY = (offY + fall) % ih
                phase += PHASE_INCREMENT
                draw(ih)
            }, TICK_INTERVAL_MS)
    }

    private fun draw(ih: Double) {
        val vw = (canvas.width as? Number)?.toDouble() ?: 0.0
        val vh = (canvas.height as? Number)?.toDouble() ?: 0.0
        ctx.clearRect(0.0, 0.0, vw, vh)
        ctx.globalAlpha = if (mode == WeatherCondition.RAIN.value) RAIN_ALPHA else SNOW_ALPHA
        // rain: steady wind to the left; snow: gentle horizontal sway. The texture (>6000px wide)
        // covers the viewport horizontally in one draw, so only a vertical tile loop is needed.
        val xoff = if (mode == WeatherCondition.RAIN.value) -40.0 else (kotlin.math.sin(phase) * 28.0 - 28.0)
        var y = (offY % ih) - ih
        while (y < vh) {
            ctx.drawImage(img, xoff, y)
            y += ih
        }
        ctx.globalAlpha = 1.0
    }
}
