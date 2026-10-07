package org.osada.ui.campaignmap

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.ui.input.GestureConstants
import org.osada.ui.input.GestureEffect
import org.osada.ui.input.GestureInput
import org.osada.ui.input.MapGestureReducer
import org.osada.ui.input.PointerSample
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.math.exp

/**
 * Pan, zoom and activation for the map surface, on the same gesture reducer the battle map uses:
 * a drag (any pointer) pans, a pinch or the wheel zooms around the fingers or cursor, a tap or a
 * long press activates the operation under it.
 *
 * Pointer taps are handled here, never by the buttons' click events: a click event still reaches
 * the button after a drag that began on it. Keyboard activation arrives as a click with `detail`
 * 0, which [onActivate]'s caller keeps for itself.
 */
internal class CampaignMapViewport(
    private val scroller: HTMLElement,
    private val sizer: HTMLElement,
    private val surface: HTMLElement,
    private val onActivate: (Element?) -> Unit,
) {
    private val reducer = MapGestureReducer()
    private var longPressTimer = 0
    private var pinchStartScale = 1.0
    private var contentWidth = 1.0
    private var contentHeight = 1.0
    private val listeners = mutableListOf<Pair<String, (Event) -> Unit>>()
    var scale = 1.0
        private set

    fun install() {
        listen("pointerdown") { pointer(it, down = true) }
        listen("pointermove") { pointer(it, down = false) }
        listen("pointerup") { event -> sample(event)?.let { run(reducer.reduce(GestureInput.Up(it))) } }
        listen(
            "pointercancel",
        ) { event -> run(reducer.reduce(GestureInput.Cancel(event.asDynamic().pointerId as Int))) }
        listen("wheel") { event ->
            val raw = event.asDynamic()
            event.preventDefault()
            // Proportional to the scroll distance, so a trackpad's stream of small deltas zooms as
            // smoothly as a mouse notch steps. deltaMode 1 counts lines, not pixels.
            val delta = (raw.deltaY as Number).toDouble() * if (raw.deltaMode == 1) WHEEL_LINE_PX else 1.0
            val factor = exp(-delta.coerceIn(-WHEEL_MAX_DELTA, WHEEL_MAX_DELTA) * WHEEL_ZOOM_RATE)
            zoomTo(scale * factor, (raw.clientX as Number).toDouble(), (raw.clientY as Number).toDouble())
        }
    }

    fun dispose() {
        listeners.forEach { (type, handler) -> scroller.removeEventListener(type, handler) }
        listeners.clear()
        window.clearTimeout(longPressTimer)
        run(reducer.reduce(GestureInput.Reset("closed")))
    }

    fun setContent(
        width: Double,
        height: Double,
    ) {
        contentWidth = width
        contentHeight = height
        apply()
    }

    /** Zooms to [target] keeping the content point under the client position ([anchorX], [anchorY]) still. */
    fun zoomTo(
        target: Double,
        anchorX: Double? = null,
        anchorY: Double? = null,
    ) {
        val rect = scroller.getBoundingClientRect()
        val ax = (anchorX ?: (rect.left + rect.width / 2)) - rect.left
        val ay = (anchorY ?: (rect.top + rect.height / 2)) - rect.top
        val contentX = (scroller.scrollLeft + ax - sizer.offsetLeft) / scale
        val contentY = (scroller.scrollTop + ay - sizer.offsetTop) / scale
        scale = target.coerceIn(MIN_SCALE, MAX_SCALE)
        apply()
        scroller.scrollLeft = contentX * scale + sizer.offsetLeft - ax
        scroller.scrollTop = contentY * scale + sizer.offsetTop - ay
    }

    /** The scale at which the whole route fits, never above natural size. */
    fun fitScale(): Double =
        minOf(scroller.clientWidth / contentWidth, scroller.clientHeight / contentHeight, 1.0).coerceAtLeast(MIN_SCALE)

    /** Scrolls so [box] (surface pixels) sits in the middle of the view. */
    fun reveal(box: MapBox) {
        scroller.scrollLeft = (box.x + box.width / 2) * scale + sizer.offsetLeft - scroller.clientWidth / 2.0
        scroller.scrollTop = (box.y + box.height / 2) * scale + sizer.offsetTop - scroller.clientHeight / 2.0
    }

    private fun apply() {
        sizer.style.width = "${contentWidth * scale}px"
        sizer.style.height = "${contentHeight * scale}px"
        surface.style.transform = "scale($scale)"
    }

    private fun pointer(
        event: Event,
        down: Boolean,
    ) {
        val sample = sample(event) ?: return
        if (down && sample.pointerType == PointerSample.POINTER_TYPE_MOUSE && sample.buttons != 1) return
        val input =
            if (down) GestureInput.Down(sample, scroller.scrollLeft, scroller.scrollTop) else GestureInput.Move(sample)
        run(reducer.reduce(input))
    }

    private fun sample(event: Event): PointerSample? {
        val raw = event.asDynamic()
        val id = raw.pointerId as? Int ?: return null
        return PointerSample(
            pointerId = id,
            pointerType = raw.pointerType as? String ?: PointerSample.POINTER_TYPE_MOUSE,
            clientX = (raw.clientX as Number).toDouble(),
            clientY = (raw.clientY as Number).toDouble(),
            buttons = raw.buttons as? Int ?: 0,
            timeStamp = (raw.timeStamp as Number).toDouble(),
        )
    }

    private fun run(effects: List<GestureEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is GestureEffect.StartLongPressTimer -> {
                    window.clearTimeout(longPressTimer)
                    longPressTimer =
                        window.setTimeout({
                            run(reducer.reduce(GestureInput.LongPressElapsed(effect.pointerId)))
                        }, GestureConstants.LONG_PRESS_DELAY_MS.toInt())
                }
                GestureEffect.CancelLongPressTimer -> window.clearTimeout(longPressTimer)
                is GestureEffect.CapturePointer -> scroller.asDynamic().setPointerCapture(effect.pointerId)
                is GestureEffect.ScrollTo -> {
                    scroller.scrollLeft = effect.scrollLeft
                    scroller.scrollTop = effect.scrollTop
                }
                is GestureEffect.BeginPinch -> pinchStartScale = scale
                is GestureEffect.UpdatePinch ->
                    zoomTo(
                        pinchStartScale * effect.scale,
                        effect.anchorClientX,
                        effect.anchorClientY,
                    )
                GestureEffect.CancelPinch -> zoomTo(pinchStartScale)
                GestureEffect.CommitPinch, is GestureEffect.Hover -> Unit
                // A tap and a long press both open the operation: there is nothing else to inspect.
                is GestureEffect.Activate ->
                    onActivate(document.asDynamic().elementFromPoint(effect.clientX, effect.clientY) as? Element)
            }
        }
    }

    private fun listen(
        type: String,
        handler: (Event) -> Unit,
    ) {
        scroller.addEventListener(type, handler, js("({ passive: false })"))
        listeners += type to handler
    }

    companion object {
        const val MIN_SCALE = 0.3
        const val MAX_SCALE = 1.6
        const val ZOOM_STEP = 1.2

        /** ln(ZOOM_STEP) per 100 px, so one mouse-wheel notch is one zoom step. */
        private const val WHEEL_ZOOM_RATE = 0.0018
        private const val WHEEL_LINE_PX = 16.0
        private const val WHEEL_MAX_DELTA = 120.0
    }
}
