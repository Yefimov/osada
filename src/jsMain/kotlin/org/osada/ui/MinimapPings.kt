package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.i18n.I18n
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Date
import kotlin.math.roundToInt

/** Local, short-lived presence. No unit, camera, turn-readiness or save state is changed. */
internal object MinimapPings {
    private const val LIFETIME_MILLIS = 8000
    private const val COOLDOWN_MILLIS = 1000
    private const val SCALE = 10_000.0
    private var missionKey: String? = null
    private var send: ((Int, Int, String) -> Unit)? = null
    private var button: HTMLButtonElement? = null
    private var announcement: HTMLElement? = null
    private var nextSendAt = 0.0
    private var timer: Int? = null
    private val markers = linkedMapOf<String, Marker>()

    var armed = false
        private set

    private data class Marker(
        val x: Int,
        val y: Int,
        val label: String,
        val expiresAt: Double,
    )

    fun build() {
        val title = document.getElementById("osadaMinimapTitle") ?: return
        document.getElementById("osadaMinimapPingButton")?.remove()
        document.getElementById("osadaMinimapPingAnnouncement")?.remove()
        button =
            (document.createElement("button") as HTMLButtonElement).also {
                it.id = "osadaMinimapPingButton"
                it.type = "button"
                it.className = "osada-minimap-ping-button"
                it.onclick = {
                    armed = !armed
                    updateButton()
                }
                title.parentElement?.appendChild(it)
            }
        announcement =
            (document.createElement("span") as HTMLElement).also {
                it.id = "osadaMinimapPingAnnouncement"
                it.className = "osada-ping-announcement"
                it.setAttribute("role", "status")
                it.setAttribute("aria-live", "polite")
                title.parentElement?.appendChild(it)
            }
        updateButton()
    }

    fun configure(
        mission: String?,
        sender: ((Int, Int, String) -> Unit)?,
    ) {
        if (mission != missionKey) clearMarkers()
        missionKey = mission
        send = sender
        if (mission == null || sender == null) armed = false
        updateButton()
    }

    fun cancel() {
        armed = false
        updateButton()
    }

    /** Called only for a completed tap, never pointer-down, dragging, or pointer cancellation. */
    @Suppress("ReturnCount")
    fun place(
        canvas: dynamic,
        clientX: Double,
        clientY: Double,
    ) {
        if (!armed) return
        cancel()
        val mission = missionKey ?: return
        val callback = send ?: return
        val rect = canvas.getBoundingClientRect()
        val width = (rect.width as Number).toDouble()
        val height = (rect.height as Number).toDouble()
        if (width <= 0 || height <= 0 || Date.now() < nextSendAt) return
        val x = (clientX - (rect.left as Number).toDouble()) / width
        val y = (clientY - (rect.top as Number).toDouble()) / height
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return
        nextSendAt = Date.now() + COOLDOWN_MILLIS
        callback((x * SCALE).roundToInt(), (y * SCALE).roundToInt(), mission)
    }

    @Suppress("ReturnCount")
    fun receive(
        senderId: String,
        name: String,
        payload: dynamic,
    ) {
        if (missionKey == null || payload.mission as? String != missionKey) return
        val x = coordinate(payload.x) ?: return
        val y = coordinate(payload.y) ?: return
        markers[senderId] = Marker(x, y, name, Date.now() + LIFETIME_MILLIS)
        announcement?.textContent = I18n.t("multiplayer.ping.received", mapOf("name" to name))
        MinimapBuilder.refresh()
        if (timer == null) {
            timer =
                window.setInterval({
                    markers.entries.removeAll { it.value.expiresAt <= Date.now() }
                    MinimapBuilder.refresh()
                    if (markers.isEmpty()) stopTimer()
                }, COOLDOWN_MILLIS)
        }
    }

    /** Drawn last on the existing minimap canvas; zoom and responsive sizing affect all layers alike. */
    @Suppress("MagicNumber") // Pixel geometry for the marker glyph and its label, not gameplay values.
    fun draw(
        context: dynamic,
        width: Double,
        height: Double,
    ) {
        context.save()
        context.font = "bold 11px sans-serif"
        context.textAlign = "center"
        markers.values.filter { it.expiresAt > Date.now() }.forEach { marker ->
            val x = marker.x / SCALE * width
            val y = marker.y / SCALE * height
            context.beginPath()
            context.arc(x, y, 9.0, 0.0, 2.0 * kotlin.math.PI)
            context.fillStyle = "#17191e"
            context.fill()
            context.strokeStyle = "#ffdf71"
            context.lineWidth = 2.0
            context.stroke()
            context.fillStyle = "#ffdf71"
            context.fillText("!", x, y + 4.0)
            val label = marker.label.take(18)
            val half = ((context.measureText(label).width as Number).toDouble() / 2.0 + 3.0).coerceAtMost(width / 2)
            val labelX = x.coerceIn(half, width - half)
            val labelY = if (y < 28.0) y + 23.0 else y - 14.0
            context.fillStyle = "#17191e"
            context.fillRect(labelX - half, labelY - 11.0, half * 2.0, 14.0)
            context.fillStyle = "#ffffff"
            context.fillText(label, labelX, labelY)
        }
        context.restore()
    }

    private fun coordinate(value: dynamic): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it in 0.0..SCALE && it % 1.0 == 0.0 }?.toInt()
    }

    private fun updateButton() {
        button?.hidden = missionKey == null
        button?.disabled = send == null
        button?.textContent = I18n.t(if (armed) "multiplayer.ping.cancel" else "multiplayer.ping.button")
        button?.title = I18n.t(if (armed) "multiplayer.ping.armed" else "multiplayer.ping.help")
        button?.setAttribute("aria-pressed", armed.toString())
        document.getElementById("osadaMinimapPanel")?.classList?.toggle("osada-has-ping", missionKey != null)
        document.getElementById("osada-minimap")?.setAttribute(
            "title",
            I18n.t(if (armed) "multiplayer.ping.armed" else "hud.sidebar.minimap.canvas.help"),
        )
    }

    private fun clearMarkers() {
        markers.clear()
        nextSendAt = 0.0
        armed = false
        announcement?.textContent = ""
        stopTimer()
        MinimapBuilder.refresh()
    }

    private fun stopTimer() {
        timer?.let(window::clearInterval)
        timer = null
    }
}
