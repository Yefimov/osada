package org.osada

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the EventHandler global event manager.
 */
class EventHandlerTest {
    @Test
    fun canAddAndCheckEvent() {
        EventHandler.addEvent("test-event-check")
        assertTrue(EventHandler.hasEvent("test-event-check"))
    }

    @Test
    fun canEmitEventAndListenerIsCalled() {
        EventHandler.addEvent("test-event-emit")
        var called = false
        EventHandler.addListener("test-event-emit", { called = true }, null)
        EventHandler.emitEvent("test-event-emit")
        assertTrue(called)
    }

    @Test
    fun listenerReceivesParams() {
        EventHandler.addEvent("test-event-params")
        var received: dynamic = null
        EventHandler.addListener("test-event-params", { params -> received = params }, 42)
        EventHandler.emitEvent("test-event-params")
        assertEquals(42, received)
    }

    @Test
    fun canDeleteEvent() {
        EventHandler.addEvent("test-event-delete")
        assertTrue(EventHandler.hasEvent("test-event-delete"))
        EventHandler.delEvent("test-event-delete")
        assertFalse(EventHandler.hasEvent("test-event-delete"))
    }
}
