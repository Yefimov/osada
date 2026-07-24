package org.osada.ui

import org.osada.hero.CommanderRow
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroId
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The campaign commander roster (design brief §14.3) — Headquarters → Commanders, with
 * Active / Reserve / Wounded / Missing / Fallen tabs. View-only: a row opens the leader dossier;
 * transfers happen between scenarios, not here. Built from the pure [CommanderRow] list, all label
 * text from [HeroDisplay], dynamic values via `textContent`.
 */
internal object CommanderRosterPresenter {
    private const val BOX_ID = "uiCommanderRoster"

    fun close() = delTag(byId(BOX_ID))

    fun open() {
        close()
        val mainBody = byId("mainbody") ?: return
        val byTab = HeroCampaign.commanders().groupBy { HeroDisplay.rosterTab(it.status) }

        val box = addTag(mainBody, "div")
        box.id = BOX_ID
        box.className = "osada-dossier osada-hero-roster"

        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val title = addTag(header, "div")
        title.className = "osada-hero-id"
        addText(title, "osada-hero-name", "Headquarters — Commanders")
        addText(title, "osada-hero-sub", "${HeroCampaign.commanders().size} officers").title =
            "Campaign commander roster. Select an officer to open the full dossier."
        val close = addTag(header, "span")
        close.className = "osada-ico osada-ico--close osada-hero-close"
        close.title = "Close"
        close.onclick = { _: MouseEvent -> close() }

        val tabBar = addTag(box, "div")
        tabBar.className = "osada-hero-tabs"
        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"

        val buttons =
            HeroDisplay.ROSTER_TABS.map { tab ->
                val count = byTab[tab]?.size ?: 0
                tabButton(tabBar, "$tab ($count)")
            }

        fun select(index: Int) {
            clearTag(body)
            buttons.forEachIndexed { i, b -> toggleButton(b, i == index) }
            renderTab(body, byTab[HeroDisplay.ROSTER_TABS[index]].orEmpty())
        }
        buttons.forEachIndexed { i, b -> b.onclick = { _: MouseEvent -> select(i) } }
        select(0)
    }

    private fun renderTab(
        body: HTMLElement,
        rows: List<CommanderRow>,
    ) {
        if (rows.isEmpty()) {
            addText(body, "osada-hero-empty", "No officers in this category.")
            return
        }
        rows.sortedBy { it.name }.forEach { row -> renderRow(body, row) }
    }

    private fun renderRow(
        body: HTMLElement,
        row: CommanderRow,
    ) {
        val card = addTag(body, "div")
        card.className = "osada-hero-rosterrow"

        val portrait = addTag(card, "div")
        portrait.className = "osada-hero-rosterrow-portrait"
        portrait.textContent =
            row.name
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
        HeroCampaign.dossier(HeroId(row.heroId))?.let { dossier ->
            PortraitRenderer.render(
                portrait,
                dossier.portrait,
                dossier.portraitSeed,
                gray = dossier.inMemoriam,
            )
        }

        val copy = addTag(card, "div")
        copy.className = "osada-hero-rosterrow-copy"
        addText(copy, "osada-hero-rosterrow-name", "${row.rank} ${row.name}")
        val sub = listOfNotNull(row.formationName, row.potential, row.renown, row.statusLabel).joinToString(" · ")
        addText(copy, "osada-hero-rosterrow-sub", sub)

        val locate = addTag(card, "button")
        locate.className = "osada-hero-locate"
        locate.textContent = "Locate"
        locate.title = "Select this commander's deployed formation on the map"
        locate.onclick = { e: MouseEvent ->
            e.stopPropagation()
            locateHero(HeroId(row.heroId))
        }
        card.onclick = { _: MouseEvent -> LeaderDossierPresenter.openForHero(HeroId(row.heroId)) }
    }

    private fun tabButton(
        bar: HTMLElement,
        label: String,
    ): HTMLElement {
        val b = addTag(bar, "div")
        b.className = "osada-hero-tab"
        b.textContent = label
        b.title = "Show commanders whose current status is ${label.substringBefore(" (").lowercase()}"
        return b
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
