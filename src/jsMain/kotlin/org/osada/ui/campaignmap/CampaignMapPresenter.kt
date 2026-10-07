package org.osada.ui.campaignmap

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.osada.ui.CampaignContentLocalization
import org.osada.ui.MessageDialogs
import org.osada.ui.MobileLayoutController
import org.osada.ui.OrientationGate
import org.osada.ui.StartMenuBuilder
import org.osada.ui.StartMenuListToolbar
import org.osada.ui.ViewportMetrics
import org.osada.ui.ViewportMetricsService
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The full-screen operations map of one campaign (`docs/campaigns/operations-map.md`).
 *
 * Opened from the campaign screen; read-only over campaign data and the stored run. Escape first
 * clears a selection, then closes. Keyboard: Tab walks the operations in time order, Enter opens
 * one, `+` / `-` / `0` zoom in, out and to fit.
 */
internal object CampaignMapPresenter {
    private const val INITIAL_MIN_SCALE = 0.8

    private var shell: CampaignMapShell? = null
    private var viewport: CampaignMapViewport? = null
    private var data: CampaignMapData? = null
    private var trace = CampaignRunTrace.NONE
    private var geometry: CampaignMapGeometry? = null
    private var selected: Int? = null
    private var campaignIndex: Int? = null
    private var opener: HTMLElement? = null
    private var request = 0

    init {
        I18n.onLanguageChanged { if (isOpen()) campaignIndex?.let { reload(it, selected) } }
    }

    fun isOpen(): Boolean = shell?.backdrop?.style?.display == "flex"

    /** Opens the map of [index] (a `campaignlist` index), optionally with [select] already chosen. */
    fun open(
        index: Int,
        select: Int? = null,
    ) {
        if (isOpen()) return
        opener = document.activeElement as? HTMLElement
        val frame = shell ?: CampaignMapShell(onClose = { close() }, onZoom = ::zoom).also { shell = it }
        frame.localize()
        val campaign = StartMenuBuilder.campaignList().getOrNull(index)
        frame.title.textContent =
            CampaignMapText.t(
                "title",
                mapOf(
                    "campaign" to
                        StartMenuListToolbar.campaignDisplayTitle(
                            CampaignContentLocalization.title(campaign),
                        ),
                ),
            )
        MessageDialogs.suspendDynamicMessages("campaign-map")
        frame.isolate()
        frame.backdrop.style.display = "flex"
        frame.place(ViewportMetricsService.current)
        frame.closeButton.focus()
        val view = CampaignMapViewport(frame.scroller, frame.sizer, frame.surface, ::activate)
        view.install()
        viewport = view
        frame.scroller.onclick = { event ->
            // Keyboard activation of a focused operation (pointer taps go through the viewport).
            if ((event.asDynamic().detail as? Int ?: 0) == 0) activate(event.target as? Element)
        }
        frame.panelBack.onclick = { select(null, reveal = false) }
        reload(index, select)
    }

    fun close(restoreFocus: Boolean = true) {
        val frame = shell ?: return
        if (!isOpen()) return
        request++
        viewport?.dispose()
        viewport = null
        frame.backdrop.style.display = "none"
        frame.surface.innerHTML = ""
        frame.release()
        MessageDialogs.resumeDynamicMessages("campaign-map")
        // The rotation prompt stood aside while the map was open; a phone turned upright meanwhile
        // gets it now rather than at the next resize.
        OrientationGate.update(MobileLayoutController.mode)
        if (restoreFocus) opener?.takeIf { it.isConnected }?.focus()
        opener = null
        data = null
        selected = null
    }

    /** Called by the single keyboard owner while the map is open; true when the press was ours. */
    fun handleKey(event: Event): Boolean {
        if (!isOpen()) return false
        val raw = event.asDynamic()
        // Ctrl/Cmd/Alt with + - 0 is the browser's own page zoom: zooming the map as well doubled it.
        val modified = raw.ctrlKey == true || raw.metaKey == true || raw.altKey == true
        when (if (modified) null else raw.key as? String) {
            "Escape" -> {
                event.preventDefault()
                if (selected != null) select(null, reveal = false) else close()
            }
            "Tab" -> shell?.trapTab(event)
            "+", "=" -> zoom(CampaignMapViewport.ZOOM_STEP)
            "-", "_" -> zoom(1 / CampaignMapViewport.ZOOM_STEP)
            "0" -> zoom(null)
        }
        return true
    }

