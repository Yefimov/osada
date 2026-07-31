package org.osada.ui.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The pure map gesture state machine (spec §30.2): tap vs. long press vs. pan vs. pinch.
 *
 * Deliberately has **no DOM dependency and no game-model dependency** — it consumes
 * [GestureInput] and returns [GestureEffect]s for [MapPointerController] to execute. That is what
 * makes "a pan never becomes a tap" and "the finger left after a pinch is not a new gesture"
 * testable without a browser, which is the single most regression-prone part of touch input.
 *
 * The reducer does own the active-pointer table, because pinch recognition is a function of the
 * pointer *set*, not of any one event; the adapter owns only timers, capture and coordinates.
 */
@Suppress("ReturnCount")
internal class MapGestureReducer {
    var state: MapGestureState = MapGestureState.Idle
        private set

    private val active = mutableMapOf<Int, PointerSample>()

    val activePointerCount: Int get() = active.size

    fun reduce(input: GestureInput): List<GestureEffect> =
        when (input) {
            is GestureInput.Down -> onDown(input)
            is GestureInput.Move -> onMove(input.sample)
            is GestureInput.Up -> onUp(input.sample)
            is GestureInput.Cancel -> onCancel(input.pointerId)
            is GestureInput.LongPressElapsed -> onLongPressElapsed(input.pointerId)
            is GestureInput.Suppress -> onSuppress(input.reason)
            is GestureInput.Reset -> onSuppress(input.reason, clearPointers = true)
        }

    private fun onDown(input: GestureInput.Down): List<GestureEffect> {
        val sample = input.sample
        active[sample.pointerId] = sample
        val current = state
        if (current is MapGestureState.SuppressedUntilAllPointersUp) return emptyList()

        val touchPointers = active.values.filter { it.isTouch }
        if (touchPointers.size >= PINCH_POINTER_COUNT) return startPinch(touchPointers)
        if (active.size > 1) {
            // A second non-touch contact (or a third finger): nothing sensible to recognise, and
            // it must not be allowed to complete as a tap.
            state = MapGestureState.SuppressedUntilAllPointersUp("multi-pointer")
            return listOf(GestureEffect.CancelLongPressTimer)
        }

        state =
            MapGestureState.PressCandidate(
                pointerId = sample.pointerId,
                startClientX = sample.clientX,
                startClientY = sample.clientY,
                startTimeMs = sample.timeStamp,
                startScrollLeft = input.scrollLeft,
                startScrollTop = input.scrollTop,
                secondary = sample.isSecondaryButton,
            )
        // A mouse has its own inspect path (right button / contextmenu), so holding it still must
        // not fire a long press — that would make click-and-hold-then-release ambiguous.
        val wantsLongPress = sample.pointerType != PointerSample.POINTER_TYPE_MOUSE && !sample.isSecondaryButton
        return if (wantsLongPress) listOf(GestureEffect.StartLongPressTimer(sample.pointerId)) else emptyList()
    }

    private fun startPinch(touchPointers: List<PointerSample>): List<GestureEffect> {
        val a = touchPointers[0]
        val b = touchPointers[1]
        val distance = max(GestureConstants.PINCH_MIN_DISTANCE_PX, distanceBetween(a, b))
        state = MapGestureState.Pinching(a.pointerId, b.pointerId, distance)
        return listOf(
            GestureEffect.CancelLongPressTimer,
            GestureEffect.BeginPinch(midpoint(a.clientX, b.clientX), midpoint(a.clientY, b.clientY)),
        )
    }

    private fun onMove(sample: PointerSample): List<GestureEffect> {
        if (!active.containsKey(sample.pointerId)) {
            // No button down: a fine pointer hovering the map. Touch never reaches here (a touch
            // pointer that is not down produces no pointermove).
            return if (sample.isTouch) emptyList() else listOf(GestureEffect.Hover(sample.clientX, sample.clientY))
        }
        active[sample.pointerId] = sample
        return when (val current = state) {
            is MapGestureState.PressCandidate -> moveCandidate(current, sample)
            is MapGestureState.Panning -> movePanning(current, sample)
            is MapGestureState.Pinching -> movePinching(current)
            else -> emptyList()
        }
    }

    private fun moveCandidate(
        current: MapGestureState.PressCandidate,
        sample: PointerSample,
    ): List<GestureEffect> {
        if (current.pointerId != sample.pointerId) return emptyList()
        val moved = max(abs(sample.clientX - current.startClientX), abs(sample.clientY - current.startClientY))
        // Strictly greater: exactly MOVE_SLOP_PX is still a tap (spec §61.1 boundary case).
        if (moved <= GestureConstants.PAN_START_THRESHOLD_PX) {
            return if (sample.isTouch) emptyList() else listOf(GestureEffect.Hover(sample.clientX, sample.clientY))
        }
        val panning =
            MapGestureState.Panning(
                pointerId = current.pointerId,
                startClientX = current.startClientX,
                startClientY = current.startClientY,
                startScrollLeft = current.startScrollLeft,
                startScrollTop = current.startScrollTop,
            )
        state = panning
        return listOf(
            GestureEffect.CancelLongPressTimer,
            GestureEffect.CapturePointer(current.pointerId),
            scrollFor(panning, sample),
        )
    }

