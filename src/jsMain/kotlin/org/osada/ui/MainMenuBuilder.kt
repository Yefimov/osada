@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.GameHolder
import org.osada.i18n.I18n

/**
 * Builds the full-width in-game TOP BAR (OSADA Stage-3 HUD, Task 1) and wires the buttons that
 * used to live in the floating `#menu` icon rail. The rail is dissolved: End-Turn, Reserves
 * (buy/upgrade), Strategic map and Options now live in the top bar, plus a ready-unit navigator.
 *
 * Buttons keep the ids the action router ([MainMenuButtonHandler.mainMenuButton]) toggles (`buy`, `zoom`,
 * `options`), so relocating them does not change any action. Hex-grid and Air-mode toggles move to
 * the sidebar (built in Task 2); their ids simply don't exist yet, and every `byId(...)?.let` that
 * toggles them is null-safe.
 */
internal object MainMenuBuilder {
    fun buildMainMenu() {
        val statusbar = byId("statusbar") ?: return

        buildBrandAndObserver(statusbar)
        buildDrawerButton(statusbar)

        // Reparent the existing status elements (ids kept — StatusBarController fills them).
        // --- scenario / turn / date (center-left) ---
        byId("statusmsg")?.let {
            statusbar.appendChild(it)
            it.title =
                "Current scenario, turn limit and in-game date. Victory tiers may require taking all objectives before an earlier turn threshold."
        }
        // --- weather (text) ---
        byId("weathermsg")?.let { statusbar.appendChild(it) }

        buildCombatLogSpacer(statusbar)
        buildPrestige(statusbar)
        buildReservesButton(statusbar)
        buildIconCluster(statusbar)
        buildReadyUnitNav(statusbar)
        buildEndTurnButton(statusbar)

        // --- hex coords / terrain (far right, muted) ---
        byId("locmsg")?.let {
            statusbar.appendChild(it)
            it.title = I18n.t("hud.status.location.help")
        }

        // The bar no longer toggles the combat log on click (buttons live in it now).
        statusbar.onclick = null

        // Dissolve the floating rail entirely.
        byId("menu")?.style?.display = "none"

        wireLegacyButtons()
    }

    private fun buildBrandAndObserver(statusbar: org.w3c.dom.HTMLElement) {
        // --- brand (far left): the riveted-metal logo art, not text ---
        val brand = addTag(statusbar, "div")
        brand.id = "osadaBrand"
        brand.className = "osada-tb-brand"
        brand.innerHTML = "<img class=\"osada-tb-brand-logo\" src=\"resources/logo_osada.png\" alt=\"OSADA\">"
        brand.title = "OSADA"

        // Observer-mode badge (Task 5 toggles it; hidden by default).
        val observer = addTag(statusbar, "div")
        observer.id = "osadaObserverBadge"
        observer.className = "osada-tb-observer"
        observer.textContent = I18n.t("hud.observer.label")
        observer.title =
            "Observer mode is active (fog of war disabled and/or hidden objectives shown) — affects game balance"
        observer.style.display = "none"
    }

    /**
     * Opens the operational drawer on phone/tablet layouts (spec §15). Hidden by CSS on desktop,
     * where the same panels are the permanent sidebar. It is deliberately an explicit button and
     * not only an edge swipe: Android's back gesture and iOS Safari's edge navigation both eat
     * edge swipes, so a swipe-only drawer is unreachable on exactly the devices that need it.
     */
    private fun buildDrawerButton(statusbar: org.w3c.dom.HTMLElement) {
        val button = addTag(statusbar, "div")
        button.id = "osadaDrawerBtn"
        button.className = "osada-tb-btn osada-tb-drawer"
        button.title = I18n.t("mobile.drawer.open.help")
        button.setAttribute("aria-expanded", "false")
        button.setAttribute("aria-controls", "osada-sidebar")
        val glyph = addTag(button, "span")
        glyph.className = "osada-tb-drawer__glyph"
        glyph.setAttribute("aria-hidden", "true")
        glyph.textContent = "≡"
        val label = addTag(button, "span")
        label.className = "osada-tb-drawer__label"
        label.textContent = I18n.t("mobile.drawer.open.label")
        button.asButton(I18n.t("mobile.drawer.open.label")) { MobileDrawer.toggle() }
    }

