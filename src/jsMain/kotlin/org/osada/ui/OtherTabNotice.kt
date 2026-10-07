package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * The two cards of the single-writer rule (`save/ProfileWriterLock`): "open in another tab" for a
 * tab that started second, and "opened elsewhere" for a tab whose profile was taken over.
 * Styled with the first-run touch card's classes so no second modal style exists.
 */
internal object OtherTabNotice {
    private const val ID = "osadaOtherTab"

    fun showBusy(onPlayHere: () -> Unit) {
        show(
            I18n.t("tabs.busy.title"),
            I18n.t("tabs.busy.body"),
            I18n.t("tabs.busy.play_here"),
        ) {
            close()
            onPlayHere()
        }
    }

    fun showLost() {
        show(I18n.t("tabs.lost.title"), I18n.t("tabs.lost.body"), I18n.t("tabs.lost.reload")) {
            window.location.reload()
        }
    }

    private fun show(
        title: String,
        body: String,
        action: String,
        onAction: () -> Unit,
    ) {
        close()
        val overlay = document.createElement("div") as HTMLElement
        overlay.id = ID
        overlay.className = "osada-tutorial osada-other-tab"
        overlay.setAttribute("role", "alertdialog")
        overlay.setAttribute("aria-modal", "true")
        overlay.setAttribute("aria-labelledby", "$ID-title")
        val card = addTag(overlay, "div")
        card.className = "osada-tutorial__card"
        val heading = addTag(card, "div")
        heading.id = "$ID-title"
        heading.className = "osada-tutorial__title"
        heading.textContent = title
        val text = addTag(card, "p")
        text.className = "osada-tutorial__step"
        text.textContent = body
        val button = addTag(card, "div")
        button.id = "$ID-action"
        button.className = "osada-btn osada-btn--primary osada-tutorial__done"
        button.textContent = action
        button.asButton(action) { onAction() }
        document.body?.appendChild(overlay)
        button.focus()
    }

    private fun close() {
        document.getElementById(ID)?.let { it.parentNode?.removeChild(it) }
    }
}