    private fun movePanning(
        current: MapGestureState.Panning,
        sample: PointerSample,
    ): List<GestureEffect> =
        if (current.pointerId != sample.pointerId) emptyList() else listOf(scrollFor(current, sample))

    private fun movePinching(current: MapGestureState.Pinching): List<GestureEffect> {
        val a = active[current.pointerA] ?: return emptyList()
        val b = active[current.pointerB] ?: return emptyList()
        val distance = max(GestureConstants.PINCH_MIN_DISTANCE_PX, distanceBetween(a, b))
        return listOf(
            GestureEffect.UpdatePinch(
                scale = distance / current.startDistance,
                anchorClientX = midpoint(a.clientX, b.clientX),
                anchorClientY = midpoint(a.clientY, b.clientY),
            ),
        )
    }

    private fun onUp(sample: PointerSample): List<GestureEffect> {
        active.remove(sample.pointerId)
        return when (val current = state) {
            is MapGestureState.PressCandidate -> {
                state = settledState(active.isEmpty(), "released")
                if (current.pointerId == sample.pointerId && withinSlop(current, sample)) {
                    val kind = if (current.secondary) MapActivationKind.INSPECT else MapActivationKind.PRIMARY
                    listOf(
                        GestureEffect.CancelLongPressTimer,
                        GestureEffect.Activate(sample.clientX, sample.clientY, kind),
                    )
                } else {
                    listOf(GestureEffect.CancelLongPressTimer)
                }
            }

            is MapGestureState.Panning -> {
                state = settledState(active.isEmpty(), "panned")
                emptyList()
            }

            is MapGestureState.Pinching -> {
                state = settledState(active.isEmpty(), "pinch")
                listOf(GestureEffect.CommitPinch)
            }
            // The pointer-up that follows a triggered long press is consumed (spec §26).
            is MapGestureState.LongPressTriggered -> {
                state = settledState(active.isEmpty(), "long-press")
                emptyList()
            }

            else -> {
                state = settledState(active.isEmpty(), "suppressed")
                emptyList()
            }
        }
    }

    private fun onCancel(pointerId: Int): List<GestureEffect> {
        active.remove(pointerId)
        val wasPinching = state is MapGestureState.Pinching
        state = settledState(active.isEmpty(), "pointercancel")
        return if (wasPinching) {
            listOf(GestureEffect.CancelLongPressTimer, GestureEffect.CancelPinch)
        } else {
            listOf(GestureEffect.CancelLongPressTimer)
        }
    }

    private fun onLongPressElapsed(pointerId: Int): List<GestureEffect> {
        val current = state
        if (current !is MapGestureState.PressCandidate || current.pointerId != pointerId) return emptyList()
        val sample = active[pointerId] ?: return emptyList()
        if (!withinSlop(current, sample)) return emptyList()
        state = MapGestureState.LongPressTriggered(pointerId)
        return listOf(GestureEffect.Activate(sample.clientX, sample.clientY, MapActivationKind.INSPECT))
    }

    private fun onSuppress(
        reason: String,
        clearPointers: Boolean = false,
    ): List<GestureEffect> {
        val wasPinching = state is MapGestureState.Pinching
        if (clearPointers) active.clear()
        state = settledState(active.isEmpty(), reason)
        return if (wasPinching) {
            listOf(GestureEffect.CancelLongPressTimer, GestureEffect.CancelPinch)
        } else {
            listOf(GestureEffect.CancelLongPressTimer)
        }
    }

    private companion object {
        const val PINCH_POINTER_COUNT = 2
    }
}

/**
 * Every terminal path funnels through here so the invariant "Idle implies no active pointers"
 * cannot be broken by adding a new transition later. A finger still on the glass keeps the gesture
 * suppressed rather than starting a new one.
 */
private fun settledState(
    activeEmpty: Boolean,
    reason: String,
): MapGestureState =
    if (activeEmpty) {
        MapGestureState.Idle
    } else {
        MapGestureState.SuppressedUntilAllPointersUp(reason)
    }

private fun distanceBetween(
    a: PointerSample,
    b: PointerSample,
): Double = hypot(a.clientX - b.clientX, a.clientY - b.clientY)

private fun midpoint(
    a: Double,
    b: Double,
): Double = (a + b) / 2.0

private fun withinSlop(
    candidate: MapGestureState.PressCandidate,
    sample: PointerSample,
): Boolean =
    abs(sample.clientX - candidate.startClientX) <= GestureConstants.MOVE_SLOP_PX &&
        abs(sample.clientY - candidate.startClientY) <= GestureConstants.MOVE_SLOP_PX

/**
 * Finger-follows-content panning: the scroll offset moves opposite to the pointer, exactly the
 * arithmetic the legacy mouse drag already used, so desktop drag behaviour is unchanged.
 */
private fun scrollFor(
    panning: MapGestureState.Panning,
    sample: PointerSample,
): GestureEffect.ScrollTo =
    GestureEffect.ScrollTo(
        scrollLeft = panning.startScrollLeft + (panning.startClientX - sample.clientX),
        scrollTop = panning.startScrollTop + (panning.startClientY - sample.clientY),
    )
