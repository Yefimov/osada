@file:Suppress("MaxLineLength")

package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.GameHolder
import org.osada.uiSettings

/**
 * Builds the right operational sidebar (Task 2): the Grid/Air view toggles, the minimap
 * placeholder (Task 4 fills in the canvas), and the whole-sidebar collapse rail. Objectives
 * and Log panel CONTENT are filled by [StatusBarController.updateStatusBar] / [HudLog] respectively —
 * this object only wires the static chrome once at startup.
 */
internal object SidebarBuilder {
    private const val COLLAPSE_KEY = "osada-sidebar-collapsed"

    fun buildSidebar() {
        val hex = byId("hex")
        hex?.let { toggleButton(it, uiSettings.hexGrid) }
        hex?.title = "Grid (H) — show or hide hex boundaries and terrain-type overlays."
        hex?.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.mainMenuButton("hex") }

        val air = byId("air")
        air?.let { toggleButton(it, uiSettings.airMode) }
        air?.title =
            "Air mode (P) — select, move and attack with aircraft. Ground units remain visible but clicks target the air layer."
        air?.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.mainMenuButton("air") }

        byId("osadaSideToggle")?.title = "Collapse the operational sidebar to make more room for the map."
        byId("osadaRailExpand")?.title = "Expand the operational sidebar."
        byId("osadaRailObjCounter")?.title =
            "Visible objectives currently held by your side / total visible objectives."
        byId("osadaRailLogDot")?.title = "New battle-log events arrived while the sidebar was collapsed."
        documentTitle(
            "#osadaMinimapPanel .osada-sb-panel__title",
            "Minimap — friendly units are green, spotted enemies red, and objectives brass. Click or drag to centre the main map.",
        )
        documentTitle(
            ".osada-sb-panel--objectives .osada-sb-panel__title",
            "Visible victory objectives. Click an entry to centre it on the main map.",
        )
        documentTitle(
            ".osada-sb-panel--log .osada-sb-panel__title",
            "Recent turn, combat, capture and hero events. Click a located event to jump to its hex.",
        )

        val sidebar = byId("osada-sidebar")
        val collapsed = localStorage.getItem(COLLAPSE_KEY) == "1"
        if (collapsed) {
            sidebar?.classList?.add("osada-sidebar--collapsed")
            HudLog.onSidebarCollapsed()
        }

        byId("osadaSideToggle")?.onclick = { _: org.w3c.dom.events.MouseEvent -> setCollapsed(true) }
        byId("osadaRailExpand")?.onclick = { _: org.w3c.dom.events.MouseEvent -> setCollapsed(false) }
    }

    private fun documentTitle(
        selector: String,
        title: String,
    ) {
        kotlinx.browser.document
            .querySelector(selector)
            ?.setAttribute("title", title)
    }

    private fun setCollapsed(collapse: Boolean) {
        val sidebar = byId("osada-sidebar") ?: return
        if (collapse) {
            sidebar.classList.add("osada-sidebar--collapsed")
        } else {
            sidebar.classList.remove("osada-sidebar--collapsed")
        }
        localStorage.setItem(COLLAPSE_KEY, if (collapse) "1" else "0")
        if (collapse) HudLog.onSidebarCollapsed() else HudLog.onSidebarExpanded()
    }
}
