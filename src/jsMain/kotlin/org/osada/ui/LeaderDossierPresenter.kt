package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroId
import org.osada.hero.LeaderDossierView
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.ui.LeaderDossierPresenter.openForHero
import org.osada.ui.LeaderDossierPresenter.openForUnit
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The leader dossier (design brief §14.4) — an overlay with Overview / Development / Service Record /
 * Medals / Formations tabs, built from the pure [LeaderDossierView]. Opened from the unit-info leader
 * row ([openForUnit]) or the campaign roster ([openForHero]).
 *
 * Dynamic values are written with `textContent`, never `innerHTML`: a formation name can be a
 * player-typed unit name, so it must not be interpreted as markup. This mirrors the safety posture of
 * the rest of the OSADA panels while staying localization-ready — all label text comes from
 * [org.osada.hero.HeroDisplay].
 */
@Suppress("TooManyFunctions")
internal object LeaderDossierPresenter {
    private const val BOX_ID = "uiLeaderDossier"

    fun openForUnit(unit: GameUnit?) = HeroCampaign.dossier(unit)?.let { show(it) }

    fun openForHero(heroId: HeroId) = HeroCampaign.dossier(heroId)?.let { show(it) }

    /**
     * Source-neutral entry point (`docs/design/hero-desk-and-profile-archive.md` §6): opens the
     * SAME presentation for a view assembled from an archived roster as for a live one. Live and
     * archived records reach this through one assembler, so parity is structural rather than
     * maintained by hand.
     *
     * [parent] lets the main-menu Hero Desk mount it over `body` instead of the in-game `#mainbody`,
     * which is not on screen there. [allowLocate] is false for an archived career: there is no map
     * to locate an officer on, and a button that quietly does nothing is worse than no button.
     */
    fun openForView(
        view: LeaderDossierView,
        parent: HTMLElement?,
        allowLocate: Boolean = true,
        onClose: () -> Unit = {},
    ) = show(view, parent, allowLocate, onClose)

    fun close() = delTag(byId(BOX_ID))

    fun isOpen(): Boolean = byId(BOX_ID) != null

    private fun show(
        view: LeaderDossierView,
        parent: HTMLElement? = null,
        allowLocate: Boolean = true,
        onClose: () -> Unit = {},
    ) {
        close()
        val mainBody = parent ?: byId("mainbody") ?: return
        val box = addTag(mainBody, "div")
        box.id = BOX_ID
        box.className = "osada-dossier osada-hero-dossier"

        buildHeader(box, view, allowLocate, onClose)
        val tabBar = addTag(box, "div")
        tabBar.className = "osada-hero-tabs"
        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"

        val tabs =
            listOf(
                "overview" to { p: HTMLElement -> overview(p, view) },
                "development" to { p: HTMLElement -> development(p, view) },
                "service" to { p: HTMLElement -> serviceRecord(p, view) },
                "medals" to { p: HTMLElement -> medals(p, view) },
                "formations" to { p: HTMLElement -> formations(p, view) },
            )
        val buttons = tabs.map { (key, _) -> tabButton(tabBar, key) }

        fun select(index: Int) {
            clearTag(body)
            buttons.forEachIndexed { i, b -> toggleButton(b, i == index) }
            tabs[index].second(body)
        }
        buttons.forEachIndexed { i, b -> b.onclick = { _: MouseEvent -> select(i) } }
        select(0)
    }

    @Suppress("MaxLineLength")
    private fun buildHeader(
        box: HTMLElement,
        view: LeaderDossierView,
        allowLocate: Boolean,
        onClose: () -> Unit,
    ) {
        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val portrait = addTag(header, "div")
        portrait.className = "osada-hero-portrait ${view.renownClass}".trim()
        portrait.textContent =
            view.name
                .split(' ')
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .take(2)
        // The monogram above is the placeholder; the portrait (§15) — painted for an authored hero,
        // otherwise the layered SVG stack — loads over it.
        PortraitRenderer.render(
            portrait,
            view.portrait,
            view.portraitSeed,
            gray = view.inMemoriam,
            artPath = view.portraitArt,
        )
        val id = addTag(header, "div")
        id.className = "osada-hero-id"
        addText(id, "osada-hero-name", "${view.rank} ${view.name}")
        val sub =
            addText(
                id,
                "osada-hero-sub",
                listOfNotNull(
                    I18n.t("hero.dossier.potential", mapOf("value" to view.potential)),
                    I18n.t("hero.dossier.renown", mapOf("value" to view.renown)),
                    view.nickname,
                ).joinToString(" · "),
            )
        sub.title =
            I18n.t("hero.dossier.potential_renown.help")
        addText(
            id,
            "osada-hero-status",
            I18n.t("hero.dossier.status", mapOf("value" to view.status)),
        ).title = I18n.t("hero.dossier.status.help")
        if (view.inMemoriam) {
            box.classList.add("osada-hero-memoriam")
            addText(id, "osada-hero-memoriam-tag", I18n.t("hero.dossier.in_memoriam"))
        }
        // §26: no hidden modifiers. While this is set, every trait listed below is inactive, so it
        // belongs next to the status rather than buried in the service record.
        view.settlingNote?.let { addText(id, "osada-hero-settling", it) }
        if (allowLocate) buildLocateButton(header, view)
        val close = addTag(header, "span")
        close.className = "osada-ico osada-ico--close osada-hero-close"
        close.title = I18n.t("common.close.label")
        close.asButton(ariaLabel = I18n.t("common.close.label")) {
            close()
            onClose()
        }
    }

