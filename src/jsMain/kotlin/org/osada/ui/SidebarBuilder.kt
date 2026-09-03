@file:Suppress("MaxLineLength")

package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.GameHolder
import org.osada.i18n.I18n
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

        refreshLocalization()

        val sidebar = byId("osada-sidebar")
        val collapsed = localStorage.getItem(COLLAPSE_KEY) == "1"
        if (collapsed) {
            sidebar?.classList?.add("osada-sidebar--collapsed")
            HudLog.onSidebarCollapsed()
        }

        byId("osadaSideToggle")?.onclick = { _: org.w3c.dom.events.MouseEvent -> setCollapsed(true) }
        byId("osadaRailExpand")?.onclick = { _: org.w3c.dom.events.MouseEvent -> setCollapsed(false) }
    }

    fun refreshLocalization() {
        byId("hex")?.apply {
            textContent = I18n.t("hud.sidebar.grid.label")
            title = I18n.t("hud.sidebar.grid.help")
        }
        byId("air")?.apply {
            textContent = I18n.t("hud.sidebar.air.label")
            title = I18n.t("hud.sidebar.air.help")
        }
        byId("osadaSideToggle")?.title = I18n.t("hud.sidebar.collapse.help")
        byId("osadaDrawerClose")?.apply {
            val label = I18n.t("mobile.drawer.close.label")
            title = label
            setAttribute("aria-label", label)
        }
        byId("osadaRailExpand")?.title = I18n.t("hud.sidebar.expand.help")
        byId("osadaRailObjCounter")?.title = I18n.t("hud.sidebar.objective_counter.help")
        byId("osadaRailLogDot")?.title = I18n.t("hud.sidebar.new_events.help")
        documentTitle(
            "#osadaMinimapPanel .osada-sb-panel__title",
            I18n.t("hud.sidebar.minimap.help"),
        )
        documentTitle(
            ".osada-sb-panel--objectives .osada-sb-panel__title",
            I18n.t("hud.sidebar.objectives.help"),
        )
        documentTitle(
            ".osada-sb-panel--log .osada-sb-panel__title",
            I18n.t("hud.sidebar.log.help"),
        )
        byId("osadaMinimapTitle")?.textContent = I18n.t("hud.sidebar.minimap.label")
        byId("osadaObjectivesTitle")?.textContent = I18n.t("hud.sidebar.objectives.label")
        byId("osadaLogTitle")?.textContent = I18n.t("hud.sidebar.log.label")
        byId("osadaMinimapFrame")?.querySelector(".osada-side-empty")?.textContent =
            I18n.t("hud.sidebar.minimap.loading")
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
