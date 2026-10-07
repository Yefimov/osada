package org.osada.multiplayer.ui

import org.osada.GameHolder
import org.osada.hero.HeroBiographyNarrator
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroLifePath
import org.osada.i18n.I18n
import org.osada.model.getPlayer
import org.osada.model.getUnitById
import org.osada.multiplayer.coop.CoopAbilityCatalogue
import org.osada.multiplayer.coop.CoopCommander
import org.osada.multiplayer.coop.CoopCounters
import org.osada.ui.addTag
import org.osada.ui.asButton
import org.osada.ui.byId
import org.osada.ui.clearTag
import org.osada.ui.delTag
import org.osada.ui.toggleButton
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * One player's dossier in a cooperative room, laid out like a heroic commander's
 * (`LeaderDossierPresenter`): portrait and status in the header, then Overview (biography and the
 * chosen abilities with what each does), Units, and Score tabs. Opened from [CoopCommanderRoster]
 * or from a unit card's commander line. Only your own dossier offers to change your map mark.
 */
internal object CoopCommanderDossier {
    private const val DOSSIER_ID = "uiCoopDossier"
    private const val RANK_ID = "colonel"

    private var openFor: String? = null
    private var dossierTab = 0
    private var pickingSymbol = false

    fun isOpen(): Boolean = byId(DOSSIER_ID) != null

    fun close() {
        delTag(byId(DOSSIER_ID))
        openFor = null
        pickingSymbol = false
    }

    fun refresh(model: CoopHud.Model) {
        openFor?.let { id -> if (isOpen()) open(model, id, keepTab = true) }
    }

    fun open(
        model: CoopHud.Model,
        commanderId: String,
        keepTab: Boolean = false,
    ) {
        val commander = model.state.commanderById(commanderId) ?: return
        delTag(byId(DOSSIER_ID))
        if (!keepTab || openFor != commanderId) {
            dossierTab = 0
            pickingSymbol = false
        }
        openFor = commanderId
        val mainBody = byId("mainbody") ?: return
        val isSelf = commander.id == model.selfCommanderId
        val box = addTag(mainBody, "div")
        box.id = DOSSIER_ID
        box.className = "osada-dossier osada-hero-dossier"

        header(box, model, commander, isSelf)
        if (isSelf && pickingSymbol) {
            box.appendChild(
                CoopHud.symbolPicker(commander.seat, commander.symbol) { symbol ->
                    pickingSymbol = false
                    model.onSetSymbol(symbol)
                },
            )
        }

        tabs(box, model, commander)
    }

    private fun header(
        box: HTMLElement,
        model: CoopHud.Model,
        commander: CoopCommander,
        isSelf: Boolean,
    ) {
        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        header.appendChild(CoopHud.portrait(commander, "osada-hero-portrait"))
        val id = addTag(header, "div")
        id.className = "osada-hero-id"
        CoopDossierBits.addText(id, "osada-hero-name", "${I18n.t("hero.rank.$RANK_ID")} ${commander.name}")
        val sub = addTag(id, "div")
        sub.className = "osada-hero-sub osada-coop-nameline"
        sub.appendChild(CoopHud.seatMark(commander, "osada-coop-seat"))
        addTag(sub, "span").textContent =
            I18n.t("multiplayer.coop.dossier.seat", mapOf("seat" to commander.seat + 1)) +
            if (isSelf) " · " + I18n.t("multiplayer.coop.roster.you") else ""
        val status = CoopDossierBits.statusOf(model, commander)
        CoopDossierBits.addText(id, "osada-hero-status", I18n.t("hero.dossier.status", mapOf("value" to status)))
        if (isSelf) {
            val change = addTag(header, "button")
            change.className = "osada-hero-locate osada-hero-locate--header"
            change.textContent = I18n.t("multiplayer.coop.symbol.change")
            change.onclick = { e: MouseEvent ->
                e.stopPropagation()
                pickingSymbol = !pickingSymbol
                open(model, commander.id, keepTab = true)
            }
        }
        CoopDossierBits.closeButton(header) {
            delTag(byId(DOSSIER_ID))
            openFor = null
        }
    }

    private fun tabs(
        box: HTMLElement,
        model: CoopHud.Model,
        commander: CoopCommander,
    ) {
        val tabBar = addTag(box, "div")
        tabBar.className = "osada-hero-tabs"
        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"
        val tabs =
            listOf(
                "overview" to { p: HTMLElement -> overview(p, model, commander) },
                "units" to { p: HTMLElement -> units(p, model, commander) },
                "score" to { p: HTMLElement -> score(p, model, commander) },
            )
        val buttons =
            tabs.map { (key, _) ->
                addTag(tabBar, "div").also {
                    it.className = "osada-hero-tab"
                    it.textContent = I18n.t("multiplayer.coop.dossier.tab.$key")
                }
            }

        fun select(index: Int) {
            dossierTab = index
            clearTag(body)
            buttons.forEachIndexed { i, b -> toggleButton(b, i == index) }
            tabs[index].second(body)
        }
        buttons.forEachIndexed { i, b -> b.onclick = { _: MouseEvent -> select(i) } }
        select(dossierTab.coerceIn(0, tabs.lastIndex))
    }

