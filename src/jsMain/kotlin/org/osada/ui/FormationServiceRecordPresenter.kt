@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.ui

import kotlinx.browser.document
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroEventDisplay
import org.osada.i18n.I18n
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
        child(
            titleWrap,
            "div",
            "osada-service-record__eyebrow",
            I18n.t("formation.service_record.formation", mapOf("id" to formation.id.value)),
        )
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
        summary(summaryGrid, I18n.t("formation.service_record.recognition"), formation.recognition.toString())
        summary(summaryGrid, I18n.t("formation.service_record.experience"), unit.experience.toString())
        summary(summaryGrid, I18n.t("formation.service_record.strength"), unit.strength.toString())
        summary(
            summaryGrid,
            I18n.t("formation.service_record.commander"),
            dossier?.let { "${it.rank} ${it.name}" } ?: I18n.t("common.none"),
        )
        // Nothing writes attachmentIds yet (DEFERRED.md §1.4), so "Attachments: 0" would render
        // forever and read as broken rather than "the feature doesn't exist here yet". Hide the
        // row until there is something to report.
        if (formation.attachmentIds.isNotEmpty()) {
            summary(
                summaryGrid,
                I18n.t("formation.service_record.attachments"),
                formation.attachmentIds.size.toString(),
            )
        }
        summary(summaryGrid, I18n.t("formation.service_record.battle_honours"), formation.battleHonors.size.toString())

        val history = child(panel, "div", "osada-service-record__history")
        child(history, "h3", "osada-service-record__section-title", I18n.t("formation.service_record.chronology"))
        if (formation.history.isEmpty()) {
            child(history, "div", "osada-service-record__empty", I18n.t("formation.service_record.empty"))
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
            "equipment_changed" -> I18n.t("hero.event.equipment_changed")
            "objective_captured" -> I18n.t("hero.event.objective_captured")
            "flag_captured" -> I18n.t("hero.event.flag_captured")
            "scenario_completed" -> I18n.t("hero.event.scenario_completed")
            "commander_departed" -> I18n.t("hero.event.commander_departed")
            "commander_transferred" -> I18n.t("hero.event.commander_transferred")
            else -> HeroEventDisplay.title(eventId)
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
