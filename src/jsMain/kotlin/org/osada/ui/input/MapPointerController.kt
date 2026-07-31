package org.osada.ui.input

import kotlinx.browser.window
import org.osada.ui.MapInputController
import org.osada.ui.MapZoomPreview
import org.osada.ui.byId

/**
 * The DOM half of the map gesture system (spec §30.3): it subscribes to Pointer Events, owns the
 * long-press timer and pointer capture, and executes the [GestureEffect]s that
 * [MapGestureReducer] returns. All recognition logic lives in the reducer; nothing here decides
 * what a gesture *means*.
 *
 * Pointer Events unify mouse, pen and touch, which is why the previous mouse-only listeners could
 * be retired outright rather than duplicated: the desktop paths (primary click, secondary-button
 * inspect, drag-to-scroll) run through exactly the same pipeline as a finger.
 */
internal class MapPointerController(
    private val input: MapInputController,
) {
    private val reducer = MapGestureReducer()
    private var element: dynamic = null
    private var longPressTimer = 0

    fun attach(canvas: dynamic) {
        element = canvas
        canvas.addEventListener("pointerdown", { event: dynamic -> onDown(event) })
        canvas.addEventListener("pointermove", { event: dynamic -> onMove(event) })
        canvas.addEventListener("pointerup", { event: dynamic -> onUp(event) })
        canvas.addEventListener("pointercancel", { event: dynamic -> onCancel(event) })
        canvas.addEventListener("lostpointercapture", { event: dynamic -> onCancel(event) })
        // A long press must never raise the browser's own context menu over the map.
        canvas.addEventListener("contextmenu", { event: dynamic -> event.preventDefault() })
        // Losing the window mid-gesture (task switch, incoming call) leaves pointers stranded.
        window.addEventListener("blur", { reset("window-blur") })
    }

    /** Voids any gesture in flight — used when a modal takes over input or the layout changes. */
    fun suppress(reason: String) {
        run(reducer.reduce(GestureInput.Suppress(reason)), null)
    }

    /** Browser-level loss of focus invalidates every contact, even without pointercancel. */
    private fun reset(reason: String) {
        run(reducer.reduce(GestureInput.Reset(reason)), null)
    }

    private fun onDown(event: dynamic) {
        val sample = sampleOf(event)
        val game = byId("game")?.asDynamic()
        // Claimed here rather than opportunistically later: the map owns every touch that starts
        // on it, so suppressing the compatibility mouse events and native scrolling up front is
        // exactly "after the game has claimed the gesture" (spec §29).
        if (sample.isTouch) event.preventDefault()
        run(
            reducer.reduce(
                GestureInput.Down(
                    sample = sample,
                    scrollLeft = (game?.scrollLeft as? Number)?.toDouble() ?: 0.0,
                    scrollTop = (game?.scrollTop as? Number)?.toDouble() ?: 0.0,
                ),
            ),
            sample,
        )
    }

    private fun onMove(event: dynamic) {
        val sample = sampleOf(event)
        run(reducer.reduce(GestureInput.Move(sample)), sample)
    }

    private fun onUp(event: dynamic) {
        val sample = sampleOf(event)
        releasePointerCapture(element, sample.pointerId)
        run(reducer.reduce(GestureInput.Up(sample)), sample)
    }

    private fun onCancel(event: dynamic) {
        val sample = sampleOf(event)
        releasePointerCapture(element, sample.pointerId)
        run(reducer.reduce(GestureInput.Cancel(sample.pointerId)), sample)
    }

    private fun run(
        effects: List<GestureEffect>,
        sample: PointerSample?,
    ) {
        effects.forEach { execute(it, sample) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun execute(
        effect: GestureEffect,
        sample: PointerSample?,
    ) {
        when (effect) {
            is GestureEffect.StartLongPressTimer -> startLongPress(effect.pointerId)
            GestureEffect.CancelLongPressTimer -> cancelLongPress()
            is GestureEffect.CapturePointer -> capturePointer(element, effect.pointerId)
            is GestureEffect.ScrollTo -> {
                val game = byId("game")?.asDynamic()
                game?.scrollLeft = effect.scrollLeft
                game?.scrollTop = effect.scrollTop
            }

            is GestureEffect.BeginPinch -> MapZoomPreview.begin(effect.anchorClientX, effect.anchorClientY)
            is GestureEffect.UpdatePinch ->
                MapZoomPreview.update(effect.scale, effect.anchorClientX, effect.anchorClientY)

            GestureEffect.CommitPinch -> MapZoomPreview.commit()
            GestureEffect.CancelPinch -> MapZoomPreview.cancel()
            is GestureEffect.Activate ->
                input.onActivate(
                    effect.clientX,
                    effect.clientY,
                    effect.kind,
                    sample?.pointerType != PointerSample.POINTER_TYPE_MOUSE,
                )

            is GestureEffect.Hover -> input.onHover(effect.clientX, effect.clientY)
        }
    }

    private fun startLongPress(pointerId: Int) {
        cancelLongPress()
        longPressTimer =
            window.setTimeout({
                longPressTimer = 0
                val effects = reducer.reduce(GestureInput.LongPressElapsed(pointerId))
                if (effects.isNotEmpty()) vibrate()
                run(effects, null)
            }, GestureConstants.LONG_PRESS_DELAY_MS.toInt())
    }

    private fun cancelLongPress() {
        if (longPressTimer != 0) {
            window.clearTimeout(longPressTimer)
            longPressTimer = 0
        }
    }
}

/** Flattens a DOM `PointerEvent` into the reducer's DOM-free [PointerSample]. */
private fun sampleOf(event: dynamic): PointerSample =
    PointerSample(
        pointerId = (event.pointerId as? Number)?.toInt() ?: 0,
        pointerType = (event.pointerType as? String) ?: PointerSample.POINTER_TYPE_MOUSE,
        clientX = (event.clientX as? Number)?.toDouble() ?: 0.0,
        clientY = (event.clientY as? Number)?.toDouble() ?: 0.0,
        buttons = (event.buttons as? Number)?.toInt() ?: 0,
        timeStamp = (event.timeStamp as? Number)?.toDouble() ?: 0.0,
    )

/**
 * Pointer capture is what keeps a pan alive when the finger leaves the canvas — without it a drag
 * that crosses into the HUD or off-screen simply stops receiving events and strands the gesture.
 */
private fun capturePointer(
    element: dynamic,
    pointerId: Int,
) {
    if (element == null) return
    if (element.setPointerCapture == null || element.setPointerCapture == undefined) return
    element.setPointerCapture(pointerId)
}

private fun releasePointerCapture(
    element: dynamic,
    pointerId: Int,
) {
    if (element == null) return
    if (element.hasPointerCapture == null || element.hasPointerCapture == undefined) return
    if (element.hasPointerCapture(pointerId) == true) element.releasePointerCapture(pointerId)
}

/** Confirms a recognised long press on devices that support it. Nothing may depend on haptics. */
private fun vibrate() {
    val navigator = window.navigator.asDynamic()
    if (navigator.vibrate == null || navigator.vibrate == undefined) return
    navigator.vibrate(LONG_PRESS_VIBRATION_MS)
}

private const val LONG_PRESS_VIBRATION_MS = 10
