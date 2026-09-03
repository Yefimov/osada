package org.osada.ui

import org.osada.i18n.I18n
import org.osada.scenario.ExtendedObjectiveProgress
import org.osada.ui.briefing.extendedObjectiveBriefingLines

/** Adds engine-authored scenario conditions to the legacy standalone opening message. */
internal fun extendedObjectiveOpeningHtml(
    intro: String,
    conditions: List<ExtendedObjectiveProgress>,
): String {
    if (conditions.isEmpty()) return intro
    val prefix = if (intro.isBlank()) "" else "$intro<br><br>"
    val heading = I18n.t("briefing.objective.extended.title")
    val rows = extendedObjectiveBriefingLines(conditions).joinToString("<br>") { "• $it" }
    return "$prefix<strong>$heading</strong><br>$rows"
}