    private fun overview(
        parent: HTMLElement,
        model: CoopHud.Model,
        commander: CoopCommander,
    ) {
        val record = biography(model, commander)
        if (record.isNotEmpty()) {
            CoopDossierBits.section(parent, I18n.t("hero.dossier.section.personal_record")) { s ->
                record.forEach { CoopDossierBits.addText(s, "osada-hero-line", it) }
            }
        }
        CoopDossierBits.section(
            parent,
            I18n.t(
                "multiplayer.coop.abilities.title",
                mapOf("used" to commander.abilityIds.size, "cap" to model.state.slotCap()),
            ),
        ) { s ->
            val abilities = commander.abilityIds.mapNotNull(CoopAbilityCatalogue::byId)
            if (abilities.isEmpty()) {
                CoopDossierBits.addText(
                    s,
                    "osada-hero-empty",
                    I18n.t("multiplayer.coop.abilities.none"),
                )
            }
            abilities.forEach { ability ->
                val line = HeroDisplay.trait(ability.leader, "")
                val row = addTag(s, "div")
                row.className = "osada-hero-trait"
                CoopDossierBits.addText(row, "osada-hero-trait-title", line.title)
                CoopDossierBits.addText(row, "osada-hero-trait-effect", line.effect)
                CoopDossierBits.addText(
                    row,
                    "osada-hero-trait-cond",
                    I18n.t("hero.dossier.trait.applies", mapOf("activation" to line.activation)),
                )
            }
        }
    }

    private fun units(
        parent: HTMLElement,
        model: CoopHud.Model,
        commander: CoopCommander,
    ) {
        val game = GameHolder.instance
        val map = game?.scenario?.map
        val ids = model.state.unitsOf(commander.id)
        CoopDossierBits.section(parent, I18n.t("multiplayer.coop.hud.units", mapOf("count" to ids.size))) { s ->
            if (ids.isEmpty()) {
                CoopDossierBits.addText(
                    s,
                    "osada-hero-empty",
                    I18n.t("multiplayer.coop.dossier.no_units"),
                )
            }
            ids.mapNotNull { map?.getUnitById(it) }.forEach { unit ->
                val row = addTag(s, "div")
                row.className = "osada-hero-kv osada-coop-unitrow"
                CoopDossierBits.addText(row, "osada-hero-kv-key", unit.unitData(true).name)
                CoopDossierBits.addText(
                    row,
                    "osada-hero-kv-val",
                    I18n.t(
                        "multiplayer.coop.dossier.strength",
                        mapOf(
                            "value" to unit.strength,
                        ),
                    ),
                )
                row.asButton(ariaLabel = unit.unitData(true).name) {
                    val ui = game?.ui ?: return@asButton
                    close()
                    ui.uiUnitSelect(unit)
                    unit.getPos()?.let(ui::uiSetCellOnViewPort)
                }
            }
        }
    }

    private fun score(
        parent: HTMLElement,
        model: CoopHud.Model,
        commander: CoopCommander,
    ) {
        val state = model.state
        CoopDossierBits.section(parent, I18n.t("multiplayer.coop.dossier.mission_score")) { s ->
            CoopReportView
                .statLines(state.ledger.perCommander[commander.id] ?: CoopCounters())
                .forEach { CoopDossierBits.addText(s, "osada-hero-line", it) }
        }
        if (state.summaries.isNotEmpty()) {
            CoopDossierBits.section(parent, I18n.t("multiplayer.coop.dossier.campaign_score")) { s ->
                CoopReportView
                    .statLines(CoopReportView.campaignTotals(state)[commander.id] ?: CoopCounters())
                    .forEach { CoopDossierBits.addText(s, "osada-hero-line", it) }
            }
        }
    }

    /**
     * A life path drawn from the commander's portrait seed exactly as an officer's is: the same face
     * always gets the same history, and nothing is stored or sent.
     */
    private fun biography(
        model: CoopHud.Model,
        commander: CoopCommander,
    ): List<String> {
        val scenario = GameHolder.instance?.scenario
        val country =
            scenario
                ?.map
                ?.getPlayer(model.state.humanPlayerId)
                ?.country
                ?.takeIf { it >= 0 }
        val facts =
            HeroLifePath.generate(
                HeroLifePath.Context(
                    seed = commander.portrait.seed,
                    country = country,
                    serviceYear = scenario?.date?.getFullYear(),
                    unitClass = 0,
                    rankId = RANK_ID,
                    emergenceEventId = "",
                ),
            )
        return HeroBiographyNarrator.narrate(facts, RANK_ID, commander.portrait.seed)
    }
}

/** Small DOM builders and wording shared by the roster and the dossier (textContent only). */
internal object CoopDossierBits {
    fun statusOf(
        model: CoopHud.Model,
        commander: CoopCommander,
    ): String =
        when {
            commander.id in model.state.excused -> I18n.t("multiplayer.coop.hud.excused")
            model.connected[commander.participantId] == false -> I18n.t("multiplayer.coop.hud.away")
            commander.id in model.state.done -> I18n.t("multiplayer.coop.hud.done")
            else -> I18n.t("multiplayer.coop.hud.planning")
        }

    fun closeButton(
        header: HTMLElement,
        onClose: () -> Unit,
    ) {
        val close = addTag(header, "span")
        close.className = "osada-ico osada-ico--close osada-hero-close"
        close.title = I18n.t("common.close.label")
        close.asButton(ariaLabel = I18n.t("common.close.label")) { onClose() }
    }

    fun section(
        parent: HTMLElement,
        title: String,
        build: (HTMLElement) -> Unit,
    ) {
        val sec = addTag(parent, "div")
        sec.className = "osada-hero-section"
        addText(sec, "osada-hero-section-title", title)
        build(sec)
    }

    fun addText(
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
