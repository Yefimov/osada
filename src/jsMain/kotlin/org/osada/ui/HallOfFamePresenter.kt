package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The main-menu Hall of Fame screen (design brief §14.6) — a read-only, cross-campaign collection of
 * the officers worth remembering, drawn from [HallOfFame]'s persisted store. Overlays the start
 * menu (appended to `body`, not the in-game `#mainbody`). Reuses the `.osada-hero-*` panel styling.
 */
internal object HallOfFamePresenter {
    private const val BOX_ID = "uiHallOfFame"

    fun close() = delTag(byId(BOX_ID))

    fun open() {
        close()
        val parent = document.body ?: return
        val entries = HallOfFame.all()

        val box = addTag(parent, "div")
        box.id = BOX_ID
        box.className = "osada-dossier osada-hero-roster"

        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val title = addTag(header, "div")
        title.className = "osada-hero-id"
        addText(title, "osada-hero-name", I18n.t("hero.hall_of_fame.title"))
        addText(
            title,
            "osada-hero-sub",
            I18n.t("hero.hall_of_fame.count", mapOf("count" to entries.size)),
        )
        val close = addTag(header, "span")
        close.className = "osada-ico osada-ico--close osada-hero-close"
        close.title = I18n.t("common.close.label")
        close.onclick = { _: MouseEvent -> close() }

        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"
        if (entries.isEmpty()) {
            addText(body, "osada-hero-empty", I18n.t("hero.hall_of_fame.empty"))
            return
        }
        entries.sortedByDescending { it.campaign }.forEach { entry -> renderEntry(body, entry) }
    }

    private fun renderEntry(
        body: HTMLElement,
        entry: HallOfFame.Entry,
    ) {
        val card = addTag(body, "div")
        card.className = "osada-hero-rosterrow"
        addText(card, "osada-hero-rosterrow-name", "${entry.rank} ${entry.name}".trim())
        val sub =
            listOf(entry.campaign, entry.renown, entry.potential, entry.status)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        addText(card, "osada-hero-rosterrow-sub", sub)
    }

    private fun addText(
        parent: HTMLElement,
        className: String,
        text: String,
    ): HTMLElement {
        val el = addTag(parent, "div")
        el.className = className
        el.textContent = text
        return el
    }
}
