package org.osada.ui.keyboard

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.ui.ManualLink
import org.osada.ui.addTag
import org.osada.ui.byId
import org.osada.ui.clearTag
import org.osada.ui.delTag
import org.w3c.dom.HTMLElement

/**
 * The `F1` / `?` Controls quick reference
 * (`docs/design/keyboard-shortcuts-and-help.md` §6): a short in-game card, not a second manual.
 *
 * Every row is generated from [CommandCatalog], so a binding can never appear here without being
 * dispatchable, and key caps stay locale-independent while the labels are localized.
 */
internal object ControlsCard {
    const val CARD_ID = "osadaControlsCard"

    private var previouslyFocused: HTMLElement? = null

    fun isOpen(): Boolean = byId(CARD_ID) != null

    fun toggle() {
        if (isOpen()) close() else open()
    }

    fun open() {
        if (isOpen()) return
        val mainBody = byId("mainbody") ?: return
        previouslyFocused = document.activeElement as? HTMLElement

        val card = addTag(mainBody, "div")
        card.id = CARD_ID
        card.className = "osada-controls-card"
        card.setAttribute("role", "dialog")
        card.setAttribute("aria-modal", "true")
        card.setAttribute("aria-label", I18n.t("controls.title"))
        card.setAttribute("tabindex", "-1")

        val title = addTag(card, "div")
        title.className = "osada-controls-card__title"
        title.textContent = I18n.t("controls.title")

        val body = addTag(card, "div")
        body.className = "osada-controls-card__body"
        CommandGroup.entries.forEach { group -> addGroup(body, group) }

        val footer = addTag(card, "div")
        footer.className = "osada-controls-card__footer"
        footer.textContent = I18n.t("controls.footer.close")
        // The footer used to END with "The full manual has the complete rules" and offer no way to
        // reach it -- `manual.html` shipped with nothing in the game linking to it. The sentence is
        // now a real link (`ManualLink`, shared with the main-menu entry); a real <a> rather than a
        // scripted div so it keeps middle-click, "open in new tab" and keyboard activation for free.
        addTag(footer, "span").textContent = " "
        val manualLink = addTag(footer, "a")
        manualLink.className = "osada-controls-card__manual"
        manualLink.textContent = I18n.t("controls.footer.manual")
        manualLink.setAttribute("href", ManualLink.FILE)
        manualLink.setAttribute("target", "_blank")
        manualLink.setAttribute("rel", "noopener")

        val closeButton = addTag(card, "button")
        closeButton.className = "osada-button osada-controls-card__close"
        closeButton.textContent = I18n.t("controls.close.label")
        closeButton.onclick = { _ -> close() }

        card.addEventListener("keydown", { event -> trapTab(event.asDynamic(), card) })
        closeButton.focus()
    }

    fun close() {
        byId(CARD_ID)?.let {
            clearTag(it)
            delTag(it)
        }
        previouslyFocused?.focus()
        previouslyFocused = null
    }

    private fun addGroup(
        body: HTMLElement,
        group: CommandGroup,
    ) {
        val section = addTag(body, "div")
        section.className = "osada-controls-group"
        val heading = addTag(section, "div")
        heading.className = "osada-controls-group__title"
        heading.textContent = I18n.t("controls.group.${group.name.lowercase()}.title")
        CommandCatalog.cardRows(group).forEach { row -> addRow(section, row) }
    }

    private fun addRow(
        section: HTMLElement,
        entry: CommandCatalog.CardRow,
    ) {
        val row = addTag(section, "div")
        row.className = "osada-controls-row"
        row.setAttribute("data-command", entry.id)
        val cap = addTag(row, "span")
        cap.className = "osada-controls-row__cap"
        cap.textContent = entry.cap
        val text = addTag(row, "span")
        text.className = "osada-controls-row__text"
        val label = addTag(text, "span")
        label.className = "osada-controls-row__label"
        label.textContent = I18n.t(entry.labelKey)
        val help = addTag(text, "span")
        help.className = "osada-controls-row__help"
        help.textContent = I18n.t(entry.helpKey)
    }

    /** Tab stays inside the card while it is open; the only focusable child is the Close button,
     *  so the trap is a single wrap back onto it. */
    private fun trapTab(
        event: dynamic,
        card: HTMLElement,
    ) {
        if (event.key != "Tab") return
        event.preventDefault()
        (card.querySelector(".osada-controls-card__close") as? HTMLElement)?.focus()
    }
}
