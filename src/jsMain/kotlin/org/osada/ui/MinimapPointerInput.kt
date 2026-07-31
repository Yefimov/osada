package org.osada.ui

/**
 * Pointer Events for the minimap (spec §36, MOB-AUDIT-002).
 *
 * The minimap used to listen for `mousedown`/`mousemove`/`mouseup` on `window`, which meant a
 * finger could not drag the viewport at all and a mouse drag that left the browser window never
 * ended. Pointer capture fixes both: once a drag starts, every subsequent move and the release
 * are delivered to the minimap itself regardless of where the pointer travels.
 *
 * Tap centres the map; drag moves it continuously; a drag never also counts as a tap. There is no
 * pinch here — the minimap is a navigation aid, and its own scale is fixed.
 */
internal object MinimapPointerInput {
    private var draggingPointer: Int? = null

    fun wire(canvas: dynamic) {
        canvas.addEventListener("pointerdown", { event: dynamic -> onDown(canvas, event) })
        canvas.addEventListener("pointermove", { event: dynamic -> onMove(event) })
        canvas.addEventListener("pointerup", { event: dynamic -> onUp(canvas, event) })
        canvas.addEventListener("pointercancel", { event: dynamic -> onUp(canvas, event) })
        canvas.addEventListener("lostpointercapture", { _: dynamic -> draggingPointer = null })
    }

    private fun onDown(
        canvas: dynamic,
        event: dynamic,
    ) {
        val pointerId = (event.pointerId as? Number)?.toInt() ?: return
        draggingPointer = pointerId
        // Claimed immediately: the minimap owns this gesture, so the page must not also scroll.
        event.preventDefault()
        if (canvas.setPointerCapture != null && canvas.setPointerCapture != undefined) {
            canvas.setPointerCapture(pointerId)
        }
        MinimapBuilder.scrollMapTo(clientX(event), clientY(event))
    }

    private fun onMove(event: dynamic) {
        val pointerId = (event.pointerId as? Number)?.toInt() ?: return
        if (draggingPointer != pointerId) return
        MinimapBuilder.scrollMapTo(clientX(event), clientY(event))
    }

    private fun onUp(
        canvas: dynamic,
        event: dynamic,
    ) {
        val pointerId = (event.pointerId as? Number)?.toInt() ?: return
        draggingPointer = null
        if (canvas.hasPointerCapture != null &&
            canvas.hasPointerCapture != undefined &&
            canvas.hasPointerCapture(pointerId) == true
        ) {
            canvas.releasePointerCapture(pointerId)
        }
    }

    private fun clientX(event: dynamic): Double = (event.clientX as? Number)?.toDouble() ?: 0.0

    private fun clientY(event: dynamic): Double = (event.clientY as? Number)?.toDouble() ?: 0.0
}
