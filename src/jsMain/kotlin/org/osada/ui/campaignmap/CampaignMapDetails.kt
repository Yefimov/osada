package org.osada.ui.campaignmap

import org.osada.scenario.CampaignRouting
import org.osada.ui.addTag
import org.w3c.dom.HTMLElement

/** Where one outcome grade of a scenario leads, as the detail panel spells it out. */
internal sealed interface GradeDestination {
    data class Operation(
        val index: Int,
        val back: Boolean,
    ) : GradeDestination

    data object ByChoice : GradeDestination

    data object Replay : GradeDestination

    data object Defeat : GradeDestination

    data class Ending(
        val finaleId: Int,
    ) : GradeDestination
}

/** The side panel's operation view: the briefing opening, every result and every briefing choice. */
internal class CampaignMapDetails(
    private val data: CampaignMapData,
    private val trace: CampaignRunTrace,
    private val navigate: (Int) -> Unit,
) {
    fun render(
        panel: HTMLElement,
        node: CampaignMapNode,
    ) {
        val record = node.record
        if (record == null) renderFinale(panel, node) else renderOperation(panel, record)
    }

    private fun renderOperation(
        panel: HTMLElement,
        record: CampaignScenarioRecord,
    ) {
        CampaignMapPanel.heading(panel, record.title, CampaignMapText.year(record.year))
        CampaignMapText.status(trace, record.index)?.let { CampaignMapPanel.paragraph(panel, it, "osada-cmap-status") }
        if (record.summary.isNotBlank()) CampaignMapPanel.paragraph(panel, record.summary, "osada-cmap-summary")
        CampaignMapPanel.section(panel, CampaignMapText.t("detail.outcomes"))
        val list = addTag(panel, "ul")
        list.className = "osada-cmap-results"
        OutcomeGrade.entries.forEach { grade ->
            // A defeat that only replays the same operation is not worth a row: every defeat can be
            // replayed from its checkpoint, so it says nothing about where the campaign goes.
            destination(data, record, grade)
                ?.takeUnless { it == GradeDestination.Replay }
                ?.let { resultRow(list, record, grade, it) }
        }
        if (record.choices.isNotEmpty()) renderChoices(panel, record)
    }

    private fun resultRow(
        list: HTMLElement,
        record: CampaignScenarioRecord,
        grade: OutcomeGrade,
        destination: GradeDestination,
    ) {
        val row = addTag(list, "li")
        row.className = "osada-cmap-result osada-cmap-result--${grade.name.lowercase()}"
        addTag(row, "span").apply {
            className = "osada-cmap-result-grade"
            textContent = CampaignMapText.gradeName(grade)
        }
        CampaignMapText.prestige(record.prestige[grade])?.let { amount ->
            addTag(row, "span").apply {
                className = "osada-cmap-result-prestige"
                textContent = amount
            }
        }
        val target = addTag(row, "span")
        target.className = "osada-cmap-result-target"
        when (destination) {
            is GradeDestination.Operation -> {
                val title = data.record(destination.index)?.title.orEmpty()
                CampaignMapPanel.link(
                    target,
                    if (destination.back) "↩ $title" else "→ $title",
                ) { navigate(destination.index) }
            }
            is GradeDestination.Ending ->
                CampaignMapPanel.link(
                    target,
                    "★ " + CampaignMapText.t("detail.end_victory"),
                ) { navigate(destination.finaleId) }
            GradeDestination.ByChoice -> target.textContent = "◆ " + CampaignMapText.t("detail.by_choice")
            GradeDestination.Replay -> Unit
            GradeDestination.Defeat -> target.textContent = "☠ " + CampaignMapText.t("detail.end_defeat")
        }
    }

    private fun renderChoices(
        panel: HTMLElement,
        record: CampaignScenarioRecord,
    ) {
        CampaignMapPanel.section(panel, CampaignMapText.t("detail.choices"))
        CampaignMapPanel.paragraph(panel, CampaignMapText.t("detail.choice_note"), "osada-cmap-note")
        val list = addTag(panel, "ul")
        list.className = "osada-cmap-choices"
        record.choices.forEach { choice ->
            val row = addTag(list, "li")
            row.className = "osada-cmap-choice"
            addTag(row, "span").apply {
                className = "osada-cmap-choice-label"
                textContent = "◆ ${choice.label}"
            }
            data.record(choice.target)?.let { target ->
                CampaignMapPanel.link(addTag(row, "span"), "→ ${target.title}") { navigate(choice.target) }
            }
            if (choice.hint.isNotBlank()) CampaignMapPanel.paragraph(row, choice.hint, "osada-cmap-choice-hint")
            if (choice.conditional) {
                CampaignMapPanel.paragraph(
                    row,
                    CampaignMapText.t("detail.conditional_choice"),
                    "osada-cmap-note",
                )
            }
        }
    }

    private fun renderFinale(
        panel: HTMLElement,
        node: CampaignMapNode,
    ) {
        CampaignMapPanel.heading(panel, CampaignMapText.t("finale.title"), "")
        val source = node.finaleOf?.let { data.record(it) } ?: return
        val reached =
            data.graph.edges
                .withIndex()
                .any { it.value.to == node.id && it.index in trace.travelled }
        if (reached) CampaignMapPanel.paragraph(panel, CampaignMapText.t("detail.finale_reached"), "osada-cmap-status")
        val grades = OutcomeGrade.WINS.filter { destination(data, source, it) is GradeDestination.Ending }
        val text =
            if (grades.isEmpty()) {
                CampaignMapText.t("detail.end_victory")
            } else {
                CampaignMapText.t(
                    "detail.finale_grades",
                    mapOf(
                        "outcomes" to grades.joinToString(", ") { CampaignMapText.gradeName(it) },
                    ),
                )
            }
        CampaignMapPanel.paragraph(panel, text, "osada-cmap-summary")
        CampaignMapPanel.link(addTag(panel, "p"), "← ${source.title}") { navigate(source.index) }
    }

    companion object {
        fun destination(
            data: CampaignMapData,
            record: CampaignScenarioRecord,
            grade: OutcomeGrade,
        ): GradeDestination? {
            val routed = record.choices.isNotEmpty() && record.choices.none { it.conditional }
            val goto = record.gotos[grade]
            return when {
                routed && CampaignRouting.routeApplies(grade.key) -> GradeDestination.ByChoice
                goto == null -> null
                goto == record.index -> GradeDestination.Replay
                goto in data.records.indices ->
                    GradeDestination.Operation(
                        goto,
                        data.graph.edges.any {
                            it.from == record.index &&
                                it.to == goto &&
                                it.back
                        },
                    )
                grade == OutcomeGrade.LOSE -> GradeDestination.Defeat
                else -> GradeDestination.Ending(CampaignGraph.finaleId(record.index))
            }
        }
    }
}
