package org.osada.ui

import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Publishes a register screen's footer height as `--osada-screen-footer-h` on its panel, so the
 * absolutely positioned `.osadaScreenBody` can end above the footer's real top edge
 * (`osada-theme.css`, `mobile.css`). A fixed `bottom` overlapped the footer as soon as a button
 * label wrapped to two lines, the language changed or the text was scaled.
 *
 * The panel itself cannot become a grid/flex container: its `display` is toggled inline by
 * makeVisible/makeHidden. A hidden panel reports a zero-height footer; that reading is ignored so
 * the last real height survives until the panel is shown again and the observer re-fires.
 */
internal object ScreenFooterReserve {
    const val VARIABLE = "--osada-screen-footer-h"

    private val observers = mutableMapOf<String, dynamic>()

    /** Watches [footer] and mirrors its height onto [panel]. Safe to call again after a rebuild. */
    fun install(
        panel: HTMLElement,
        footer: HTMLElement,
    ) {
        observers.remove(panel.id)?.disconnect()
        val ctor = window.asDynamic().ResizeObserver
        if (ctor != null && ctor != undefined) {
            // `js(...)` cannot capture Kotlin locals; see MobileLayoutController.observeHudRegions.
            val construct = js("(function(C, cb) { return new C(cb); })")
            val observer = construct(ctor, { publish(panel, footer) })
            observer.observe(footer)
            observers[panel.id] = observer
        }
        publish(panel, footer)
    }

    private fun publish(
        panel: HTMLElement,
        footer: HTMLElement,
    ) {
        val height = footer.getBoundingClientRect().height
        if (height > 0) panel.style.setProperty(VARIABLE, "${height}px")
    }
}
