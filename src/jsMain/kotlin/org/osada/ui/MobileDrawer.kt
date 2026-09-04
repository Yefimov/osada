package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.w3c.dom.events.MouseEvent

/**
 * The phone/tablet form of the operational sidebar (spec §15).
 *
 * A permanent ~280px sidebar costs a third of an 844px phone screen, so on mobile the SAME
 * `#osada-sidebar` element (minimap, objectives, log, view toggles — no content is rebuilt or
 * duplicated) slides in over the map as a drawer. Opening it must not move the map: the drawer
 * overlays `#game` rather than resizing it, so no scroll or selection state changes.
 *
 * An edge swipe is deliberately NOT the only way in — Android back-gesture and iOS Safari's own
 * edge navigation both intercept it — so an explicit top-bar button is the primary opener.
 */
internal object MobileDrawer {
    private const val OPEN_CLASS = "osada-drawer-open"
    private const val SCRIM_ID = "osadaDrawerScrim"

    var isOpen: Boolean = false
        private set

    fun install() {
        if (document.getElementById(SCRIM_ID) != null) return
        val scrim = addTag("mainbody", "div")
        scrim.id = SCRIM_ID
        scrim.className = "osada-drawer-scrim"
        scrim.setAttribute("aria-hidden", "true")
        scrim.onclick = { _: MouseEvent -> close() }

        val sidebar = byId("osada-sidebar")
        val toggles = sidebar?.querySelector(".osada-sb-toggles") as? org.w3c.dom.HTMLElement
        if (sidebar == null || toggles == null) return
        sidebar.setAttribute("role", "region")
        val closeBtn = addTag(toggles, "div")
        closeBtn.id = "osadaDrawerClose"
        closeBtn.className = "osada-drawer-close osada-ico osada-ico--close"
        closeBtn.title = I18n.t("mobile.drawer.close.label")
        // Appended AFTER the flexible `.osada-sb-toggles-spacer`, so X sits on the drawer's right
        // edge rather than crowding the Air toggle it used to butt against (2026-09-04 report).
        // The desktop collapse chevron that normally ends this row is `display:none` on phones.
        closeBtn.asButton(I18n.t("mobile.drawer.close.label")) { close() }
    }

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        if (isOpen) return
        isOpen = true
        document.body?.classList?.add(OPEN_CLASS)
        setTriggerExpanded(true)
        // The drawer's minimap has been invisible (and therefore not worth repainting) until now.
        MinimapBuilder.refresh()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        document.body?.classList?.remove(OPEN_CLASS)
        setTriggerExpanded(false)
    }

    /** Leaving the phone/tablet shell must not strand the page with a drawer class still applied. */
    fun onLayoutModeChanged(mode: LayoutMode) {
        if (!mode.isMobileShell) close()
    }

    private fun setTriggerExpanded(expanded: Boolean) {
        byId("osadaDrawerBtn")?.setAttribute("aria-expanded", expanded.toString())
    }
}