    fun resize(metrics: ViewportMetrics) {
        val frame = shell ?: return
        if (!isOpen()) return
        frame.place(metrics)
        if (frame.orientation() != geometry?.orientation) render()
    }

    private fun reload(
        index: Int,
        select: Int?,
    ) {
        val frame = shell ?: return
        val token = ++request
        campaignIndex = index
        frame.status.textContent = CampaignMapText.t("loading")
        frame.status.style.display = "block"
        CampaignGraphSource.load(index) { loaded ->
            if (token != request || !isOpen()) return@load
            if (loaded == null) {
                frame.status.textContent = CampaignMapText.t("error")
                return@load
            }
            frame.status.style.display = "none"
            data = loaded
            trace = CampaignRunTraceLoader.load(loaded)
            frame.stats.textContent = CampaignMapText.statsParts(loaded.stats).joinToString(" · ")
            selected = select?.takeIf { loaded.graph.node(it) != null }
            render()
        }
    }

    /** Draws the route afresh and frames it: the selection, else the current operation, else the start. */
    private fun render() {
        val frame = shell
        val current = data
        val view = viewport
        if (frame == null || current == null || view == null) return
        val shape = CampaignMapGeometry(current.layout, frame.orientation())
        geometry = shape
        CampaignMapSurface.render(frame.surface, current, shape, trace)
        view.setContent(shape.width, shape.height)
        view.zoomTo(view.fitScale().coerceAtLeast(INITIAL_MIN_SCALE).coerceAtMost(1.0))
        boxOf(selected ?: trace.current ?: CampaignGraph.START)?.let(view::reveal)
        // With nothing to point at, a long route opens at its beginning rather than its middle.
        if (selected == null && trace.current == null) {
            if (shape.orientation == MapOrientation.HORIZONTAL) {
                frame.scroller.scrollLeft = 0.0
            } else {
                frame.scroller.scrollTop = 0.0
            }
        }
        select(selected, reveal = false)
    }

    private fun select(
        id: Int?,
        reveal: Boolean,
    ) {
        val frame = shell ?: return
        val current = data ?: return
        selected = id
        val nodes = frame.surface.querySelectorAll("[${CampaignMapSurface.NODE_ATTRIBUTE}]")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? HTMLElement ?: continue
            val match = element.getAttribute(CampaignMapSurface.NODE_ATTRIBUTE) == id?.toString()
            element.classList.toggle("osada-cmap-selected", match)
            element.setAttribute("aria-pressed", match.toString())
        }
        frame.dialog.classList.toggle("osada-cmap--selected", id != null)
        frame.panelBody.innerHTML = ""
        val node = id?.let { current.graph.node(it) }
        if (node == null) {
            CampaignMapPanel.legend(frame.panelBody, current, trace)
        } else {
            CampaignMapDetails(current, trace) { target -> select(target, reveal = true) }.render(frame.panelBody, node)
        }
        frame.panel.scrollTop = 0.0
        if (reveal && id != null) {
            boxOf(id)?.let { viewport?.reveal(it) }
            // preventScroll: the reveal above already centred it; a native focus scroll would undo that.
            val element: dynamic = frame.surface.querySelector("[${CampaignMapSurface.NODE_ATTRIBUTE}=\"$id\"]")
            element?.focus(js("({ preventScroll: true })"))
        }
    }

    private fun activate(target: Element?) {
        val node = target?.closest("[${CampaignMapSurface.NODE_ATTRIBUTE}]") ?: return
        val id = node.getAttribute(CampaignMapSurface.NODE_ATTRIBUTE)?.toIntOrNull() ?: return
        select(if (id == selected) null else id, reveal = false)
    }

    private fun zoom(factor: Double?) {
        val view = viewport ?: return
        if (factor == null) view.zoomTo(view.fitScale()) else view.zoomTo(view.scale * factor)
    }

    private fun boxOf(id: Int): MapBox? {
        val point = data?.layout?.positions?.get(id) ?: return null
        return geometry?.box(point, data?.graph?.node(id)?.isFinale == true)
    }
}
