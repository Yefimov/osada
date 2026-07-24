package org.osada.ui

import org.osada.GameHolder

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

        // Reparent the existing status elements (ids kept — StatusBarController fills them).
        // --- scenario / turn / date (center-left) ---
        byId("statusmsg")?.let { statusbar.appendChild(it) }
        // --- weather (text) ---
        byId("weathermsg")?.let { statusbar.appendChild(it) }

        buildCombatLogSpacer(statusbar)
        buildPrestige(statusbar)
        buildReservesButton(statusbar)
        buildIconCluster(statusbar)
        buildReadyUnitNav(statusbar)
        buildEndTurnButton(statusbar)

        // --- hex coords / terrain (far right, muted) ---
        byId("locmsg")?.let { statusbar.appendChild(it) }

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
        observer.textContent = "OBSERVER"
        observer.title =
            "Observer mode is active (fog of war disabled and/or hidden objectives shown) — affects game balance"
        observer.style.display = "none"
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
            btn.title = "Turn Report (L)"
        }
        val spacer2 = addTag(statusbar, "div")
        spacer2.className = "osada-tb-spacer"
    }

    private fun buildPrestige(statusbar: org.w3c.dom.HTMLElement) {
        // --- prestige + per-turn delta ---
        val prestige = addTag(statusbar, "div")
        prestige.id = "osadaPrestige"
        prestige.className = "osada-tb-prestige"
        prestige.title = "Prestige"
    }

    private fun buildReservesButton(statusbar: org.w3c.dom.HTMLElement) {
        // --- Reserves button (old buy/upgrade action) with undeployed-count badge ---
        val reserves = addTag(statusbar, "div")
        reserves.id = "buy"
        reserves.className = "osada-tb-btn osada-tb-reserves"
        reserves.title = "Reserves — buy, upgrade and deploy units (R)"
        val reservesLabel = addTag(reserves, "span")
        reservesLabel.className = "osada-tb-reserves__label"
        reservesLabel.textContent = "Reserves"
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
        iconButton("zoom", "zoom", "recon", "Strategic map — zoom out to the whole theatre (M)")
        iconButton("options", "options", "settings", "Options & menu (Esc)")
    }

    private fun buildReadyUnitNav(statusbar: org.w3c.dom.HTMLElement) {
        // --- ready-unit navigator [<  N  >] ---
        val nav = addTag(statusbar, "div")
        nav.id = "osadaNav"
        nav.className = "osada-tb-nav"
        nav.title = "Cycle through your units that can still act this turn"
        val prev = addTag(nav, "span")
        prev.id = "osadaNavPrev"
        prev.className = "osada-tb-nav__arrow"
        prev.innerHTML = "<img class=\"osada-tb-nav__glyph\" src=\"resources/ui/osada/ico_nav_prev.png\" alt=\"◀\">"
        prev.title = "Previous ready unit"
        prev.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.cycleReadyUnit(-1) }
        val count = addTag(nav, "span")
        count.id = "osadaNavCount"
        count.className = "osada-tb-nav__count"
        count.textContent = "0"
        val next = addTag(nav, "span")
        next.id = "osadaNavNext"
        next.className = "osada-tb-nav__arrow"
        next.innerHTML = "<img class=\"osada-tb-nav__glyph\" src=\"resources/ui/osada/ico_nav_next.png\" alt=\"▶\">"
        next.title = "Next ready unit"
        next.onclick = { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.cycleReadyUnit(1) }
    }

    private fun buildEndTurnButton(statusbar: org.w3c.dom.HTMLElement) {
        // --- END TURN (state machine lives in EndTurnFlow/ReadyUnitNavigator) ---
        val endTurn = addTag(statusbar, "div")
        endTurn.id = "osadaEndTurn"
        endTurn.className = "osada-et osada-et--ready"
        endTurn.title = "End turn"
        val etLabel = addTag(endTurn, "span")
        etLabel.className = "osada-et__label"
        etLabel.textContent = "End turn"
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
        statusBarButton?.onclick =
            { _: org.w3c.dom.events.MouseEvent -> GameHolder.instance?.ui?.toggleUnitsAndEquipmentWindow(false) }
        val unitsBarButton = byId("unitsBarButton")
        unitsBarButton?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("unitsBarButton")
            byId("equipment")?.style?.display = "grid"
        }
    }
}
