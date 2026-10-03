package org.osada

import kotlinx.browser.document
import org.osada.ui.MinimapPings
import org.osada.ui.MinimapPointerInput
import org.w3c.dom.HTMLButtonElement
import kotlin.js.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinimapPingTest {
    private val sent = mutableListOf<String>()
    private var canvas: dynamic = null

    @BeforeTest
    fun setup() {
        document.body!!.innerHTML =
            """
            <div id="osadaMinimapPanel"><div id="osadaMinimapTitle"></div>
            <canvas id="osada-minimap"></canvas></div>
            """.trimIndent()
        canvas = document.getElementById("osada-minimap").asDynamic()
        canvas.getBoundingClientRect = { json("left" to 100, "top" to 50, "width" to 120, "height" to 80) }
        canvas.setPointerCapture = { _: Int -> }
        canvas.hasPointerCapture = { _: Int -> false }
        MinimapPings.configure(null, null)
        MinimapPings.build()
        MinimapPings.configure("0:test.xml") { x, y, mission -> sent += "$x,$y:$mission" }
        MinimapPointerInput.wire(canvas)
    }

    @AfterTest
    fun cleanup() {
        MinimapPings.configure(null, null)
        document.body!!.innerHTML = ""
    }

    @Test
    fun tapSendsOneNormalizedLocationAndDoesNotRepeatWhileDragging() {
        arm()
        pointer("pointerdown", 130, 110)
        assertTrue(sent.isEmpty(), "Pointer-down alone does not send")
        pointer("pointerup", 130, 110)
        assertEquals(listOf("2500,7500:0:test.xml"), sent)
        assertFalse(MinimapPings.armed)
        pointer("pointermove", 180, 80)
        assertEquals(1, sent.size)
    }

    @Test
    fun cancelledOrDraggedGestureDoesNotSendAndButtonCanCancel() {
        arm()
        pointer("pointerdown", 130, 90)
        pointer("pointercancel", 130, 90)
        pointer("pointerdown", 130, 90)
        pointer("pointermove", 180, 90)
        pointer("pointerup", 180, 90)
        assertTrue(sent.isEmpty())
        arm()
        assertFalse(MinimapPings.armed)
    }

    @Test
    fun missionChangeDropsMarkersAndOldMissionMessagesAndDoesNotTouchSaveState() {
        val labels = mutableListOf<String>()
        val context: dynamic = js("({})")
        context.save = {}
        context.restore = {}
        context.beginPath = {}
        context.arc = { _: Double, _: Double, _: Double, _: Double, _: Double -> }
        context.fill = {}
        context.stroke = {}
        context.fillRect = { _: Double, _: Double, _: Double, _: Double -> }
        context.measureText = { _: String -> json("width" to 20) }
        context.fillText = { label: String, _: Double, _: Double -> labels += label }
        MinimapPings.receive("a", "Alice", json("x" to 100, "y" to 200, "mission" to "0:test.xml"))
        MinimapPings.draw(context, 240.0, 160.0)
        assertTrue("Alice" in labels)
        labels.clear()
        MinimapPings.configure("1:next.xml") { _, _, _ -> }
        MinimapPings.receive("a", "Alice", json("x" to 100, "y" to 200, "mission" to "0:test.xml"))
        MinimapPings.receive("a", "Invalid", json("x" to -1, "y" to 200, "mission" to "1:next.xml"))
        MinimapPings.draw(context, 240.0, 160.0)
        assertTrue(labels.isEmpty())
    }

    private fun arm() = (document.getElementById("osadaMinimapPingButton") as HTMLButtonElement).click()

    @Suppress("UnusedParameter") // Referenced inside the inline JavaScript constructor.
    private fun pointer(
        type: String,
        x: Int,
        y: Int,
    ) {
        val options = json("pointerId" to 1, "button" to 0, "clientX" to x, "clientY" to y, "bubbles" to true)
        canvas.dispatchEvent(js("new PointerEvent(type, options)"))
    }
}
