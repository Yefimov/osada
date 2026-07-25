@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import kotlinx.browser.document
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroEventDisplay
import org.osada.model.GameUnit
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/** Dedicated chronological history window for one persistent formation. */
internal object FormationServiceRecordPresenter {
    private const val ROOT_ID = "osadaServiceRecord"

    fun open(unit: GameUnit) {
        close()
        val formation = HeroCampaign.formationFor(unit) ?: return
        val overlay = document.createElement("div") as HTMLElement
        overlay.id = ROOT_ID
        overlay.className = "osada-service-record-overlay"
        overlay.setAttribute("role", "presentation")
        overlay.onclick = { close() }

        val panel = child(overlay, "section", "osada-service-record")
        panel.setAttribute("role", "dialog")
        panel.setAttribute("aria-modal", "true")
        panel.setAttribute("aria-label", "Service record for ${formation.displayName}")
        panel.onclick = { event: MouseEvent -> event.stopPropagation() }

        val header = child(panel, "header", "osada-service-record__header")
        val titleWrap = child(header, "div", "osada-service-record__titles")
        child(titleWrap, "div", "osada-service-record__eyebrow", "Formation ${formation.id.value}")
        child(titleWrap, "h2", "osada-service-record__title", formation.displayName)
        child(titleWrap, "div", "osada-service-record__sub", unit.unitData(true).name)
        val closeButton = child(header, "button", "osada-service-record__close", "×")
        closeButton.setAttribute("aria-label", "Close service record")
        closeButton.onclick = { event: MouseEvent ->
            event.stopPropagation()
            close()
        }

        val summaryGrid = child(panel, "div", "osada-service-record__summary")
        val dossier = HeroCampaign.dossier(unit)
        summary(summaryGrid, "Recognition", formation.recognition.toString())
        summary(summaryGrid, "Experience", unit.experience.toString())
        summary(summaryGrid, "Strength", unit.strength.toString())
        summary(summaryGrid, "Commander", dossier?.let { "${it.rank} ${it.name}" } ?: "None")
        summary(summaryGrid, "Attachments", formation.attachmentIds.size.toString())
        summary(summaryGrid, "Battle honours", formation.battleHonors.size.toString())

        val history = child(panel, "div", "osada-service-record__history")
        child(history, "h3", "osada-service-record__section-title", "Chronology")
        if (formation.history.isEmpty()) {
            child(history, "div", "osada-service-record__empty", "No notable events have been recorded yet.")
        } else {
            formation.history.forEach { event ->
                val row = child(history, "article", "osada-service-record__event")
                child(row, "div", "osada-service-record__event-title", eventTitle(event.eventId))
                child(
                    row,
                    "div",
                    "osada-service-record__event-context",
                    HeroEventDisplay.context(event.scenarioId, event.turn, event.date, event.location).trim(),
                )
            }
        }

        document.body?.appendChild(overlay)
    }

    fun eventTitle(eventId: String): String =
        when (eventId) {
            "equipment_changed" -> "Formation re-equipped"
            "objective_captured" -> "Objective captured"
            "flag_captured" -> "Flag captured"
            "scenario_completed" -> "Scenario completed"
            else ->
                if (eventId.startsWith("commander_")) {
                    "Commander ${eventId.removePrefix("commander_").replace('_', ' ')}"
                } else {
                    HeroEventDisplay.title(eventId)
                }
        }

    fun close() {
        byId(ROOT_ID)?.let { delTag(it) }
    }

    private fun summary(
        parent: HTMLElement,
        key: String,
        value: String,
    ) {
        val row = child(parent, "div", "osada-service-record__kv")
        child(row, "span", "osada-service-record__key", key)
        child(row, "strong", "osada-service-record__value", value)
    }

    private fun child(
        parent: HTMLElement,
        tag: String,
        className: String,
        text: String? = null,
    ): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        element.className = className
        element.textContent = text
        parent.appendChild(element)
        return element
    }
}
