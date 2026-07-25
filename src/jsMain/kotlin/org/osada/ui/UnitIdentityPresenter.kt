@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroEventDisplay
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getUnits
import org.osada.rules.UnitCapabilities
import org.osada.unitClassNames
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
                val label = "Led by ${dossier.rank} ${dossier.name}"
                leader.textContent = label
                leader.classList.add("uc-commander-line--hero")
                leader.title = "$label\nClick to open the commander dossier."
                leader.setAttribute("aria-label", "$label. Open commander dossier")
                leader.onclick = { event: MouseEvent ->
                    event.stopPropagation()
                    LeaderDossierPresenter.openForUnit(unit)
                }
                enableKeyboardActivation(leader)
            }
            unit.leader >= 0 -> {
                val descriptions = Leaders.getUnitLeaderDescriptions(unit)
                val trait = descriptions.firstOrNull()?.first ?: "authored commander"
                val label = "Legacy commander: $trait"
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
                leader.textContent = "Temporary auxiliary formation"
                leader.classList.add("uc-commander-line--disabled")
                leader.title = "This formation is explicitly temporary and does not develop a persistent commander."
                disableInteraction(leader)
            }
            unit.formationId == null -> {
                leader.textContent = "No persistent commander"
                leader.classList.add("uc-commander-line--disabled")
                leader.title = "Scenario-only formation: commander development is unavailable."
                disableInteraction(leader)
            }
            else -> {
                val progress = HeroCampaign.recognitionProgress(unit)
                val label = "Officer candidate developing"
                leader.textContent = label
                leader.classList.add("uc-commander-line--candidate")
                leader.title =
                    if (progress == null) {
                        "No commander assigned. This formation has no recognition record yet."
                    } else {
                        val chanceLine =
                            when {
                                progress.recognition < progress.target ->
                                    "Officer checks unlock at ${progress.target} recognition"
                                progress.drought >= progress.guaranteedAfterFailures ->
                                    "Next notable action: officer guaranteed"
                                else -> "Next notable action: ${progress.chancePercent}% officer chance"
                            }
                        "No commander assigned\nRecognition: ${progress.recognition}\n$chanceLine\n" +
                            "Drought protection: ${progress.drought}/${progress.guaranteedAfterFailures}"
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
            val className = unitClassNames.getOrNull(data.uclass) ?: "Unit"
            val service =
                when {
                    unit.isTemporaryBorrowed || unit.nodossier -> "AUXILIARY"
                    unit.formationId != null -> "CORE"
                    else -> "SCENARIO"
                }
            textContent = "$className · $service"
            title = "Unit class and campaign-persistence status"
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
                if (unit.hits > 0) add("SUPPRESSED ${unit.hits}")
                if (unit.isSurprised) add("SURPRISED")
                if (unit.isMounted) add("MOUNTED")
                if (!unit.isDeployed) add("RESERVE")
            }
        byId("osadaUcTempState")?.apply {
            style.display = if (states.isEmpty()) "none" else "inline-flex"
            textContent = states.joinToString(" · ")
            title =
                buildList {
                    add("Current temporary battlefield state.")
                    if (unit.hits > 0) {
                        add(
                            "SUPPRESSED ${unit.hits}: each suppression point currently reduces defence by 2 " +
                                "in combat for affected units. Artillery, fortifications and most naval classes " +
                                "ignore this defence penalty.",
                        )
                    }
                    if (unit.isSurprised) {
                        add(
                            "SURPRISED: the unit was caught unprepared and may suffer combat penalties.",
                        )
                    }
                    if (unit.isMounted) add("MOUNTED: the formation is currently using its organic transport.")
                    if (!unit.isDeployed) add("RESERVE: the formation is waiting to be deployed.")
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
        val commander = HeroCampaign.dossier(unit)?.let { "${it.rank} ${it.name}" } ?: "None"
        val honors = formation.battleHonors.takeIf { it.isNotEmpty() }?.joinToString() ?: "None"
        listOf(
            "Recognition" to formation.recognition.toString(),
            "Scenarios recorded" to scenarios.toString(),
            "Enemy formations destroyed" to victories.toString(),
            "Objectives captured" to objectives.toString(),
            "Current commander" to commander,
            "Battle honours" to honors,
        ).forEach { (key, value) ->
            val row = addTag(detail, "div")
            row.className = "osada-formation-detail__summary"
            row.textContent = "$key: $value"
        }

        val button = addTag(detail, "button")
        button.className = "osada-service-record-button"
        button.textContent = "SERVICE RECORD"
        button.title = "Open the complete chronological formation history."
        button.setAttribute("aria-label", "Open service record for ${formation.displayName}")
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