    /** Absent for an archived career: there is no loaded map to locate that officer on. */
    private fun buildLocateButton(
        header: HTMLElement,
        view: LeaderDossierView,
    ) {
        val locate = addTag(header, "button")
        locate.className = "osada-hero-locate osada-hero-locate--header"
        locate.textContent = I18n.t("hero.dossier.locate.label")
        locate.title = I18n.t("hero.dossier.locate.help")
        locate.onclick = { e: MouseEvent ->
            e.stopPropagation()
            locateHero(HeroId(view.heroId))
        }
    }

    private fun overview(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        view.background?.let { (title, desc) ->
            section(parent, I18n.t("hero.dossier.section.background")) { s -> keyValue(s, title, desc) }
        }
        section(parent, I18n.t("hero.dossier.section.traits")) { s ->
            if (view.traits.isEmpty()) empty(s, I18n.t("hero.dossier.traits.empty"))
            view.traits.forEach { t ->
                val row = addTag(s, "div")
                row.className = "osada-hero-trait"
                addText(row, "osada-hero-trait-title", "${t.title}  (${t.source})")
                addText(row, "osada-hero-trait-effect", t.effect)
                addText(
                    row,
                    "osada-hero-trait-cond",
                    I18n.t("hero.dossier.trait.applies", mapOf("activation" to t.activation)),
                )
            }
        }
    }

    private fun development(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        section(parent, I18n.t("hero.dossier.section.command_profile")) { s ->
            empty(
                s,
                I18n.t("hero.dossier.command_profile.help"),
            )
            view.attributes.forEach { (label, value) -> keyValue(s, label, value.toString()) }
        }
        section(parent, I18n.t("hero.dossier.section.experience")) { s ->
            keyValue(s, I18n.t("hero.dossier.experience.label"), view.leaderExperience.toString())
        }
        section(parent, I18n.t("hero.dossier.section.evidence")) { s ->
            if (view.evidence.isEmpty()) empty(s, I18n.t("hero.dossier.evidence.empty"))
            view.evidence.forEach { (title, value) -> keyValue(s, title, value.toString()) }
        }
    }

    private fun serviceRecord(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        if (view.inMemoriam) {
            section(parent, I18n.t("hero.dossier.section.in_memoriam")) { s ->
                empty(s, I18n.t("hero.dossier.in_memoriam.help"))
            }
        }
        section(parent, I18n.t("hero.dossier.section.condition")) { s ->
            if (view.injuries.isEmpty()) empty(s, I18n.t("hero.dossier.injuries.empty"))
            view.injuries.forEach { line -> addText(s, "osada-hero-line", line) }
        }
        section(parent, I18n.t("hero.dossier.section.service")) { s ->
            if (view.serviceRecord.isEmpty()) empty(s, I18n.t("hero.dossier.service.empty"))
            view.serviceRecord.forEach { line -> addText(s, "osada-hero-line", line) }
        }
    }

    private fun medals(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        section(parent, I18n.t("hero.dossier.section.medals")) { s ->
            if (view.medals.isEmpty()) empty(s, I18n.t("hero.dossier.medals.empty"))
            view.medals.forEach { (title, scenario) ->
                keyValue(s, title, I18n.t("hero.dossier.scenario", mapOf("scenario" to scenario)))
            }
        }
    }

    private fun formations(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        val formation = view.formation
        if (formation == null) {
            section(parent, I18n.t("hero.dossier.section.formations")) { s ->
                empty(s, I18n.t("hero.dossier.formation.unassigned"))
            }
            return
        }
        section(parent, formation.name) { s ->
            keyValue(s, I18n.t("hero.dossier.recognition.label"), formation.recognitionStatus)
            formation.unitExperience?.let {
                keyValue(s, I18n.t("hero.dossier.unit_experience.label"), it.toString())
            }
            if (formation.battleHonors.isNotEmpty()) {
                keyValue(s, I18n.t("hero.dossier.battle_honours.label"), formation.battleHonors.joinToString(", "))
            }
            if (formation.attachments.isNotEmpty()) {
                keyValue(s, I18n.t("hero.dossier.attachments.label"), formation.attachments.joinToString(", "))
            }
        }
        if (formation.history.isNotEmpty()) {
            section(parent, I18n.t("hero.dossier.section.history")) { s ->
                formation.history.forEach { addText(s, "osada-hero-line", it) }
            }
        }
    }

    // ---- small DOM builders (textContent only, so custom names cannot inject markup) ----

    private fun tabButton(
        bar: HTMLElement,
        key: String,
    ): HTMLElement {
        val b = addTag(bar, "div")
        b.className = "osada-hero-tab"
        b.textContent = I18n.t("hero.dossier.tab.$key.label")
        b.title = I18n.t("hero.dossier.tab.$key.help")
        return b
    }

    private fun section(
        parent: HTMLElement,
        title: String,
        build: (HTMLElement) -> Unit,
    ) {
        val sec = addTag(parent, "div")
        sec.className = "osada-hero-section"
        addText(sec, "osada-hero-section-title", title)
        build(sec)
    }

    private fun keyValue(
        parent: HTMLElement,
        key: String,
        value: String,
    ) {
        val row = addTag(parent, "div")
        row.className = "osada-hero-kv"
        addText(row, "osada-hero-kv-key", key)
        addText(row, "osada-hero-kv-val", value)
    }

    private fun empty(
        parent: HTMLElement,
        text: String,
    ) = addText(parent, "osada-hero-empty", text)

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
