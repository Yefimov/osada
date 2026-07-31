package org.osada.ui

import kotlinx.browser.window

/**
 * A brief status strip above the bottom dock (spec §18).
 *
 * On desktop the terrain/coordinate line lives permanently in the top bar. A phone top bar has no
 * room for it, and the information is still needed after a tap on an empty hex — so the same text
 * is surfaced here for a few seconds instead. Purely presentational: it repeats what
 * [updateMapLocationMessage] already computed and reveals nothing extra.
 */
internal object MobileStatusStrip {
    private const val STRIP_ID = "osadaMobileStatus"
    private const val VISIBLE_MS = 2600

    private var hideTimer = 0

    fun show(text: String) {
        if (!MobileLayoutController.mode.isPhone || text.isEmpty()) return
        val strip = ensureStrip()
        strip.textContent = text
        strip.classList.add("osada-mobile-status--visible")
        if (hideTimer != 0) window.clearTimeout(hideTimer)
        hideTimer =
            window.setTimeout({
                hideTimer = 0
                strip.classList.remove("osada-mobile-status--visible")
            }, VISIBLE_MS)
    }

    private fun ensureStrip(): org.w3c.dom.HTMLElement {
        byId(STRIP_ID)?.let { return it }
        val strip = addTag("mainbody", "div")
        strip.id = STRIP_ID
        strip.className = "osada-mobile-status"
        // Restrained: the map itself is the primary surface, so this is polite, not assertive.
        strip.setAttribute("aria-live", "polite")
        return strip
    }
}
