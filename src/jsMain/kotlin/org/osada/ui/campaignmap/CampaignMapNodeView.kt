package org.osada.ui.campaignmap

import org.osada.ui.addTag
import org.w3c.dom.HTMLElement

/** One operation card or campaign-ending medallion on the map surface. */
internal object CampaignMapNodeView {
    private const val MARK_DEFEAT = "☠"
    private const val MARK_RETURN = "↩"
    private const val MEDAL = "★"

    fun render(
        surface: HTMLElement,
        node: CampaignMapNode,
        box: MapBox,
        data: CampaignMapData,
        trace: CampaignRunTrace,
    ) {
        val button = addTag(surface, "button")
        button.setAttribute("type", "button")
        button.setAttribute(CampaignMapSurface.NODE_ATTRIBUTE, node.id.toString())
        button.style.left = "${box.x}px"
        button.style.top = "${box.y}px"
        button.style.width = "${box.width}px"
        button.style.height = "${box.height}px"
        if (node.isFinale) finale(button, node, data, trace) else operation(button, node, data, trace)
    }

    private fun operation(
        button: HTMLElement,
        node: CampaignMapNode,
        data: CampaignMapData,
        trace: CampaignRunTrace,
    ) {
        val record = node.record ?: return
        val played = trace.played[node.id]
        val classes = mutableListOf("osada-cmap-node")
        if (played != null) classes += listOf("osada-cmap-node--played", "osada-cmap-node--${played.name.lowercase()}")
        if (trace.current == node.id) classes += "osada-cmap-node--current"
        if (trace.defeatedAt == node.id) classes += "osada-cmap-node--defeated"
        button.className = classes.joinToString(" ")
        val meta = addTag(button, "span")
        meta.className = "osada-cmap-node-meta"
        addTag(meta, "span").apply {
            className = "osada-cmap-node-year"
            textContent = CampaignMapText.year(record.year)
        }
        val marks = markers(node, data)
        if (marks.isNotEmpty()) {
            addTag(meta, "span").apply {
                className = "osada-cmap-node-marks"
                textContent = marks.joinToString(" ") { it.first }
                title = marks.joinToString("\n") { it.second }
            }
        }
        addTag(button, "span").apply {
            className = "osada-cmap-node-title"
            textContent = record.title
        }
        button.title = record.title
        badges(button, node.id, trace)
        val spoken =
            listOf(record.title, CampaignMapText.year(record.year), CampaignMapText.status(trace, node.id).orEmpty()) +
                marks.map { it.second }
        button.setAttribute("aria-label", spoken.filter { it.isNotBlank() }.joinToString(". "))
    }

    /** The played-result stamp and the "you are here" tab. */
    private fun badges(
        button: HTMLElement,
        id: Int,
        trace: CampaignRunTrace,
    ) {
        trace.played[id]?.let { played ->
            addTag(button, "span").apply {
                className = "osada-cmap-stamp osada-cmap-stamp--${played.name.lowercase()}"
                textContent = CampaignMapText.gradeShort(played)
            }
        }
        if (trace.current == id) {
            addTag(button, "span").apply {
                className = "osada-cmap-here"
                textContent = CampaignMapText.t("node.here")
            }
        }
    }

    /** Glyph + its meaning, for what a defeat or a cycle does here. */
    fun markers(
        node: CampaignMapNode,
        data: CampaignMapData,
    ): List<Pair<String, String>> {
        val marks = mutableListOf<Pair<String, String>>()
        if (node.defeatGrades.isNotEmpty()) marks += MARK_DEFEAT to CampaignMapText.t("legend.defeat_end")
        if (data.graph.outgoing(node.id).any { it.back }) marks += MARK_RETURN to CampaignMapText.t("legend.return")
        return marks
    }

    private fun finale(
        button: HTMLElement,
        node: CampaignMapNode,
        data: CampaignMapData,
        trace: CampaignRunTrace,
    ) {
        val reached =
            data.graph.edges
                .withIndex()
                .any { it.value.to == node.id && it.index in trace.travelled }
        button.className = "osada-cmap-finale" + if (reached) " osada-cmap-finale--reached" else ""
        addTag(button, "span").apply {
            className = "osada-cmap-medal"
            textContent = MEDAL
        }
        addTag(button, "span").apply {
            className = "osada-cmap-finale-label"
            textContent = CampaignMapText.t("finale.label")
        }
        val source = node.finaleOf?.let { data.record(it)?.title }.orEmpty()
        button.setAttribute("aria-label", CampaignMapText.t("detail.finale_from", mapOf("scenario" to source)))
        button.title = button.getAttribute("aria-label").orEmpty()
    }
}
