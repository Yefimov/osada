package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroId
import org.osada.hero.LeaderDossierView
import org.osada.model.GameUnit
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

    fun openForUnit(unit: GameUnit?) = HeroCampaign.dossier(unit)?.let(::show)

    fun openForHero(heroId: HeroId) = HeroCampaign.dossier(heroId)?.let(::show)

    fun close() = delTag(byId(BOX_ID))

    private fun show(view: LeaderDossierView) {
        close()
        val mainBody = byId("mainbody") ?: return
        val box = addTag(mainBody, "div")
        box.id = BOX_ID
        box.className = "osada-dossier osada-hero-dossier"

        buildHeader(box, view)
        val tabBar = addTag(box, "div")
        tabBar.className = "osada-hero-tabs"
        val body = addTag(box, "div")
        body.className = "osada-hero-tabbody"

        val tabs =
            listOf(
                "Overview" to { p: HTMLElement -> overview(p, view) },
                "Development" to { p: HTMLElement -> development(p, view) },
                "Service Record" to { p: HTMLElement -> serviceRecord(p, view) },
                "Medals" to { p: HTMLElement -> medals(p, view) },
                "Formations" to { p: HTMLElement -> formations(p, view) },
            )
        val buttons = tabs.map { (name, _) -> tabButton(tabBar, name) }

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
    ) {
        val header = addTag(box, "div")
        header.className = "osada-hero-header"
        val portrait = addTag(header, "div")
        portrait.className = "osada-hero-portrait"
        portrait.textContent =
            view.name
                .split(' ')
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .take(2)
        // The monogram above is the placeholder; the layered SVG portrait (§15) loads over it.
        PortraitRenderer.render(portrait, view.portrait, view.portraitSeed, gray = view.inMemoriam)
        val id = addTag(header, "div")
        id.className = "osada-hero-id"
        addText(id, "osada-hero-name", "${view.rank} ${view.name}")
        val sub =
            addText(
                id,
                "osada-hero-sub",
                listOfNotNull(
                    "Potential: ${view.potential}",
                    "Renown: ${view.renown}",
                    view.nickname,
                ).joinToString(" · "),
            )
        sub.title =
            "Potential governs career growth. Renown is public reputation; Unknown means newly appointed, not missing."
        addText(id, "osada-hero-status", "Status: ${view.status}").title =
            "Current availability and casualty status"
        if (view.inMemoriam) {
            box.classList.add("osada-hero-memoriam")
            addText(id, "osada-hero-memoriam-tag", "✝ In Memoriam")
        }
        val locate = addTag(header, "button")
        locate.className = "osada-hero-locate osada-hero-locate--header"
        locate.textContent = "Locate unit"
        locate.title = "Select and centre this commander's deployed formation"
        locate.onclick = { e: MouseEvent ->
            e.stopPropagation()
            locateHero(HeroId(view.heroId))
        }
        val close = addTag(header, "span")
        close.className = "osada-ico osada-ico--close osada-hero-close"
        close.title = "Close"
        close.onclick = { _: MouseEvent -> close() }
    }

    private fun overview(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        view.background?.let { (title, desc) -> section(parent, "Background") { s -> keyValue(s, title, desc) } }
        section(parent, "Traits & Effects") { s ->
            if (view.traits.isEmpty()) empty(s, "No learned traits yet.")
            view.traits.forEach { t ->
                val row = addTag(s, "div")
                row.className = "osada-hero-trait"
                addText(row, "osada-hero-trait-title", "${t.title}  (${t.source})")
                addText(row, "osada-hero-trait-effect", t.effect)
                addText(row, "osada-hero-trait-cond", "Applies: ${t.activation}")
            }
        }
    }

    private fun development(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        section(parent, "Command Profile") { s ->
            empty(
                s,
                "Career record only — these values do not modify combat. " +
                    "Gameplay effects are listed under Traits & Effects.",
            )
            view.attributes.forEach { (label, value) -> keyValue(s, label, value.toString()) }
        }
        section(parent, "Leader Experience") { s -> keyValue(s, "Experience", view.leaderExperience.toString()) }
        section(parent, "Specialization Evidence") { s ->
            if (view.evidence.isEmpty()) empty(s, "No specialization evidence recorded yet.")
            view.evidence.forEach { (title, value) -> keyValue(s, title, value.toString()) }
        }
    }

    private fun serviceRecord(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        if (view.inMemoriam) {
            section(parent, "In Memoriam") { s ->
                empty(s, "Fallen in the service of the campaign — remembered in the roster and formation history.")
            }
        }
        section(parent, "Condition") { s ->
            if (view.injuries.isEmpty()) empty(s, "No recorded wounds.")
            view.injuries.forEach { line -> addText(s, "osada-hero-line", line) }
        }
        section(parent, "Service Record") { s ->
            if (view.serviceRecord.isEmpty()) empty(s, "No service events recorded yet.")
            view.serviceRecord.forEach { line -> addText(s, "osada-hero-line", line) }
        }
    }

    private fun medals(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        section(parent, "Medals") { s ->
            if (view.medals.isEmpty()) empty(s, "No medals awarded yet.")
            view.medals.forEach { (title, scenario) -> keyValue(s, title, "scenario $scenario") }
        }
    }

    private fun formations(
        parent: HTMLElement,
        view: LeaderDossierView,
    ) {
        val formation = view.formation
        if (formation == null) {
            section(parent, "Formations") { s -> empty(s, "Not assigned to a formation.") }
            return
        }
        section(parent, formation.name) { s ->
            keyValue(s, "Recognition", formation.recognitionStatus)
            formation.unitExperience?.let { keyValue(s, "Unit experience", it.toString()) }
            if (formation.battleHonors.isNotEmpty()) {
                keyValue(s, "Battle honors", formation.battleHonors.joinToString(", "))
            }
            if (formation.attachments.isNotEmpty()) {
                keyValue(s, "Attachments", formation.attachments.joinToString(", "))
            }
        }
        if (formation.history.isNotEmpty()) {
            section(parent, "History") { s -> formation.history.forEach { addText(s, "osada-hero-line", it) } }
        }
    }

    // ---- small DOM builders (textContent only, so custom names cannot inject markup) ----

    private fun tabButton(
        bar: HTMLElement,
        name: String,
    ): HTMLElement {
        val b = addTag(bar, "div")
        b.className = "osada-hero-tab"
        b.textContent = name
        b.title =
            when (name) {
                "Overview" -> "Biography, traits and their gameplay effects"
                "Development" -> "Career-only command profile, experience and specialization evidence"
                "Service Record" -> "Dated battlefield achievements and casualty history"
                "Medals" -> "Decorations earned by this commander"
                else -> "Formation assignment, recognition, attachments and unit history"
            }
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
