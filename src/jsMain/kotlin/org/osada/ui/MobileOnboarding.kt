package org.osada.ui

import org.osada.i18n.I18n
import org.osada.uiSettings

/**
 * The first-run touch controls card (spec §51).
 *
 * Touch controls are not self-evident — nothing on screen says that holding a hex inspects it, or
 * that the first tap on an enemy previews combat instead of attacking. The flag is *versioned*
 * rather than boolean so that changing the gestures later can re-show the card to players who
 * already dismissed the old one, instead of silently leaving them with stale muscle memory.
 *
 * Shown only on a phone/tablet shell, always dismissible, and replayable from Settings.
 */
internal object MobileOnboarding {
    /** Bump when the gestures themselves change, not when the wording is edited. */
    const val CURRENT_VERSION = 1

    private const val OVERLAY_ID = "osadaGestureTutorial"

    private val stepKeys =
        listOf(
            "mobile.tutorial.tap",
            "mobile.tutorial.drag",
            "mobile.tutorial.pinch",
            "mobile.tutorial.hold",
            "mobile.tutorial.combat",
        )

    fun showIfNeeded() {
        if (!MobileLayoutController.mode.isMobileShell) return
        if (uiSettings.gestureTutorialVersion >= CURRENT_VERSION) return
        show()
    }

    /** Also the Settings "Replay" action, which is why it is public and does not check the flag. */
    fun show() {
        byId(OVERLAY_ID)?.let { delTag(it) }
        val overlay = addTag("mainbody", "div")
        overlay.id = OVERLAY_ID
        overlay.className = "osada-tutorial"
        overlay.setAttribute("role", "dialog")
        overlay.setAttribute("aria-modal", "true")

        val card = addTag(overlay, "div")
        card.className = "osada-tutorial__card"
        val title = addTag(card, "div")
        title.className = "osada-tutorial__title"
        title.textContent = I18n.t("mobile.tutorial.title")

        val list = addTag(card, "ul")
        list.className = "osada-tutorial__list"
        stepKeys.forEach { key ->
            val item = addTag(list, "li")
            item.className = "osada-tutorial__step"
            item.textContent = I18n.t(key)
        }

        val done = addTag(card, "div")
        done.className = "osada-btn osada-btn--primary osada-tutorial__done"
        done.textContent = I18n.t("mobile.tutorial.done")
        done.asButton(I18n.t("mobile.tutorial.done")) { dismiss() }
    }

    private fun dismiss() {
        uiSettings.gestureTutorialVersion = CURRENT_VERSION
        byId(OVERLAY_ID)?.let { delTag(it) }
    }
}
