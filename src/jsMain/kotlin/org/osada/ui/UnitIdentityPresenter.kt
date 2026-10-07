@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroEventDisplay
import org.osada.hero.HeroHonours
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getUnits
import org.osada.multiplayer.coop.CoopRuntime
import org.osada.multiplayer.ui.CoopHud
import org.osada.rules.UnitCapabilities
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/** Identity-first presentation layered over the existing UnitStatCard data population. */
internal object UnitIdentityPresenter {
    private const val RECENT_EVENTS = 3

    fun present(
        ui: UI,
        unit: GameUnit?,
    ) {
        UnitIdentityStyles.ensureInstalled()
        if (unit == null || byId("unit-info") == null || !isVisible("unit-info")) return
        ensureStructure()
        if (!unit.isTemporaryBorrowed && !unit.nodossier && unit.formationId != null) {
            HeroCampaign.synchronizeFormation(unit)
        }
        presentName(unit)
        presentCommander(unit)
        presentStatus(ui, unit)
        presentFormation(unit)
    }

    private fun ensureStructure() {
        val main = byId("uc-main") ?: return
        val bars = byId("uc-bars")
        val nameLine = byId("uc-nameline") ?: return

        // The commander line shares the NAME row rather than owning one of its own. Reported:
        // *"for T-26 TU Tank the #uc-commandline is not on the right side, but on the next line.
        // There is plenty of space."* It is also the cheapest ~18px of height available, and height
        // is what was clipping the FUEL bar out of the bottom zone on long-named units.
        val commandLine =
            byId("uc-commandline") ?: addTag(nameLine, "div").also { it.id = "uc-commandline" }
        nameLine.appendChild(commandLine)
        byId("uLeader")?.let { commandLine.appendChild(it) }

        val statusLine =
            byId("uc-statusline") ?: addTag(main, "div").also {
                it.id = "uc-statusline"
                main.insertBefore(it, bars)
            }
        ensureChip(statusLine, "osadaUcClass", "uc-chip")
        ensureChip(statusLine, "osadaUcSupport", "uc-chip uc-chip--support")
        ensureChip(statusLine, "osadaUcTempState", "uc-chip uc-chip--warning")
        listOf("osadaUcStars", "osadaUcEnt", "osadaUcMarkings", "osadaUcWeather", "uTransport", "uCarrier")
            .forEach { id -> byId(id)?.let { statusLine.appendChild(it) } }

        // Name, rename pencil, then the commander line filling the rest of the row. Tactical
        // badges still live on #uc-statusline below -- identity does not compete with them.
        byId("ucRename")?.let { nameLine.insertBefore(it, commandLine) }
    }

    private fun ensureChip(
        parent: HTMLElement,
        id: String,
        className: String,
    ): HTMLElement =
        (byId(id) ?: addTag(parent, "span")).also {
            it.id = id
            it.className = className
        }

    private fun presentName(unit: GameUnit) {
        val data = unit.unitData(true)
        val ordinal = if (unit.id >= 0) UIBuilder.unitIDToOrdinal(unit.id) else ""
        val equipmentName =
            listOf(ordinal, data.name)
                .filter(String::isNotBlank)
                .joinToString(" ")
        byId("uName")?.textContent = unit.customName ?: equipmentName
    }

