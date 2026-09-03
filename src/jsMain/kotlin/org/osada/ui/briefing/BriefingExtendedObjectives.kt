package org.osada.ui.briefing

import org.osada.i18n.I18n
import org.osada.scenario.ExtendedObjectiveKind
import org.osada.scenario.ExtendedObjectiveProgress
import org.w3c.dom.HTMLElement

/** Generated operational orders for engine-authored victory and defeat conditions. */
internal fun addExtendedObjectiveSection(
    parent: HTMLElement,
    conditions: List<ExtendedObjectiveProgress>,
) {
    addListSection(
        parent,
        I18n.t("briefing.objective.extended.title"),
        extendedObjectiveBriefingLines(conditions),
        primary = true,
    )
}

internal fun extendedObjectiveBriefingLines(conditions: List<ExtendedObjectiveProgress>): List<String> =
    conditions.map(::describeExtendedObjective)

private fun describeExtendedObjective(condition: ExtendedObjectiveProgress): String =
    when (condition.kind) {
        ExtendedObjectiveKind.RETREAT ->
            I18n.plural(
                "briefing.objective.extended.retreat",
                condition.required,
                mapOf("count" to condition.required),
            )

        ExtendedObjectiveKind.KILL ->
            I18n.plural(
                "briefing.objective.extended.kill",
                condition.required,
                mapOf("count" to condition.required),
            )

        ExtendedObjectiveKind.MUST_SURVIVE ->
            I18n.plural(
                "briefing.objective.extended.must_survive",
                condition.required,
                mapOf("count" to condition.required),
            )
    }
