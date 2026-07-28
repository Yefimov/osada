package org.osada.ui

import org.osada.hero.CommanderRow
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroId
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The campaign commander roster (design brief §14.3) — Headquarters → Commanders, with
 * Active / Reserve / Wounded / Missing / Fallen tabs. A row opens the leader dossier; an eligible
 * benched officer (Reserve/Wounded, currently unassigned — [HeroCampaign.transferableFormations])
 * also gets a Transfer action (DEFERRED.md §1.10), the "way back" a wounded or evacuated commander
 * previously had no route to. Built from the pure [CommanderRow] list, dynamic values via
 * `textContent`.
 *
 * **Localized in full (DEFERRED.md §4.15).** §7.37 localized only the transfer picker inside this
 * file and left the roster around it in English, which was the worst of both. Note what the tab
 * labels do now: [HeroDisplay.ROSTER_TABS]' strings are treated as **ids** for grouping and looked
 * up as `hero.roster.tab.<id>` for display. The previous tooltip interpolated the visible label
 * into an English sentence (`"…status is ${label.substringBefore(" (").lowercase()}"`), which would
 * have produced half-Russian text the moment those labels were localized at the source.
 */
internal object CommanderRosterPresenter {
    private const val BOX_ID = "uiCommanderRoster"
    private const val TRANSFER_BOX_ID = "uiHeroTransferBox"

    fun close() = delTag(byId(BOX_ID))

    /** Whether the roster is on screen — see [MainMenuButtonHandler.handleGlobalEscape] (§4.13). */
    fun isOpen(): Boolean = byId(BOX_ID) != null

    /** Whether the transfer picker is on screen. It layers ABOVE the roster, so Escape has to
     *  offer it first — see [MainMenuButtonHandler.handleGlobalEscape] (§4.13). */
    fun isTransferPickerOpen(): Boolean = byId(TRANSFER_BOX_ID) != null

    fun closeTransferPicker() = delTag(byId(TRANSFER_BOX_ID))

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
        addText(title, "osada-hero-name", I18n.t("hero.roster.title"))
        addText(
            title,
            "osada-hero-sub",
            I18n.t("hero.roster.count", mapOf("count" to HeroCampaign.commanders().size)),
        ).title = I18n.t("hero.roster.help")
        val close = addTag(header, "span")
        close.className = "osada-ico osada-ico--close osada-hero-close"
        close.title = I18n.t("common.close.label")
        close.asButton(ariaLabel = I18n.t("common.close.label")) { close() }

        val tabBar = addTag(box, "div")
        tabBar.className = "osada-hero-tabs"
        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"

        val buttons =
            HeroDisplay.ROSTER_TABS.map { tab ->
                val count = byTab[tab]?.size ?: 0
                tabButton(tabBar, tab, count)
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
            addText(body, "osada-hero-empty", I18n.t("hero.roster.empty"))
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
        portrait.className = "osada-hero-rosterrow-portrait ${row.renownClass}".trim()
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
                artPath = dossier.portraitArt,
            )
        }

        val copy = addTag(card, "div")
        copy.className = "osada-hero-rosterrow-copy"
        addText(copy, "osada-hero-rosterrow-name", "${row.rank} ${row.name}")
        val sub = listOfNotNull(row.formationName, row.potential, row.renown, row.statusLabel).joinToString(" · ")
        addText(copy, "osada-hero-rosterrow-sub", sub)

        val locate = addTag(card, "button")
        locate.className = "osada-hero-locate"
        locate.textContent = I18n.t("hero.roster.locate.label")
        locate.title = I18n.t("hero.roster.locate.help")
        locate.onclick = { e: MouseEvent ->
            e.stopPropagation()
            locateHero(HeroId(row.heroId))
        }
        if (row.formationName == null && HeroCampaign.isTransferEligible(row.status)) {
            val transfer = addTag(card, "button")
            transfer.className = "osada-hero-locate"
            transfer.textContent = I18n.t("hero.roster.transfer.label")
            transfer.title = I18n.t("hero.roster.transfer.help")
            transfer.onclick = { e: MouseEvent ->
                e.stopPropagation()
                openTransferPicker(HeroId(row.heroId), row.name)
            }
        }
        card.onclick = { _: MouseEvent -> LeaderDossierPresenter.openForHero(HeroId(row.heroId)) }
    }

    // Reuses HeroPromotionPresenter's `.osada-hpp` dialog shape (DEFERRED.md §4.10/§4.12) rather
    // than the legacy `.smallButton heroPromotionChoice` this used to copy: same ICON-font trap
    // (real words rendering as glyphs) and same hardcoded z-index that opens behind #equipment.
    private fun openTransferPicker(
        heroId: HeroId,
        heroName: String,
    ) {
        val mainBody = byId("mainbody") ?: return
        closeTransferPicker()
        val choices = HeroCampaign.transferableFormations(heroId)

        val box = addTag(mainBody, "div")
        box.id = TRANSFER_BOX_ID
        box.className = "osada-hpp"

        val titleEl = addTag(box, "div")
        titleEl.className = "osada-hpp__title"
        titleEl.textContent = I18n.t("hero.roster.transfer.title", mapOf("name" to heroName))

        if (choices.isEmpty()) {
            val bodyEl = addTag(box, "div")
            bodyEl.className = "osada-hpp__body"
            bodyEl.textContent = I18n.t("hero.roster.transfer.empty")
        } else {
            choices.forEach { formation ->
                val option = addTag(box, "div")
                option.className = "osada-hpp__choice"
                option.textContent = formation.displayName
                option.asButton {
                    HeroCampaign.transferCommander(heroId, formation.id)
                    delTag(box)
                    open()
                }
            }
        }
        val cancel = addTag(box, "div")
        cancel.className = "osada-hpp__choice"
        cancel.textContent = I18n.t("common.cancel.label")
        cancel.asButton { delTag(box) }
    }

    /** [tabId] is the untranslated [HeroDisplay.ROSTER_TABS] entry — the grouping key, not display
     *  text. Both the label and its tooltip are looked up from it, so neither can end up half
     *  translated (§4.15). */
    private fun tabButton(
        bar: HTMLElement,
        tabId: String,
        count: Int,
    ): HTMLElement {
        val b = addTag(bar, "div")
        val status = I18n.t("hero.roster.tab.${tabId.lowercase()}")
        b.className = "osada-hero-tab"
        b.textContent = I18n.t("hero.roster.tab.label", mapOf("status" to status, "count" to count))
        b.title = I18n.t("hero.roster.tab.help", mapOf("status" to status.lowercase()))
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