    /**
     * The commander mark on the name row: a peaked-cap silhouette whose SHAPE carries the state and
     * whose text carries only what differs between units. It used to be a sentence per state
     * ("Heroic commander: Captain X", "No persistent hero") that competed with the unit name for the
     * one row both share. Filled means a commander is present, hollow means the slot is empty --
     * a hollow brass one can still fill, a hollow grey one cannot. Absence is deliberately quiet
     * (no strike-through): it is the normal state of every standalone-scenario unit, not an error.
     * The full sentence moves to the tooltip and the aria-label, so nothing is lost.
     *
     * Internal because [GameplayLocalization] re-runs it verbatim on a language switch rather than
     * keeping a second copy of the same five branches.
     */
    internal fun presentCommander(unit: GameUnit) {
        val leader = byId("uLeader") ?: return
        leader.className = "uc-commander-line"
        leader.setAttribute("role", "button")
        leader.setAttribute("tabindex", "0")
        leader.removeAttribute("aria-disabled")
        leader.onclick = null
        leader.onkeydown = null

        val dossier = HeroCampaign.dossier(unit)
        val eligibilityHelp = I18n.t("unit_info.leader.eligibility.help")
        when {
            // A cooperative match replaces the friendly leader layer: no hero, no legacy leader and no
            // candidate line -- the unit shows who commands it, in words.
            CoopRuntime.governs(unit) -> presentCoopCommander(leader, unit)

            dossier != null -> {
                val label =
                    I18n.t(
                        "unit_info.leader.led_by",
                        mapOf("rank" to dossier.rank, "name" to dossier.name),
                    )
                renderMark(
                    leader,
                    CommanderMark.HERO,
                    I18n.t("unit_info.leader.hero.short", mapOf("rank" to dossier.rank, "name" to dossier.name)),
                )
                leader.title = "$label\n${I18n.t("unit_info.leader.open_dossier.help")}\n$eligibilityHelp"
                leader.setAttribute(
                    "aria-label",
                    "${I18n.t("unit_info.leader.open_dossier.aria", mapOf("label" to label))}. $eligibilityHelp",
                )
                leader.onclick = { event: MouseEvent ->
                    event.stopPropagation()
                    LeaderDossierPresenter.openForUnit(unit)
                }
                enableKeyboardActivation(leader)
            }

            unit.leader >= 0 -> {
                val descriptions = Leaders.getUnitLeaderDescriptions(unit)
                val trait = descriptions.firstOrNull()?.first ?: I18n.t("unit_info.leader.authored_commander")
                val label = I18n.t("unit_info.leader.legacy", mapOf("trait" to trait))
                renderMark(leader, CommanderMark.LEGACY, trait)
                leader.title =
                    listOf(label)
                        .plus(descriptions.map { "${it.first}: ${it.second}" })
                        .plus(eligibilityHelp)
                        .joinToString("\n")
                leader.setAttribute("aria-label", "$label. $eligibilityHelp")
                leader.onclick = { event: MouseEvent ->
                    event.stopPropagation()
                    byId("unit-info")?.classList?.add("uc--expanded")
                }
                enableKeyboardActivation(leader)
            }

            unit.isTemporaryBorrowed || unit.nodossier -> {
                renderMark(leader, CommanderMark.NONE, null)
                val label = I18n.t("unit_info.leader.temporary.label")
                leader.title = "$label\n${I18n.t("unit_info.leader.temporary.help")}\n$eligibilityHelp"
                disableInteraction(leader)
            }

            // The hero system's scope, not the bare id: a stale id from an old save (an enemy or a
            // standalone purchase) is a scenario unit (`hero/CampaignHeroScope`).
            !HeroCampaign.isHeroEligible(unit) -> {
                renderMark(leader, CommanderMark.NONE, null)
                val label = I18n.t("unit_info.leader.scenario_only.label")
                leader.title = "$label\n${I18n.t("unit_info.leader.scenario_only.help")}\n$eligibilityHelp"
                disableInteraction(leader)
            }

            else -> {
                val progress = HeroCampaign.recognitionProgress(unit)
                val label = I18n.t("unit_info.leader.candidate.label")
                renderMark(leader, CommanderMark.CANDIDATE, null)
                val help =
                    if (progress == null) {
                        I18n.t("unit_info.leader.candidate.no_record")
                    } else {
                        val chanceLine =
                            when {
                                progress.recognition < progress.target ->
                                    I18n.t("unit_info.leader.checks_unlock", mapOf("target" to progress.target))

                                progress.drought >= progress.guaranteedAfterFailures ->
                                    I18n.t("unit_info.leader.guaranteed")

                                else ->
                                    I18n.t(
                                        "unit_info.leader.chance",
                                        mapOf("chance" to progress.chancePercent),
                                    )
                            }
                        I18n.t(
                            "unit_info.leader.candidate.help",
                            mapOf(
                                "recognition" to progress.recognition,
                                "chanceLine" to chanceLine,
                                "drought" to progress.drought,
                                "guaranteedAfter" to progress.guaranteedAfterFailures,
                            ),
                        )
                    }
                leader.title = "$label\n$help\n$eligibilityHelp"
                leader.setAttribute("aria-label", "$label. $help. $eligibilityHelp")
                leader.onclick = { event: MouseEvent ->
                    event.stopPropagation()
                    byId("unit-info")?.classList?.add("uc--expanded")
                }
                enableKeyboardActivation(leader)
            }
        }
    }