    private fun buildCombatLogSpacer(statusbar: org.w3c.dom.HTMLElement) {
        // --- flex spacer / Turn Report toggle / flex spacer (button centered mid-bar) ---
        val spacer = addTag(statusbar, "div")
        spacer.className = "osada-tb-spacer"
        // Reparent (not rebuild): combatLogButton is a static index.html element UICombatLog.kt
        // already finds by id and toggles a "selected" attribute on when the report opens/closes
        // — moving the same node keeps that wiring intact. It used to stay behind as an
        // independent position:fixed floating glyph that sat directly over the Turn Report's own
        // title once that window was open. Centered between two flex spacers — matching its
        // legacy screen-center position (mid-bar, not in the right icon cluster).
        byId("combatLogButton")?.let { btn ->
            statusbar.appendChild(btn)
            btn.classList.add("osada-tb-icon", "osada-tb-combatlog")
            btn.title = I18n.t("hud.turn_report.help")
        }
        val spacer2 = addTag(statusbar, "div")
        spacer2.className = "osada-tb-spacer"
    }

    private fun buildPrestige(statusbar: org.w3c.dom.HTMLElement) {
        // --- prestige + per-turn delta ---
        val prestige = addTag(statusbar, "div")
        prestige.id = "osadaPrestige"
        prestige.className = "osada-tb-prestige"
        prestige.title =
            "Prestige — campaign resources spent on buying, upgrading and reinforcing units. The green value is next turn's income."
    }

