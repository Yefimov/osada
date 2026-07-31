package org.osada.ui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary tests for the pure map gesture state machine (spec §61.1). These are the guard against
 * the two worst touch regressions: a pan that ends as an accidental move/attack, and a finger left
 * on screen after a pinch that immediately becomes a new gesture.
 */
class MapGestureReducerTest {
    private var clock = 0.0

    private fun touch(
        id: Int,
        x: Double,
        y: Double,
    ) = PointerSample(id, PointerSample.POINTER_TYPE_TOUCH, x, y, 1, clock++)

    private fun mouse(
        id: Int,
        x: Double,
        y: Double,
        buttons: Int = 1,
    ) = PointerSample(id, PointerSample.POINTER_TYPE_MOUSE, x, y, buttons, clock++)

    private fun MapGestureReducer.down(
        sample: PointerSample,
        scrollLeft: Double = 0.0,
        scrollTop: Double = 0.0,
    ) = reduce(GestureInput.Down(sample, scrollLeft, scrollTop))

    private fun activation(effects: List<GestureEffect>): GestureEffect.Activate? =
        effects.filterIsInstance<GestureEffect.Activate>().firstOrNull()

    @Test
    fun tapProducesPrimaryActivationAtReleaseCoordinates() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        r.reduce(GestureInput.Move(touch(1, 102.0, 101.0)))
        val activate = activation(r.reduce(GestureInput.Up(touch(1, 102.0, 101.0))))
        assertEquals(MapActivationKind.PRIMARY, activate?.kind)
        assertEquals(102.0, activate?.clientX)
        assertEquals(101.0, activate?.clientY)
        assertEquals(MapGestureState.Idle, r.state)
        assertEquals(0, r.activePointerCount)
    }

    @Test
    fun movementOfExactlySlopIsStillATap() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        r.reduce(GestureInput.Move(touch(1, 108.0, 100.0)))
        assertTrue(r.state is MapGestureState.PressCandidate, "8px must not start a pan")
        assertEquals(MapActivationKind.PRIMARY, activation(r.reduce(GestureInput.Up(touch(1, 108.0, 100.0))))?.kind)
    }

    @Test
    fun ninePixelsStartsPanAndSuppressesTap() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0), scrollLeft = 50.0, scrollTop = 20.0)
        val moveEffects = r.reduce(GestureInput.Move(touch(1, 109.0, 100.0)))
        assertTrue(r.state is MapGestureState.Panning)
        assertTrue(moveEffects.any { it is GestureEffect.CapturePointer })
        assertTrue(moveEffects.any { it is GestureEffect.CancelLongPressTimer })
        val scroll = moveEffects.filterIsInstance<GestureEffect.ScrollTo>().first()
        assertEquals(41.0, scroll.scrollLeft)
        assertEquals(20.0, scroll.scrollTop)
        assertNull(activation(r.reduce(GestureInput.Up(touch(1, 109.0, 100.0)))), "pan must not tap")
        assertEquals(MapGestureState.Idle, r.state)
    }

    @Test
    fun longPressFiresInspectAndConsumesTheFollowingRelease() {
        val r = MapGestureReducer()
        val effects = r.down(touch(1, 40.0, 60.0))
        assertTrue(effects.any { it is GestureEffect.StartLongPressTimer })
        val activate = activation(r.reduce(GestureInput.LongPressElapsed(1)))
        assertEquals(MapActivationKind.INSPECT, activate?.kind)
        assertTrue(r.state is MapGestureState.LongPressTriggered)
        assertNull(activation(r.reduce(GestureInput.Up(touch(1, 40.0, 60.0)))))
        assertEquals(MapGestureState.Idle, r.state)
    }

    @Test
    fun movementBeyondSlopCancelsLongPress() {
        val r = MapGestureReducer()
        r.down(touch(1, 40.0, 60.0))
        r.reduce(GestureInput.Move(touch(1, 80.0, 60.0)))
        assertNull(activation(r.reduce(GestureInput.LongPressElapsed(1))))
        assertTrue(r.state is MapGestureState.Panning)
    }

    @Test
    fun secondTouchEntersPinchAndSuppressesTap() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        val pinchStart = r.down(touch(2, 200.0, 100.0))
        assertTrue(r.state is MapGestureState.Pinching)
        val begin = pinchStart.filterIsInstance<GestureEffect.BeginPinch>().first()
        assertEquals(150.0, begin.anchorClientX)
        val update =
            r
                .reduce(GestureInput.Move(touch(2, 300.0, 100.0)))
                .filterIsInstance<GestureEffect.UpdatePinch>()
                .first()
        assertEquals(2.0, update.scale)
        assertEquals(200.0, update.anchorClientX)
    }

    @Test
    fun releasingOnePinchFingerCommitsAndTheRemainingFingerIsNotANewGesture() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        r.down(touch(2, 200.0, 100.0))
        val commit = r.reduce(GestureInput.Up(touch(2, 200.0, 100.0)))
        assertTrue(commit.any { it == GestureEffect.CommitPinch })
        assertTrue(r.state is MapGestureState.SuppressedUntilAllPointersUp)
        assertNull(activation(r.reduce(GestureInput.Up(touch(1, 100.0, 100.0)))))
        assertEquals(MapGestureState.Idle, r.state)
        assertEquals(0, r.activePointerCount)
    }

    @Test
    fun coincidentPinchFingersDoNotDivideByZero() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        r.down(touch(2, 100.0, 100.0))
        val update =
            r
                .reduce(GestureInput.Move(touch(2, 100.0, 100.0)))
                .filterIsInstance<GestureEffect.UpdatePinch>()
                .first()
        assertEquals(1.0, update.scale)
    }

    @Test
    fun pointerCancelReturnsToIdleWithoutActivation() {
        val r = MapGestureReducer()
        r.down(touch(1, 10.0, 10.0))
        val effects = r.reduce(GestureInput.Cancel(1))
        assertNull(activation(effects))
        assertEquals(MapGestureState.Idle, r.state)
        assertEquals(0, r.activePointerCount)
    }

    @Test
    fun cancellingAPinchAsksForPreviewRollback() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        r.down(touch(2, 200.0, 100.0))
        assertTrue(r.reduce(GestureInput.Cancel(2)).any { it == GestureEffect.CancelPinch })
    }

    @Test
    fun modalSuppressionDuringAGestureBlocksTheActivation() {
        val r = MapGestureReducer()
        r.down(touch(1, 10.0, 10.0))
        r.reduce(GestureInput.Suppress("modal"))
        assertTrue(r.state is MapGestureState.SuppressedUntilAllPointersUp)
        assertNull(activation(r.reduce(GestureInput.Up(touch(1, 10.0, 10.0)))))
        assertEquals(MapGestureState.Idle, r.state)
    }

    @Test
    fun browserResetClearsStrandedPointersAndAllowsTheNextGesture() {
        val r = MapGestureReducer()
        r.down(touch(1, 10.0, 10.0))
        val resetEffects = r.reduce(GestureInput.Reset("window-blur"))
        assertTrue(resetEffects.any { it == GestureEffect.CancelLongPressTimer })
        assertEquals(MapGestureState.Idle, r.state)
        assertEquals(0, r.activePointerCount)

        r.down(touch(2, 20.0, 20.0))
        assertEquals(MapActivationKind.PRIMARY, activation(r.reduce(GestureInput.Up(touch(2, 20.0, 20.0))))?.kind)
    }

    @Test
    fun browserResetRollsBackAPinchPreview() {
        val r = MapGestureReducer()
        r.down(touch(1, 100.0, 100.0))
        r.down(touch(2, 200.0, 100.0))
        val effects = r.reduce(GestureInput.Reset("window-blur"))
        assertTrue(effects.any { it == GestureEffect.CancelPinch })
        assertEquals(MapGestureState.Idle, r.state)
        assertEquals(0, r.activePointerCount)
    }

    @Test
    fun mousePrimaryTapsAndSecondaryInspectsWithoutALongPressTimer() {
        val r = MapGestureReducer()
        assertTrue(r.down(mouse(1, 5.0, 5.0)).none { it is GestureEffect.StartLongPressTimer })
        assertEquals(MapActivationKind.PRIMARY, activation(r.reduce(GestureInput.Up(mouse(1, 5.0, 5.0))))?.kind)

        val secondary = MapGestureReducer()
        secondary.down(mouse(1, 5.0, 5.0, buttons = 2))
        assertEquals(
            MapActivationKind.INSPECT,
            activation(secondary.reduce(GestureInput.Up(mouse(1, 5.0, 5.0, buttons = 2))))?.kind,
        )
    }

    @Test
    fun mouseMoveWithNoButtonDownReportsHover() {
        val r = MapGestureReducer()
        val effects = r.reduce(GestureInput.Move(mouse(1, 7.0, 9.0, buttons = 0)))
        val hover = effects.filterIsInstance<GestureEffect.Hover>().first()
        assertEquals(7.0, hover.clientX)
        assertEquals(9.0, hover.clientY)
    }

    @Test
    fun penBehavesLikeTouchForLongPress() {
        val r = MapGestureReducer()
        val sample = PointerSample(1, "pen", 20.0, 20.0, 1, clock++)
        assertTrue(r.down(sample).any { it is GestureEffect.StartLongPressTimer })
        assertEquals(MapActivationKind.INSPECT, activation(r.reduce(GestureInput.LongPressElapsed(1)))?.kind)
    }
}