    private fun presentCoopCommander(
        leader: HTMLElement,
        unit: GameUnit,
    ) {
        val coop = CoopRuntime.state ?: return
        val owner = coop.commanderById(CoopRuntime.commanderIdFor(unit))
        if (owner == null) {
            renderMark(leader, CommanderMark.NONE, I18n.t("multiplayer.coop.unit.unassigned"))
            leader.title = I18n.t("multiplayer.coop.unit.unassigned.help")
        } else {
            val label =
                I18n.t(
                    "multiplayer.coop.unit.commanded_by",
                    mapOf(
                        "name" to owner.name,
                        "seat" to owner.seat + 1,
                    ),
                )
            renderMark(leader, CommanderMark.HERO, label)
            leader.title = label
        }
        leader.setAttribute("aria-label", leader.title)
        disableInteraction(leader)
        if (owner != null) {
            // The commander line opens that player's dossier, as a hero's line opens the hero's.
            leader.setAttribute("role", "button")
            leader.setAttribute("tabindex", "0")
            leader.removeAttribute("aria-disabled")
            leader.onclick = { event: MouseEvent ->
                event.stopPropagation()
                CoopHud.openCommander(owner.id)
            }
            enableKeyboardActivation(leader)
        }
    }

    private enum class CommanderMark(
        val modifier: String,
    ) {
        HERO("uc-commander-line--hero"),
        LEGACY("uc-commander-line--legacy"),
        CANDIDATE("uc-commander-line--candidate"),
        NONE("uc-commander-line--disabled"),
    }

    private fun renderMark(
        leader: HTMLElement,
        mark: CommanderMark,
        text: String?,
    ) {
        leader.classList.add(mark.modifier)
        leader.textContent = ""
        addTag(leader, "span").also {
            it.className = "uc-commander-icon"
            it.setAttribute("aria-hidden", "true")
        }
        if (text != null) {
            addTag(leader, "span").also {
                it.className = "uc-commander-text"
                it.textContent = text
            }
        }
    }

    private fun enableKeyboardActivation(element: HTMLElement) {
        element.onkeydown = { event ->
            val key = event.asDynamic().key as? String
            if (key == "Enter" || key == " ") {
                event.preventDefault()
                element.asDynamic().click()
            }
        }
    }

    private fun disableInteraction(element: HTMLElement) {
        element.onclick = null
        element.onkeydown = null
        element.removeAttribute("role")
        element.removeAttribute("tabindex")
        element.setAttribute("role", "img")
        element.setAttribute("aria-disabled", "true")
        element.setAttribute("aria-label", element.title.replace("\n", ". "))
    }

