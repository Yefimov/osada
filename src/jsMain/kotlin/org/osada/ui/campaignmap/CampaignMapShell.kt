package org.osada.ui.campaignmap

import kotlinx.browser.document
import org.osada.ui.ViewportMetrics
import org.osada.ui.addTag
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The operations map's dialog frame, built once and reused: header (title, route figures, zoom and
 * close), the scrollable map and the side panel. It also owns the modal plumbing the music library
 * established -- everything else on the page goes `inert` while it is open, and Tab stays inside.
 */
internal class CampaignMapShell(
    onClose: () -> Unit,
    onZoom: (Double?) -> Unit,
) {
    val backdrop: HTMLElement = addTag(document.body, "div")
    val dialog: HTMLElement = addTag(backdrop, "section")
    val title: HTMLElement
    val stats: HTMLElement
    val scroller: HTMLElement
    val sizer: HTMLElement
    val surface: HTMLElement
    val status: HTMLElement
    val panel: HTMLElement
    val panelBody: HTMLElement
    val panelBack: HTMLElement
    val closeButton: HTMLElement
    private val inert = mutableListOf<HTMLElement>()

    init {
        backdrop.id = "osadaCampaignMap"
        backdrop.className = "osada-cmap-backdrop"
        backdrop.style.display = "none"
        dialog.className = "osada-cmap"
        dialog.setAttribute("role", "dialog")
        dialog.setAttribute("aria-modal", "true")
        dialog.setAttribute("aria-labelledby", "osadaCmapTitle")
        val header = addTag(dialog, "header")
        header.className = "osada-cmap-header"
        val heading = addTag(header, "div")
        heading.className = "osada-cmap-heading"
        title = addTag(heading, "h2")
        title.id = "osadaCmapTitle"
        stats = addTag(heading, "div")
        stats.className = "osada-cmap-stats"
        val tools = addTag(header, "div")
        tools.className = "osada-cmap-tools"
        // Phones: the side panel is a bottom sheet, opened on demand to read the legend.
        tool(tools, "?", "legend") { dialog.classList.toggle("osada-cmap--sheet") }
        tool(tools, "−", "zoom_out") { onZoom(1 / CampaignMapViewport.ZOOM_STEP) }
        tool(tools, "⤢", "fit") { onZoom(null) }
        tool(tools, "+", "zoom_in") { onZoom(CampaignMapViewport.ZOOM_STEP) }
        closeButton = tool(tools, "✕", "close") { onClose() }
        val body = addTag(dialog, "div")
        body.className = "osada-cmap-body"
        scroller = addTag(body, "div")
        scroller.className = "osada-cmap-scroller"
        sizer = addTag(scroller, "div")
        sizer.className = "osada-cmap-sizer"
        surface = addTag(sizer, "div")
        surface.className = "osada-cmap-surface"
        status = addTag(body, "div")
        status.className = "osada-cmap-loading"
        panel = addTag(body, "aside")
        panel.className = "osada-cmap-panel"
        panelBack = addTag(panel, "button")
        panelBack.className = "osada-cmap-panel-back"
        panelBack.setAttribute("type", "button")
        panelBody = addTag(panel, "div")
        panelBody.className = "osada-cmap-panel-body"
        panelBody.setAttribute("aria-live", "polite")
        // A tap on the dim margin around the dialog closes it, as the music library's does.
        backdrop.addEventListener("click", { event -> if (event.target == backdrop) onClose() })
        localize()
    }

    /** Re-reads every fixed label; the map content itself is rebuilt by the presenter. */
    fun localize() {
        val buttons = dialog.querySelectorAll("[data-cmap-tool]")
        for (index in 0 until buttons.length) {
            val button = buttons.item(index) as? HTMLElement ?: continue
            val label = CampaignMapText.t("${button.getAttribute("data-cmap-tool")}.label")
            button.title = label
            button.setAttribute("aria-label", label)
        }
        panelBack.textContent = "← " + CampaignMapText.t("legend.back")
    }

    fun place(metrics: ViewportMetrics) {
        val width = (metrics.width - metrics.safeLeft - metrics.safeRight).coerceAtLeast(0.0)
        val height = (metrics.height - metrics.safeTop - metrics.safeBottom).coerceAtLeast(0.0)
        backdrop.style.left = "${metrics.offsetLeft + metrics.safeLeft}px"
        backdrop.style.top = "${metrics.offsetTop + metrics.safeTop}px"
        backdrop.style.width = "${width}px"
        backdrop.style.height = "${height}px"
        // Too small for a permanent side panel: it becomes a sheet, from the bottom when upright
        // and from the right on a phone held sideways.
        val compact = width < COMPACT_WIDTH || height < COMPACT_HEIGHT
        dialog.classList.toggle("osada-cmap--compact", compact)
        backdrop.classList.toggle("osada-cmap-backdrop--compact", compact)
        dialog.classList.toggle("osada-cmap--upright", compact && height > width)
    }

    /** Upright and narrow (a phone held in portrait) reads top to bottom; anything else left to right. */
    fun orientation(): MapOrientation {
        val width = backdrop.clientWidth.toDouble()
        val height = backdrop.clientHeight.toDouble()
        return if (width < VERTICAL_MAX_WIDTH && height > width) MapOrientation.VERTICAL else MapOrientation.HORIZONTAL
    }

    fun isolate() {
        val children = document.body?.children ?: return
        for (index in 0 until children.length) {
            val child = children.item(index) as? HTMLElement ?: continue
            if (child != backdrop && child.asDynamic().inert != true) {
                child.asDynamic().inert = true
                inert += child
            }
        }
    }

    fun release() {
        inert.forEach { it.asDynamic().inert = false }
        inert.clear()
    }

    /** Keeps Tab and Shift+Tab cycling inside the dialog. */
    fun trapTab(event: Event) {
        val nodes = dialog.querySelectorAll("button:not(:disabled)")
        val visible =
            (0 until nodes.length)
                .mapNotNull { nodes.item(it) as? HTMLElement }
                .filter { it.getBoundingClientRect().width > 0 }
        val first = visible.firstOrNull() ?: return
        val last = visible.last()
        val backwards = event.asDynamic().shiftKey == true
        if (backwards && document.activeElement == first) {
            event.preventDefault()
            last.focus()
        } else if (!backwards && document.activeElement == last) {
            event.preventDefault()
            first.focus()
        }
    }

    private fun tool(
        parent: HTMLElement,
        glyph: String,
        name: String,
        onActivate: () -> Unit,
    ): HTMLElement {
        val button = addTag(parent, "button")
        button.setAttribute("type", "button")
        button.setAttribute("data-cmap-tool", name)
        button.className = "osada-cmap-tool osada-cmap-tool--$name"
        button.textContent = glyph
        button.onclick = { onActivate() }
        return button
    }

    private companion object {
        const val VERTICAL_MAX_WIDTH = 600.0
        const val COMPACT_WIDTH = 900.0
        const val COMPACT_HEIGHT = 560.0
    }
}
