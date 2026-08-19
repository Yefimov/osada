package org.osada.ui

import kotlinx.browser.window

/**
 * The one place that knows where the field manual lives and how it is opened.
 *
 * Two surfaces link to it -- the main-menu entry ([StartMenuMainButtons]) and the `F1` Controls
 * card footer ([org.osada.ui.keyboard.ControlsCard]) -- and before this object existed neither
 * did: `manual.html` shipped in `resources/` with nothing in the game pointing at it, while the
 * Controls card's own footer already told the player that *"the full manual has the complete
 * rules"*. A promise with no link is worse than no promise.
 *
 * Opened in a new tab rather than navigated to, because the game is a single-page application:
 * navigating away would discard the running battle, and the browser Back button is not a save.
 *
 * [FILE] is deliberately relative. The distribution is served from whatever path the host chooses
 * (`jsBrowserDistribution` output, a subdirectory, a `file://` copy), and an absolute path would
 * break every one of those but the first.
 */
internal object ManualLink {
    const val FILE = "manual.html"

    /** Opens the manual in a new tab. `noopener` because the manual has no business reaching back
     *  into the game's `window` -- it is a static page, not part of the application. */
    fun open() {
        window.open(FILE, "_blank", "noopener")
    }
}