    private fun buildReservesButton(statusbar: org.w3c.dom.HTMLElement) {
        // --- Reserves button (old buy/upgrade action) with undeployed-count badge ---
        val reserves = addTag(statusbar, "div")
        reserves.id = "buy"
        reserves.className = "osada-tb-btn osada-tb-reserves"
        reserves.title =
            "Reserves (R) — buy new units, upgrade existing formations, and deploy purchased units from the reserve tray."
        val reservesLabel = addTag(reserves, "span")
        reservesLabel.className = "osada-tb-reserves__label"
        reservesLabel.textContent = I18n.t("hud.reserves.label")
        val reservesBadge = addTag(reserves, "span")
        reservesBadge.id = "osadaReservesBadge"
        reservesBadge.className = "osada-tb-reserves__badge"
        reservesBadge.style.display = "none"
        reserves.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.mainMenuButton("buy") }
    }

    private fun buildIconCluster(statusbar: org.w3c.dom.HTMLElement) {
        // --- icon cluster: strategic map, options ---
        val icons = addTag(statusbar, "div")
        icons.className = "osada-tb-icons"

        fun iconButton(
            id: String,
            action: String,
            icoMod: String,
            tip: String,
        ) {
            val b = addTag(icons, "div")
            b.id = id
            b.className = "osada-tb-icon osada-ico osada-ico--$icoMod"
            b.title = tip
            b.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.mainMenuButton(action) }
        }
        iconButton(
            "zoom",
            "zoom",
            "recon",
            "Strategic map (M) — toggle a whole-theatre overview for navigation. Unit actions are unavailable in this view.",
        )

        // Headquarters → Heroic Commanders roster (§14.3), view-only during a scenario. Always
        // present; the roster shows an empty state until heroes emerge.
        val hq = addTag(icons, "div")
        hq.id = "osadaHqBtn"
        // Red star sprite (hud_icons_grid row 3, col 2 = .osada-ico--star), matching the other
        // top-bar icons rather than a text glyph.
        hq.className = "osada-tb-icon osada-ico osada-ico--star"
        hq.title =
            "Headquarters — inspect all heroic commanders, their formations, status, recognition and career history."
        hq.onclick = { _: org.w3c.dom.events.MouseEvent -> CommanderRosterPresenter.open() }

        iconButton(
            "options",
            "options",
            "settings",
            "Options & menu (Esc) — save or load the game, change settings, or return to the battle.",
        )
    }

    private fun buildReadyUnitNav(statusbar: org.w3c.dom.HTMLElement) {
        // --- ready-unit navigator [<  N  >] ---
        val nav = addTag(statusbar, "div")
        nav.id = "osadaNav"
        nav.className = "osada-tb-nav"
        nav.title =
            "Ready units — the number of your units that can still move, attack or use another action this turn."
        val prev = addTag(nav, "span")
        prev.id = "osadaNavPrev"
        prev.className = "osada-tb-nav__arrow"
        prev.innerHTML = "<img class=\"osada-tb-nav__glyph\" src=\"resources/ui/osada/ico_nav_prev.png\" alt=\"◀\">"
        prev.title = I18n.t("hud.ready_units.previous.help")
        prev.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.cycleReadyUnit(-1) }
        val count = addTag(nav, "span")
        count.id = "osadaNavCount"
        count.className = "osada-tb-nav__count"
        count.textContent = "0"
        count.title = I18n.t("hud.ready_units.count.help")
        val next = addTag(nav, "span")
        next.id = "osadaNavNext"
        next.className = "osada-tb-nav__arrow"
        next.innerHTML = "<img class=\"osada-tb-nav__glyph\" src=\"resources/ui/osada/ico_nav_next.png\" alt=\"▶\">"
        next.title = I18n.t("hud.ready_units.next.help")
        next.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.cycleReadyUnit(1) }
    }

    private fun buildEndTurnButton(statusbar: org.w3c.dom.HTMLElement) {
        // --- END TURN (state machine lives in EndTurnFlow/ReadyUnitNavigator) ---
        val endTurn = addTag(statusbar, "div")
        endTurn.id = "osadaEndTurn"
        endTurn.className = "osada-et osada-et--ready"
        endTurn.title =
            "End the current side's turn. If confirmation is enabled, you will be warned about units that have not acted."
        val etLabel = addTag(endTurn, "span")
        etLabel.className = "osada-et__label"
        etLabel.textContent = I18n.t("hud.end_turn.label")
        endTurn.onclick = { event: org.w3c.dom.events.MouseEvent ->
            event.stopPropagation()
            GameHolder.instance?.ui?.onEndTurnClick()
        }
    }

    private fun wireLegacyButtons() {
        // combatLogButton was reparented into the icon cluster above; wiring stays here.
        // Deploy-strip legacy buttons (statusBarButton/unitsBarButton below) keep working
        // (repositioned/hidden by CSS, not reparented — they only show during deploy phase).
        val combatLogButton = byId("combatLogButton")
        combatLogButton?.asDynamic()?.hasSelectedGlyph = true
        combatLogButton?.onclick = { _: org.w3c.dom.events.MouseEvent -> UICombatLog.toggleCombatLog(false, true) }

        val sideToggle = byId("osadaSideToggle")
        sideToggle?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val sidebar = byId("osada-sidebar")
            val collapsed = sidebar?.classList?.toggle("osada-sidebar--collapsed") ?: false
            sideToggle.textContent = if (collapsed) "+" else "-"
        }

        val statusBarButton = byId("statusBarButton")
        statusBarButton?.title = I18n.t("hud.deploy_strip.close.help")
        statusBarButton?.onclick =
            { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.toggleUnitsAndEquipmentWindow(false) }
        val unitsBarButton = byId("unitsBarButton")
        unitsBarButton?.title = I18n.t("hud.deploy_strip.open.help")
        unitsBarButton?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("unitsBarButton")
            CompactEquipmentNavigation.showList()
            byId("equipment")?.style?.display = "grid"
        }
    }
}
