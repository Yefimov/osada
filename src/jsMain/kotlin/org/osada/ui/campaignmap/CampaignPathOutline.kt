package org.osada.ui.campaignmap

import org.osada.ui.addTag
import org.w3c.dom.HTMLElement

/**
 * The campaign screen's folded "Campaign path" block: the route's figures, then only the places
 * where something can go differently -- branch points and defeats the campaign survives -- each a
 * link into the full map. A linear campaign shows its figures and the map button alone.
 */
internal object CampaignPathOutline {
    fun render(
        body: HTMLElement,
        data: CampaignMapData,
        open: (Int?) -> Unit,
    ) {
        body.innerHTML = ""
        CampaignMapPanel.paragraph(
            body,
            CampaignMapText.statsParts(data.stats).joinToString(" · "),
            "osada-cpath-stats",
        )
        val forks =
            data.stats.forks.mapNotNull { data.record(it) }.map { record ->
                record to OutcomeGrade.WINS.mapNotNull { grade -> phrase(data, record, grade) }.distinct()
            }
        val defeats =
            data.stats.survivableDefeats.mapNotNull { data.record(it) }.map { record ->
                record to listOfNotNull(phrase(data, record, OutcomeGrade.LOSE))
            }
        group(body, CampaignMapText.t("outline.forks"), forks, open)
        group(body, CampaignMapText.t("outline.defeats"), defeats, open)
        CampaignMapPanel.link(body, "⤢ " + CampaignMapText.t("open.label")) { open(null) }
    }

    private fun group(
        body: HTMLElement,
        title: String,
        entries: List<Pair<CampaignScenarioRecord, List<String>>>,
        open: (Int?) -> Unit,
    ) {
        if (entries.isEmpty()) return
        CampaignMapPanel.section(body, title)
        val list = addTag(body, "ul")
        list.className = "osada-cpath-list"
        entries.forEach { (record, phrases) ->
            val row = addTag(list, "li")
            CampaignMapPanel.link(row, record.title) { open(record.index) }
            addTag(row, "span").textContent = ": " + phrases.joinToString("; ")
        }
    }

    /** "Victory → Demyansk Pocket", "Brilliant Victory → ending"; null for a mere replay. */
    private fun phrase(
        data: CampaignMapData,
        record: CampaignScenarioRecord,
        grade: OutcomeGrade,
    ): String? {
        val destination =
            CampaignMapDetails.destination(data, record, grade)?.takeUnless { it == GradeDestination.Replay }
                ?: return null
        val target =
            when (destination) {
                is GradeDestination.Operation -> data.record(destination.index)?.title.orEmpty()
                is GradeDestination.Ending -> CampaignMapText.t("detail.end_victory")
                GradeDestination.ByChoice ->
                    record.choices.joinToString(" / ") { choice -> data.record(choice.target)?.title ?: choice.label }
                GradeDestination.Replay -> ""
                GradeDestination.Defeat -> CampaignMapText.t("detail.end_defeat")
            }
        val who =
            if (destination ==
                GradeDestination.ByChoice
            ) {
                CampaignMapText.t("detail.by_choice")
            } else {
                CampaignMapText.gradeName(grade)
            }
        return "$who → $target"
    }
}
