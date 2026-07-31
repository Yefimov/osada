package org.osada.ui.input

import org.osada.ui.input.GestureConstants.MOVE_SLOP_PX

/**
 * Recognition thresholds for the map gesture layer, in CSS pixels / milliseconds.
 *
 * Kept in ONE place (spec §20) so the reducer, its boundary tests and any future tuning all read
 * the same numbers. A tap is a pointer release inside [MOVE_SLOP_PX] **before** the long-press
 * timer fires — deliberately NOT a separate "tap duration" limit, which would leave a dead
 * interval between "too slow for a tap" and "not yet a long press".
 */
internal object GestureConstants {
    const val LONG_PRESS_DELAY_MS = 500.0
    const val MOVE_SLOP_PX = 8.0
    const val PAN_START_THRESHOLD_PX = 8.0

    /** Floor for the pinch start distance so `current / start` can never divide by ~zero. */
    const val PINCH_MIN_DISTANCE_PX = 12.0
}

/**
 * One pointer observation, flattened out of a DOM `PointerEvent` so the reducer never touches a
 * DOM type (and tests can synthesise input without a browser).
 */
internal data class PointerSample(
    val pointerId: Int,
    val pointerType: String,
    val clientX: Double,
    val clientY: Double,
    val buttons: Int,
    val timeStamp: Double,
) {
    /** Mouse/pen right button. Bit 1 of `PointerEvent.buttons`, per the UI Events spec. */
    val isSecondaryButton: Boolean get() = (buttons and SECONDARY_BUTTON_BIT) != 0

    /** Only touch contacts participate in pinch; a mouse cannot produce a second contact. */
    val isTouch: Boolean get() = pointerType == POINTER_TYPE_TOUCH

    companion object {
        const val POINTER_TYPE_TOUCH = "touch"
        const val POINTER_TYPE_MOUSE = "mouse"
        private const val SECONDARY_BUTTON_BIT = 2
    }
}

/**
 * What the map gesture layer decided the player meant. Target preview/confirmation happens
 * further down the chain in the click handler, which is the only place that knows whether the
 * resolved hex holds an attackable enemy. The gesture layer itself only emits [PRIMARY] and
 * [INSPECT], because it must not contain attack predicates (spec §32).
 */
internal enum class MapActivationKind {
    PRIMARY,
    INSPECT,
}

/** The gesture state machine's states (spec §30.1). */
internal sealed interface MapGestureState {
    data object Idle : MapGestureState

    /** One pointer down, still undecided between tap, long press and pan. */
    data class PressCandidate(
        val pointerId: Int,
        val startClientX: Double,
        val startClientY: Double,
        val startTimeMs: Double,
        val startScrollLeft: Double,
        val startScrollTop: Double,
        val secondary: Boolean,
    ) : MapGestureState

    data class Panning(
        val pointerId: Int,
        val startClientX: Double,
        val startClientY: Double,
        val startScrollLeft: Double,
        val startScrollTop: Double,
    ) : MapGestureState

    data class Pinching(
        val pointerA: Int,
        val pointerB: Int,
        val startDistance: Double,
    ) : MapGestureState

    data class LongPressTriggered(
        val pointerId: Int,
    ) : MapGestureState

    /**
     * A terminal-but-not-yet-Idle state: the gesture is over, yet fingers are still down. Without
     * it the finger left on screen after a pinch would immediately read as a fresh tap or pan.
     */
    data class SuppressedUntilAllPointersUp(
        val reason: String,
    ) : MapGestureState
}

/** Everything that can drive the reducer. The adapter owns timers and DOM; this is the boundary. */
internal sealed interface GestureInput {
    /** Scroll offsets are passed in because the reducer must not read the DOM to anchor a pan. */
    data class Down(
        val sample: PointerSample,
        val scrollLeft: Double,
        val scrollTop: Double,
    ) : GestureInput

    data class Move(
        val sample: PointerSample,
    ) : GestureInput

    data class Up(
        val sample: PointerSample,
    ) : GestureInput

    data class Cancel(
        val pointerId: Int,
    ) : GestureInput

    data class LongPressElapsed(
        val pointerId: Int,
    ) : GestureInput

    /** Modal opened, animation lock engaged, capture lost — anything that voids the gesture. */
    data class Suppress(
        val reason: String,
    ) : GestureInput

    /**
     * The browser can abandon every contact at once without delivering pointercancel (for
     * example when the tab loses focus). Unlike [Suppress], this clears the pointer table
     * immediately so the next real pointerdown starts a fresh gesture.
     */
    data class Reset(
        val reason: String,
    ) : GestureInput
}

/**
 * Instructions the reducer hands back to its DOM adapter. The reducer performs no side effects of
 * its own, which is what makes tap/pan/pinch separation unit-testable without a browser.
 */
internal sealed interface GestureEffect {
    data class StartLongPressTimer(
        val pointerId: Int,
    ) : GestureEffect

    data object CancelLongPressTimer : GestureEffect

    data class CapturePointer(
        val pointerId: Int,
    ) : GestureEffect

    data class ScrollTo(
        val scrollLeft: Double,
        val scrollTop: Double,
    ) : GestureEffect

    data class BeginPinch(
        val anchorClientX: Double,
        val anchorClientY: Double,
    ) : GestureEffect

    /** [scale] is relative to the zoom captured at [BeginPinch]; clamping belongs to `MapZoom`. */
    data class UpdatePinch(
        val scale: Double,
        val anchorClientX: Double,
        val anchorClientY: Double,
    ) : GestureEffect

    data object CommitPinch : GestureEffect

    data object CancelPinch : GestureEffect

    /** The only effect that may reach game rules, and only through the normal click routing. */
    data class Activate(
        val clientX: Double,
        val clientY: Double,
        val kind: MapActivationKind,
    ) : GestureEffect

    /** Hover-equivalent pointer motion (fine pointers only) — cursor drawing and location text. */
    data class Hover(
        val clientX: Double,
        val clientY: Double,
    ) : GestureEffect
}
