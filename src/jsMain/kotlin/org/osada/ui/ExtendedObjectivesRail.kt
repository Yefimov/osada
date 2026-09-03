package org.osada.ui

import org.osada.i18n.I18n
import org.osada.scenario.ExtendedObjectiveKind
import org.osada.scenario.ExtendedObjectiveProgress
import org.osada.scenario.ObjectiveReport
import org.w3c.dom.HTMLElement

/** Renders Open General's non-hex scenario conditions in the ordinary objectives rail. */
internal object ExtendedObjectivesRail {
    fun render(
        container: HTMLElement,
        report: ObjectiveReport,
    ) {
        if (report.extended.isEmpty()) return
        val heading = addTag(container, "div")
        heading.className = "osada-obj-section"
        heading.textContent = I18n.t("hud.objective.extended.title")
        heading.title = I18n.t("hud.objective.extended.help")
        report.extended.forEach { condition -> conditionRow(container, condition) }
    }

    private fun conditionRow(
        container: HTMLElement,
        condition: ExtendedObjectiveProgress,
    ) {
        val row = addTag(container, "div")
        row.className =
            "osada-obj-end" +
            when {
                condition.failed -> " osada-obj-end--missed"
                condition.satisfied -> " osada-obj-end--ok"
                else -> ""
            }
        val key = condition.kind.name.lowercase()
        val name = addTag(row, "span")
        name.className = "osada-obj-end__name"
        name.textContent = I18n.t("hud.objective.extended.$key")
        val state = addTag(row, "span")
        state.className = "osada-obj-end__state"
        state.textContent = progressText(condition)
        row.title = I18n.t("hud.objective.extended.$key.help")
    }

    private fun progressText(condition: ExtendedObjectiveProgress): String {
        val key =
            when {
                condition.failed -> "failed"
                condition.kind == ExtendedObjectiveKind.MUST_SURVIVE -> "surviving"
                condition.satisfied -> "complete"
                else -> "progress"
            }
        return I18n.t(
            "hud.objective.extended.$key",
            mapOf("current" to condition.current, "required" to condition.required),
        )
    }
}
