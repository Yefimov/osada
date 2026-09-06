package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * Covers the battlefield while the next one is being fetched.
 *
 * Loading a scenario is asynchronous twice over -- an XHR for the XML, then the terrain and unit
 * image cache -- and nothing used to repaint the canvas in between. A player who left a battle for
 * the main menu and started another one sat looking at the map they had just left, units and all,
 * for as long as the new one took to arrive: it read as the game having ignored the click.
 *
 * The curtain is the main menu's own backdrop with one line of text on it, so the hand-off is
 * start menu -> the same picture -> the new map, with no stale battle in the middle.
 *
 * It sits above the map and HUD but below the campaign briefing (`z-index: 1200`), which opens
 * before the image cache finishes on purpose and must not be curtained off; [hide] is called there
 * as well, so whichever comes first wins and the call is idempotent.
 */
object ScenarioLoadingCurtain {
    private const val ID = "scenarioLoadingCurtain"

    /** Raise the curtain. Safe to call when it is already up. */
    fun show() {
        val curtain = element()
        // Re-read the caption every time: the player can change language between battles, and the
        // curtain outlives any one locale because it is built once and reused.
        curtain.querySelector(".osada-loadcurtain__text")?.textContent = I18n.t("game.loading.scenario")
        curtain.style.display = "flex"
    }

    /** Drop it. Safe to call when it was never raised — the restore paths do exactly that. */
    fun hide() {
        (document.getElementById(ID) as? HTMLElement)?.style?.display = "none"
    }

    private fun element(): HTMLElement = (document.getElementById(ID) as? HTMLElement) ?: build()

    private fun build(): HTMLElement {
        val curtain = addTag(document.body, "div")
        curtain.id = ID
        curtain.className = "osada-loadcurtain"
        val text = addTag(curtain, "div")
        text.className = "osada-loadcurtain__text"
        return curtain
    }
}
