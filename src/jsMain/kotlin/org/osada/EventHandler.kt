package org.osada

import kotlinx.browser.document
import org.w3c.dom.events.Event

/**
 * Global event manager ported from the legacy `eventhandler.js`.
 *
 * The legacy implementation registers custom events on `document` and allows
 * other modules to emit/listen without holding direct references to each other.
 */
@JsExport
@JsName("EventHandler")
object EventHandler {
    private val events = mutableMapOf<String, Event>()
    private val listeners = mutableMapOf<String, MutableList<() -> Unit>>()

    fun addEvent(name: String) {
        if (events.containsKey(name)) {
            console.log("Warning: event already defined")
            return
        }
        val ev = document.createEvent("Event")
        ev.initEvent(name, bubbles = true, cancelable = true)
        events[name] = ev
    }

    fun delEvent(name: String) {
        events.remove(name)
        listeners.remove(name)
    }

    fun addListener(
        name: String,
        func: (dynamic) -> Unit,
        params: dynamic = null,
    ) {
        if (!events.containsKey(name)) {
            console.log("Can't add listener no such event: $name")
            return
        }
        val callback = { func(params) }
        listeners.getOrPut(name) { mutableListOf() }.add(callback)
        document.addEventListener(name, { callback() })
    }

    fun emitEvent(name: String) {
        val event = events[name]
        if (event == null) {
            console.log("Can't emit event no such event: $name")
            return
        }
        document.dispatchEvent(event)
    }

    fun hasEvent(name: String): Boolean = events.containsKey(name)
}
