package org.osada.ui.campaignmap

import kotlinx.browser.document
import org.osada.ui.addTag
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** An edge chip waiting to be placed; chips that say the same thing about one destination merge. */
private data class EdgeLabel(
    val text: String,
    val edge: CampaignMapEdge,
    val at: MapPoint,
    val travelled: Boolean,
)

/**
 * Draws one campaign route into [surface]: an SVG layer for the edges, HTML chips for their
 * labels and one `<button>` per operation, so text wraps and clamps in CSS and every operation is
 * focusable and announced like any other control.
 */
internal object CampaignMapSurface {
    private const val SVG_NS = "http://www.w3.org/2000/svg"
    const val NODE_ATTRIBUTE = "data-cmap-node"
    private const val LABEL_STACK_STEP = 21.0

    fun render(
        surface: HTMLElement,
        data: CampaignMapData,
        geometry: CampaignMapGeometry,
        trace: CampaignRunTrace,
    ) {
        surface.innerHTML = ""
        surface.style.width = "${geometry.width}px"
        surface.style.height = "${geometry.height}px"
        surface.classList.toggle("osada-cmap-surface--traced", !trace.isEmpty)
        surface.classList.toggle("osada-cmap-surface--vertical", geometry.orientation == MapOrientation.VERTICAL)
        val svg = svg(surface, "svg")
        svg.setAttribute("width", geometry.width.toString())
        svg.setAttribute("height", geometry.height.toString())
        svg.setAttribute("class", "osada-cmap-edges")
        svg.setAttribute("aria-hidden", "true")
        arrowMarkers(svg)
        val layout = data.layout
        val labels = LinkedHashMap<String, EdgeLabel>()
        data.graph.edges.forEachIndexed { index, edge ->
            val route = layout.routes[index] ?: return@forEachIndexed
            val source = data.graph.node(edge.from)
            val points = geometry.routePoints(route, source?.isFinale == true)
            val travelled = index in trace.travelled
            val path = svg(svg, "path")
            path.setAttribute("d", geometry.path(points))
            path.setAttribute("class", edgeClass(edge, travelled))
            path.setAttribute("marker-end", "url(#osada-cmap-arrow-${edge.style.name.lowercase()})")
            CampaignMapText.edgeLabel(edge)?.let { text ->
                // A choice is named where it arrives, once per destination: two scenarios offering the
                // same fronts (Kharkov and Prokhorovka) would otherwise stack six chips in one gap.
                val choice = edge.style == EdgeStyle.CHOICE
                val key = if (choice) "$text|${edge.to}" else "#$index"
                val previous = labels[key]
                labels[key] =
                    EdgeLabel(
                        text = text,
                        edge = edge,
                        at =
                            previous?.at
                                ?: if (choice) tagPoint(data, geometry, edge.to) else geometry.labelPoint(points),
                        travelled = travelled || previous?.travelled == true,
                    )
            }
        }
        // Chips that land on the same spot (two edges into one card) stack upward instead of hiding
        // each other.
        val taken = mutableMapOf<String, Int>()
        labels.values.forEach { pending ->
            val spot = "${pending.at.x.toInt()}|${pending.at.y.toInt()}"
            val below = taken[spot] ?: 0
            taken[spot] = below + 1
            label(surface, pending.copy(at = pending.at.copy(y = pending.at.y - below * LABEL_STACK_STEP)))
        }
        data.graph.nodes
            .sortedWith(compareBy({ layout.positions.getValue(it.id).rank }, { layout.positions.getValue(it.id).lane }))
            .forEach { node ->
                CampaignMapNodeView.render(
                    surface,
                    node,
                    geometry.box(layout.positions.getValue(node.id), node.isFinale),
                    data,
                    trace,
                )
            }
    }

    fun edgeClass(
        edge: CampaignMapEdge,
        travelled: Boolean,
    ): String =
        "osada-cmap-edge osada-cmap-edge--${edge.style.name.lowercase()}" +
            if (travelled) " osada-cmap-edge--travelled" else ""

    private fun label(
        surface: HTMLElement,
        pending: EdgeLabel,
    ) {
        val edge = pending.edge
        val chip = addTag(surface, "div")
        chip.className =
            "osada-cmap-label osada-cmap-label--${edge.style.name.lowercase()}" +
            (if (pending.travelled) " osada-cmap-label--travelled" else "") +
            if (edge.style == EdgeStyle.CHOICE) " osada-cmap-label--tag" else ""
        if (edge.style == EdgeStyle.CHOICE) chip.textContent = "◆ ${pending.text}" else chip.textContent = pending.text
        edge.choice
            ?.hint
            ?.takeIf { it.isNotBlank() }
            ?.let { chip.title = it }
        chip.style.left = "${pending.at.x}px"
        chip.style.top = "${pending.at.y}px"
    }

    /** A choice's chip hangs off the operation it leads to, so it names that card rather than a line. */
    private fun tagPoint(
        data: CampaignMapData,
        geometry: CampaignMapGeometry,
        target: Int,
    ): MapPoint {
        val node = data.graph.node(target)
        return geometry.tagPoint(geometry.box(data.layout.positions.getValue(target), node?.isFinale == true))
    }

    /** One arrowhead per edge style: `context-stroke` would need one, but is not in every engine we ship to. */
    private fun arrowMarkers(svg: Element) {
        val defs = svg(svg, "defs")
        EdgeStyle.entries.forEach { style ->
            val marker = svg(defs, "marker")
            marker.setAttribute("id", "osada-cmap-arrow-${style.name.lowercase()}")
            marker.setAttribute("viewBox", "0 0 10 10")
            marker.setAttribute("refX", "9")
            marker.setAttribute("refY", "5")
            marker.setAttribute("markerWidth", "7")
            marker.setAttribute("markerHeight", "7")
            marker.setAttribute("orient", "auto-start-reverse")
            val head = svg(marker, "path")
            head.setAttribute("d", "M 0 1 L 10 5 L 0 9 z")
            head.setAttribute("class", "osada-cmap-arrow osada-cmap-arrow--${style.name.lowercase()}")
        }
    }

    private fun svg(
        parent: Element,
        tag: String,
    ): Element {
        val element = document.createElementNS(SVG_NS, tag)
        parent.appendChild(element)
        return element
    }
}