    // TODO(detekt): CyclomaticComplexMethod (22) — tied with handleLeftClickWithUnit as the worst
    // offender in the codebase; assembles every unit-status chip. Deliberately deferred rather
    // than rushed.
    @Suppress("CyclomaticComplexMethod")
    private fun presentStatus(
        ui: UI,
        unit: GameUnit,
    ) {
        byId("osadaUcClass")?.let { UnitServiceChip.refresh(it, unit) }

        val units =
            ui.game.scenario
                ?.map
                ?.getUnits()
                ?.toList()
                .orEmpty()
        // Text and tooltip are written by GameplayLocalization.refreshUnitSupport, which runs
        // immediately after this presenter (UnitInfoPanel.showUnitInfo) — only the visibility
        // decision belongs here. Writing the strings in both places is how the Combat Support note
        // on #osadaUcStars used to be silently overwritten and lost.
        val supportBars = UnitCapabilities.combatSupportBars(units, unit)
        byId("osadaUcSupport")?.style?.display = if (supportBars > 0) "inline-flex" else "none"

        val states =
            buildList {
                if (unit.hits > 0) add(I18n.t("unit_info.state.suppressed.label", mapOf("hits" to unit.hits)))
                if (unit.isSurprised) add(I18n.t("unit_info.state.surprised.label"))
                if (unit.isMounted) add(I18n.t("unit_info.state.mounted.label"))
                if (!unit.isDeployed) add(I18n.t("unit_info.state.reserve.label"))
            }
        byId("osadaUcTempState")?.apply {
            style.display = if (states.isEmpty()) "none" else "inline-flex"
            textContent = states.joinToString(" · ")
            title =
                buildList {
                    add(I18n.t("unit_info.state.help"))
                    if (unit.hits > 0) {
                        add(
                            I18n.t("unit_info.state.suppressed.help", mapOf("hits" to unit.hits)),
                        )
                    }
                    if (unit.isSurprised) {
                        add(I18n.t("unit_info.state.surprised.help"))
                    }
                    if (unit.isMounted) add(I18n.t("unit_info.state.mounted.help"))
                    if (!unit.isDeployed) add(I18n.t("unit_info.state.reserve.help"))
                }.joinToString("\n")
        }
    }

    private fun presentFormation(unit: GameUnit) {
        val formation = HeroCampaign.formationFor(unit) ?: return
        val container = byId("statsRowContainer") ?: return
        val detail =
            byId("osadaFormationDetail") ?: addTag(container, "div").also {
                it.id = "osadaFormationDetail"
                it.className = "osada-formation-detail"
            }
        clearTag(detail)

        val headline = addTag(detail, "div")
        headline.className = "osada-formation-detail__headline"
        headline.textContent = "${formation.displayName} · ${formation.id.value}"

        val scenarios =
            formation.history
                .map { it.scenarioId }
                .filter(String::isNotBlank)
                .distinct()
                .size
        val victories =
            formation.history.count {
                it.eventId.contains("destroy", true) || it.eventId.contains("kill", true)
            }
        val objectives =
            formation.history.count {
                it.eventId.contains("capture", true) || it.eventId.contains("objective", true)
            }
        val commander = HeroCampaign.dossier(unit)?.let { "${it.rank} ${it.name}" } ?: I18n.t("common.none")
        val honors =
            formation.battleHonors
                .takeIf { it.isNotEmpty() }
                ?.let { HeroHonours.display(it).joinToString() }
                ?: I18n.t("common.none")
        listOf(
            I18n.t("unit_info.formation.recognition.label") to formation.recognition.toString(),
            I18n.t("unit_info.formation.scenarios.label") to scenarios.toString(),
            I18n.t("unit_info.formation.destroyed.label") to victories.toString(),
            I18n.t("unit_info.formation.objectives.label") to objectives.toString(),
            I18n.t("unit_info.formation.commander.label") to commander,
            I18n.t("unit_info.formation.honours.label") to honors,
        ).forEach { (key, value) ->
            val row = addTag(detail, "div")
            row.className = "osada-formation-detail__summary"
            row.textContent = I18n.t("unit_info.formation.summary", mapOf("label" to key, "value" to value))
        }

        val button = addTag(detail, "button")
        button.className = "osada-service-record-button"
        button.textContent = I18n.t("unit_info.formation.service_record.label")
        button.title = I18n.t("unit_info.formation.service_record.help")
        button.setAttribute(
            "aria-label",
            I18n.t("unit_info.formation.service_record.aria", mapOf("formation" to formation.displayName)),
        )
        button.onclick = { event: MouseEvent ->
            event.stopPropagation()
            FormationServiceRecordPresenter.open(unit)
        }

        formation.history.takeLast(RECENT_EVENTS).forEach { event ->
            val row = addTag(detail, "div")
            row.className = "osada-formation-detail__event"
            row.textContent =
                FormationServiceRecordPresenter.eventTitle(event.eventId) +
                HeroEventDisplay.context(event.scenarioId, event.turn, event.date, event.location)
        }
    }
}
