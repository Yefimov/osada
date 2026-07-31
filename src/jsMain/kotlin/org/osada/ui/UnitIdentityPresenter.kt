@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroEventDisplay
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getUnits
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

        val commandLine =
            byId("uc-commandline") ?: addTag(main, "div").also {
                it.id = "uc-commandline"
                main.insertBefore(it, bars)
            }
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

        // Name/rename alone own the first line; identity must not compete with tactical badges.
        byId("ucRename")?.let { nameLine.appendChild(it) }
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

    private fun presentCommander(unit: GameUnit) {
        val leader = byId("uLeader") ?: return
        leader.className = "uc-commander-line"
        leader.setAttribute("role", "button")
        leader.setAttribute("tabindex", "0")
        leader.removeAttribute("aria-disabled")
        leader.onclick = null
        leader.onkeydown = null

        val dossier = HeroCampaign.dossier(unit)
        when {
            dossier != null -> {
                val label =
                    I18n.t(
                        "unit_info.leader.led_by",
                        mapOf("rank" to dossier.rank, "name" to dossier.name),
                    )
                leader.textContent = label
                leader.classList.add("uc-commander-line--hero")
                leader.title = "$label\n${I18n.t("unit_info.leader.open_dossier.help")}"
                leader.setAttribute(
                    "aria-label",
                    I18n.t("unit_info.leader.open_dossier.aria", mapOf("label" to label)),
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
                leader.textContent = label
                leader.title = descriptions.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { label }
                leader.setAttribute("aria-label", label)
                leader.onclick = { event: MouseEvent ->
                    event.stopPropagation()
                    byId("unit-info")?.classList?.add("uc--expanded")
                }
                enableKeyboardActivation(leader)
            }

            unit.isTemporaryBorrowed || unit.nodossier -> {
                leader.textContent = I18n.t("unit_info.leader.temporary.label")
                leader.classList.add("uc-commander-line--disabled")
                leader.title = I18n.t("unit_info.leader.temporary.help")
                disableInteraction(leader)
            }

            unit.formationId == null -> {
                leader.textContent = I18n.t("unit_info.leader.scenario_only.label")
                leader.classList.add("uc-commander-line--disabled")
                leader.title = I18n.t("unit_info.leader.scenario_only.help")
                disableInteraction(leader)
            }

            else -> {
                val progress = HeroCampaign.recognitionProgress(unit)
                val label = I18n.t("unit_info.leader.candidate.label")
                leader.textContent = label
                leader.classList.add("uc-commander-line--candidate")
                leader.title =
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
                leader.setAttribute("aria-label", "$label. ${leader.title}")
                leader.onclick = { event: MouseEvent ->
                    event.stopPropagation()
                    byId("unit-info")?.classList?.add("uc--expanded")
                }
                enableKeyboardActivation(leader)
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
        element.setAttribute("aria-disabled", "true")
        element.setAttribute("aria-label", element.title)
    }

    // TODO(detekt): CyclomaticComplexMethod (22) — tied with handleLeftClickWithUnit as the worst
    // offender in the codebase; assembles every unit-status chip. Deliberately deferred rather
    // than rushed.
    @Suppress("CyclomaticComplexMethod")
    private fun presentStatus(
        ui: UI,
        unit: GameUnit,
    ) {
        val data = unit.unitData(true)
        byId("osadaUcClass")?.apply {
            val className = GameText.unitClass(data.uclass)
            val serviceKey =
                when {
                    unit.isTemporaryBorrowed || unit.nodossier -> "auxiliary"
                    unit.formationId != null -> "core"
                    else -> "scenario"
                }
            textContent =
                I18n.t(
                    "unit_info.identity.class_service",
                    mapOf(
                        "class" to className,
                        "service" to I18n.t("unit_info.identity.service.$serviceKey"),
                    ),
                )
            title = I18n.t("unit_info.identity.class_service.help")
        }

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
        val honors = formation.battleHonors.takeIf { it.isNotEmpty() }?.joinToString() ?: I18n.t("common.none")
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
