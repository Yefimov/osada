@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package org.osada.ui

import org.osada.GameHolder
import org.osada.UnitClass
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * Task 0a: rebuilds #equipment as ONE CSS grid with named areas
 * header / mode-tabs / class-tabs / list / detail / footer.
 * Split out of [EquipmentWindowBuilder] purely to keep that type under the detekt
 * TooManyFunctions limit — no behavior split intended.
 */
internal fun EquipmentWindowBuilder.restructureEquipmentWindow() {
    val eq = byId("equipment") ?: return
    if (byId("eqGridHeader") != null) return

    buildEqHeader(eq)
    buildEqModeTabs(eq)
    buildEqClassTabsRow(eq)
    buildEqListPane(eq)
    buildEqDetailPane(eq)
    buildEqFooter(eq)

    renderEquipmentDetail(null)
    setEquipmentMode("purchase")
    // Esc handling for this window is centralized in MainMenuButtonHandler.handleGlobalEscape
    // (a single document-level listener; a second one here would double-fire on the same
    // keypress — e.g. closing equipment AND toggling the pause menu on one Escape tap).
}

private fun moveInto(
    id: String,
    into: HTMLElement,
) {
    byId(id)?.let { into.appendChild(it) }
}

private fun EquipmentWindowBuilder.buildEqHeader(eq: HTMLElement) {
    // --- header: title · prestige (always visible) · fixed-size close ---
    val header = addTag(eq, "div")
    header.id = "eqGridHeader"
    moveInto("eqInfoText", header)
    val prestigeWrap = addTag(header, "div")
    prestigeWrap.id = "eqPrestigeWrap"
    prestigeWrap.title = I18n.t("equipment.prestige.help")
    moveInto("currentPrestige", prestigeWrap)
    moveInto("currentPrestigeAmount", prestigeWrap)
    moveInto("eqCloseBut", header)
}

private fun EquipmentWindowBuilder.buildEqModeTabs(eq: HTMLElement) {
    // --- mode tabs: Purchase · Upgrade · Reserve ---
    val tabs = addTag(eq, "div")
    tabs.id = "eqModeTabs"
    listOf("purchase" to "Purchase", "upgrade" to "Upgrade", "reserve" to "Reserve").forEach { (mode, label) ->
        val tab = addTag(tabs, "div")
        tab.id = "eqModeTab-$mode"
        tab.className = "osada-eq-tab"
        tab.textContent = label
        tab.title =
            when (mode) {
                "purchase" -> "Purchase — browse equipment and buy a new unit for prestige. New units enter the reserve tray."
                "upgrade" -> "Upgrade — select one of your units, choose a compatible model, and pay the price difference."
                else -> "Reserve — select purchased but undeployed units and place them on highlighted deployment hexes."
            }
        tab.onclick = { _: org.w3c.dom.events.MouseEvent -> setEquipmentMode(mode) }
    }
}

/**
 * Lets the wheel scroll a row that only scrolls horizontally.
 *
 * A browser sends a plain wheel gesture as `deltaY`, and an element whose overflow is `auto` on X
 * but `hidden` on Y consumes none of it — so the class-tab row was unscrollable by wheel even once
 * it genuinely overflowed. Chrome only maps wheel→horizontal on its own when Shift is held. Trackpad
 * and touch swipes already produce `deltaX` and are left alone; the fallback to `deltaY` is what
 * makes a mouse work.
 */
private fun attachHorizontalWheelScrolling(row: HTMLElement) {
    // Marks which ends are fully scrolled so the CSS edge fade can be dropped there. At either
    // extreme the fade has nothing left to hint at, and it dimmed the outer border of the first
    // ("All") and last ("Fortification") tabs, which reads as clipping rather than as scrollable.
    // The 1px tolerance absorbs the fractional scrollLeft a zoomed or fractional-DPI layout
    // produces -- without it the end flag never sets and the right-hand fade never clears.
    val syncEdgeFlags = {
        val maxScroll = row.scrollWidth - row.clientWidth
        // The row is built while #equipment is still display:none, where both widths read 0. Treat
        // that as "there is more to the right" rather than as fully-scrolled, or the very first
        // open shows no right-edge hint at all -- the mirror of the bug this whole flag fixes.
        val laidOut = row.clientWidth > 0
        row.setAttribute("data-scroll-start", (row.scrollLeft <= 1.0).toString())
        row.setAttribute(
            "data-scroll-end",
            (laidOut && (maxScroll <= 0 || row.scrollLeft >= maxScroll - 1.0)).toString(),
        )
    }
    row.addEventListener("wheel", { event ->
        val wheel = event.asDynamic()
        val delta = (wheel.deltaX as? Double)?.takeIf { it != 0.0 } ?: (wheel.deltaY as? Double ?: 0.0)
        if (delta != 0.0 && row.scrollWidth > row.clientWidth) {
            row.scrollLeft += delta
            event.preventDefault()
        }
        syncEdgeFlags()
    })
    row.addEventListener("scroll", { syncEdgeFlags() })
    syncEdgeFlags()
}

