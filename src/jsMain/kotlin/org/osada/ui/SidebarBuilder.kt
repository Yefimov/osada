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
        hex?.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.mainMenuButton("hex") }

        val air = byId("air")
        air?.let { toggleButton(it, uiSettings.airMode) }
        air?.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.mainMenuButton("air") }

        val sidebar = byId("osada-sidebar")
        val collapsed = localStorage.getItem(COLLAPSE_KEY) == "1"
        if (collapsed) {
            sidebar?.classList?.add("osada-sidebar--collapsed")
            HudLog.onSidebarCollapsed()
        }

        byId("osadaSideToggle")?.onclick = { _: org.w3c.dom.events.MouseEvent -> setCollapsed(true) }
        byId("osadaRailExpand")?.onclick = { _: org.w3c.dom.events.MouseEvent -> setCollapsed(false) }
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
