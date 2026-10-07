package org.osada.ui.campaignmap

import org.osada.ui.addTag
import org.w3c.dom.HTMLElement

/** Small DOM builders shared by the map's side panel, plus the legend it shows when nothing is selected. */
internal object CampaignMapPanel {
    fun heading(
        panel: HTMLElement,
        title: String,
        sub: String,
    ) {
        addTag(panel, "h3").apply {
            className = "osada-cmap-panel-title"
            textContent = title
        }
        if (sub.isNotBlank()) paragraph(panel, sub, "osada-cmap-panel-sub")
    }

    fun paragraph(
        parent: HTMLElement,
        text: String,
        className: String,
    ) {
        addTag(parent, "p").apply {
            this.className = className
            textContent = text
        }
    }

    fun section(
        panel: HTMLElement,
        text: String,
    ) {
        addTag(panel, "h4").apply {
            className = "osada-cmap-panel-section"
            textContent = text
        }
    }

    fun link(
        parent: HTMLElement,
        text: String,
        onActivate: () -> Unit,
    ) {
        val button = addTag(parent, "button")
        button.setAttribute("type", "button")
        button.className = "osada-cmap-link"
        button.textContent = text
        button.onclick = { onActivate() }
    }

    /** What the panel shows before an operation is selected: what the lines and marks mean. */
    fun legend(
        panel: HTMLElement,
        data: CampaignMapData,
        trace: CampaignRunTrace,
    ) {
        CampaignMapPanel.section(panel, CampaignMapText.t("legend.title"))
        val list = addTag(panel, "ul")
        list.className = "osada-cmap-legend"
        val styles =
            data.graph.edges
                .filterNot { it.back }
                .map { it.style }
                .toSet()
        EdgeStyle.entries.filter { it in styles }.forEach { style -> swatch(list, style) }
        val nodes = data.graph.nodes
        if (nodes.any { it.defeatGrades.isNotEmpty() }) {
            mark(
                list,
                "☠",
                CampaignMapText.t("legend.defeat_end"),
                "defeat",
            )
        }
        if (data.graph.edges.any { it.back }) mark(list, "↩", CampaignMapText.t("legend.return"), "return")
        mark(list, "★", CampaignMapText.t("legend.finale"), "finale")
        if (!trace.isEmpty) {
            mark(list, "✓", CampaignMapText.t("legend.played"), "played")
            if (trace.current != null) mark(list, "◉", CampaignMapText.t("legend.current"), "current")
        }
        paragraph(panel, CampaignMapText.t("hint"), "osada-cmap-note")
    }

    private fun swatch(
        list: HTMLElement,
        style: EdgeStyle,
    ) {
        val row = addTag(list, "li")
        addTag(row, "span").className = "osada-cmap-swatch osada-cmap-swatch--${style.name.lowercase()}"
        addTag(row, "span").textContent =
            when (style) {
                EdgeStyle.MAIN -> CampaignMapText.t("legend.main")
                EdgeStyle.BRILLIANT -> CampaignMapText.gradeName(OutcomeGrade.BRILLIANT)
                EdgeStyle.VICTORY -> CampaignMapText.gradeName(OutcomeGrade.VICTORY)
                EdgeStyle.TACTICAL -> CampaignMapText.gradeName(OutcomeGrade.TACTICAL)
                EdgeStyle.LOSE -> CampaignMapText.t("legend.lose")
                EdgeStyle.CHOICE -> CampaignMapText.t("legend.choice")
            }
    }

    private fun mark(
        list: HTMLElement,
        glyph: String,
        text: String,
        kind: String,
    ) {
        val row = addTag(list, "li")
        addTag(row, "span").apply {
            className = "osada-cmap-legend-mark osada-cmap-legend-mark--$kind"
            textContent = glyph
        }
        addTag(row, "span").textContent = text
    }
}