private fun EquipmentWindowBuilder.buildEqClassTabsRow(eq: HTMLElement) {
    // --- class tabs + tools (country, sort order, sort property) ---
    val classRow = addTag(eq, "div")
    classRow.id = "eqClassTabs"
    moveInto("eqSelClass", classRow)
    byId("eqSelClass")?.let(::attachHorizontalWheelScrolling)
    val tools = addTag(classRow, "div")
    tools.id = "eqClassTools"
    moveInto("eqSelCountryButton", tools) // kept in DOM, hidden by CSS (replaced by the dropdown)
    buildCountrySelect(tools)
    moveInto("eqSortOrderBut", tools)
    buildSortSelect(tools)
}

private fun EquipmentWindowBuilder.buildEqListPane(eq: HTMLElement) {
    // --- list pane: unit strip (upgrade/reserve) + equipment/transport lists ---
    val listPane = addTag(eq, "div")
    listPane.id = "eqListPane"
    val reservePane = addTag(listPane, "div")
    reservePane.id = "eqReservePane"
    val reserveHint = addTag(reservePane, "div")
    reserveHint.id = "eqReserveHint"
    reserveHint.textContent =
        "Pick a unit, then click a highlighted deployment hex on the map. " +
        "The window reopens until every reserve is placed."
    val upgradeHint = addTag(reservePane, "div")
    upgradeHint.id = "eqUpgradeHint"
    upgradeHint.textContent = I18n.t("equipment.upgrade.hint")
    val reserveEmpty = addTag(reservePane, "div")
    reserveEmpty.id = "eqReserveEmpty"
    reserveEmpty.textContent = I18n.t("equipment.reserve.empty")
    moveInto("container-unitlist", reservePane)
    moveInto("hscroll-eqUnitList", listPane)
    moveInto("hscroll-eqTransportList", listPane)
}

private fun EquipmentWindowBuilder.buildEqDetailPane(eq: HTMLElement) {
    // --- detail column: selected unit record + primary action ---
    val detail = addTag(eq, "div")
    detail.id = "eqDetailPane"
    val detailBody = addTag(detail, "div")
    detailBody.id = "eqDetailBody"
    val actions = addTag(detail, "div")
    actions.id = "eqDetailActions"
    val buyRow = addTag(actions, "div")
    buyRow.className = "osada-eqd-actionrow"
    moveInto("eqNewBut", buyRow)
    moveInto("eqNewCost", buyRow)
    moveInto("eqNewText", buyRow)
    val upgradeRow = addTag(actions, "div")
    upgradeRow.className = "osada-eqd-actionrow"
    moveInto("eqUpgradeBut", upgradeRow)
    moveInto("eqUpgradeCost", upgradeRow)
    moveInto("eqUpgradeText", upgradeRow)
}

private fun EquipmentWindowBuilder.buildEqFooter(eq: HTMLElement) {
    // --- footer: secondary (sell/disband) ---
    val footer = addTag(eq, "div")
    footer.id = "eqFooter"
    moveInto("eqSellBut", footer)
    moveInto("eqSellCost", footer)
    moveInto("eqSellText", footer)
}

/** Country selector for sides with support countries (e.g. Germany + Romania). Populated by
 *  EquipmentWindowController.syncCountrySelect; hidden when the side has a single country. */
private fun EquipmentWindowBuilder.buildCountrySelect(parent: HTMLElement) {
    val select = addTag(parent, "select")
    select.id = "osadaEqCountry"
    select.title =
        "Filter the catalogue by equipment country. Campaign purchases may be restricted to the campaign nation."
    select.style.display = "none"
    select.asDynamic().onchange = {
        // The option's own VALUE (-1 = "All Countries", 0..N-1 = country), not .selectedIndex
        // (a DOM position — "All" sits at position 0 ahead of the real countries, so position
        // and value only agree for "All"; everything else is off by one against it).
        val idx = (select.asDynamic().value as? String)?.toIntOrNull() ?: -1
        // Same state changes as the legacy "changecountry" action, minus the blind cycling.
        byId("eqSelCountry")?.asDynamic()?.country = idx
        val userSel = byId("eqUserSel")?.asDynamic()
        userSel?.userunit = -1
        userSel?.equnit = -1
        // Was left stale here (only userunit/equnit reset) — a transport picked for a unit in
        // the PREVIOUS country stayed selected after switching country/to "All Countries",
        // which could resurface as an unfiltered transport list on the next render.
        userSel?.eqtransport = -1
        GameHolder.instance?.ui?.updateEquipmentWindow(userSel?.eqclass as? Int ?: UnitClass.TANK.value)
    }
}

/** Compact sort control in the class-tabs row — replaces the broken #eqSortOptions panel. */
private fun EquipmentWindowBuilder.buildSortSelect(parent: HTMLElement) {
    val select = addTag(parent, "select")
    select.id = "osadaEqSort"
    select.title = I18n.t("equipment.sort.help")
    addSelectOption(select, "Sort: Cost", "cost", true)
    UIBuilder.unitStats.forEach { stat ->
        val property = stat.property ?: return@forEach
        if (!stat.isSortable) return@forEach
        addSelectOption(select, "Sort: ${stat.title}", property, false)
    }
    select.asDynamic().onchange = {
        val userSel = byId("eqUserSel")?.asDynamic()
        val next = select.asDynamic().value as? String ?: "cost"
        if (next != (userSel?.sortproperty as? String ?: "cost")) {
            userSel?.sortproperty = next
            GameHolder.instance?.ui?.updateEquipmentWindow(userSel?.eqclass as? Int ?: UnitClass.TANK.value)
        }
    }
}
