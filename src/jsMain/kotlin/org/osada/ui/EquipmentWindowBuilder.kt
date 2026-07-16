package org.osada.ui

import kotlinx.browser.window
import org.osada.*
import org.osada.model.*
import org.w3c.dom.HTMLElement

/** Unit narrative description when loaded and non-blank, else null — shared by the equipment
 *  window's own detail bay and the unit card's uName/uImage hover tooltip. Units without a
 *  reviewed description (most non-Soviet equipment, for now) simply show no description. */
internal fun equipmentDescriptionOrNull(eq: EquipmentData): String? =
    UnitDescriptions.get(eq.name)

/** "Available from Mon YYYY" — shared so the equipment window's detail bay and the unit-card
 *  tooltip always read the same month name for the same eq.monthavailable (1-based; monthNamesShort
 *  is 0-based, hence the -1). */
internal fun equipmentAvailabilityText(eq: EquipmentData): String =
    "Available from ${monthNamesShort.getOrNull(eq.monthavailable - 1) ?: ""} ${eq.yearavailable}"

/**
 * Builds the equipment window (class selector, buy/upgrade/sell buttons, sort options) and
 * renders the cost row and the attack-info status bar. Extracted from the former `UIBuilder`
 * god-object; the equipment class list and stat metadata live on the [UIBuilder] facade.
 */
internal object EquipmentWindowBuilder {

    fun setDefaultUserSelections() {
        val eqSelCountry = byId("eqSelCountry")
        // -1 = "All Countries" (see syncCountrySelect): the default whenever the side has support
        // countries, so opening Reserves as e.g. Axis shows every buyable unit up front instead of
        // just the first country in the list.
        eqSelCountry?.asDynamic()?.country = -1
        eqSelCountry?.asDynamic()?.owner = 0

        val eqUserSel = byId("eqUserSel")
        eqUserSel?.asDynamic()?.deployunit = -1
        eqUserSel?.asDynamic()?.userunit = -1
        eqUserSel?.asDynamic()?.equnit = -1
        eqUserSel?.asDynamic()?.eqtransport = -1
        eqUserSel?.asDynamic()?.sortorder = 0
        eqUserSel?.asDynamic()?.sortproperty = "cost"
    }

    fun buildEquipmentWindow() {
        setDefaultUserSelections()

        val eqSortOrderBut = byId("eqSortOrderBut")
        eqSortOrderBut?.title = "Click to change sorting order ascending/descenting"
        eqSortOrderBut?.asDynamic()?.hasSelectedGlyph = true
        eqSortOrderBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val userSel = byId("eqUserSel")?.asDynamic()
            val currentOrder = userSel?.sortorder as? Int ?: 0
            val newOrder = currentOrder.inv() and 1
            userSel?.sortorder = newOrder
            GameHolder.instance?.ui?.updateEquipmentWindow(userSel?.eqclass as? Int ?: UnitClass.TANK.value)
            toggleButton(eqSortOrderBut, newOrder == 1)
        }

        val eqSortOptionsBut = byId("eqSortOptionsBut")
        eqSortOptionsBut?.title = "Click to change sort category"
        eqSortOptionsBut?.asDynamic()?.hasSelectedGlyph = true
        eqSortOptionsBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            if (isVisible("eqSortOptions")) {
                makeHidden("eqSortOptions")
                makeVisible("eqButtonsContainer")
                toggleButton(eqSortOptionsBut, false)
            } else {
                makeHidden("eqButtonsContainer")
                makeVisible("eqSortOptions")
                toggleButton(eqSortOptionsBut, true)
            }
        }

        UIBuilder.eqClassButtons.forEach { (eqClass, data) ->
            val div = addTag("eqSelClass", "div")
            div.className = "smallButtonSubMenu"
            div.title = data.second
            div.id = "eqclass-$eqClass"
            div.asDynamic().eqclass = eqClass
            // Glyph + text label (a bare openpanzer glyph is unreadable as a class tab).
            val glyphSpan = addTag(div, "span")
            glyphSpan.className = "osada-eqtab__glyph"
            glyphSpan.innerHTML = data.first
            val labelSpan = addTag(div, "span")
            labelSpan.className = "osada-eqtab__label"
            labelSpan.textContent = eqClassTabLabels[eqClass] ?: data.second
            div.onclick = { _: org.w3c.dom.events.MouseEvent ->
                val userSel = byId("eqUserSel")?.asDynamic()
                val eqmode = userSel?.eqmode as? String ?: "purchase"
                // Deliberately NOT compared against eqUserSel.eqclass: that field gets set by
                // MANY unrelated code paths (selecting a unit in the reserve strip, switching
                // mode tabs, country change...), so it can already equal this tab's class before
                // the player ever clicked it — which made the FIRST click on, say, Tanks (the
                // default-selected class at window build) misfire as a "re-click" and jump
                // straight to All. lastClickedTab is written ONLY by this handler, so it truly
                // means "the player's last class-tab click was this one."
                val alreadyActive = (userSel?.lastClickedTab as? String) == eqClass
                // Reset the current selection so switching class jumps to (auto-selects) the
                // player's first own unit of that class in the Upgrade tab — the class tabs
                // then act as "switch between my units", not "show a mismatched catalogue".
                userSel?.userunit = -1
                userSel?.deployunit = -1
                userSel?.equnit = -1
                userSel?.eqtransport = -1
                userSel?.detailfocus = "unit"
                // Re-clicking the already-active tab shows every class ("All" — no visible tab
                // for it anymore, user feedback said it crooked/condensed the other 8; this
                // hidden toggle keeps the ability to browse everything at once without one).
                // Except on the Upgrade tab, where "All" doesn't exist (upgrades are class-locked
                // by the rules) — a re-click there is just a no-op re-show of the same class.
                val targetClass = if (alreadyActive && eqmode != "upgrade") UnitClass.NONE.value
                    else (eqClass.toIntOrNull() ?: UnitClass.TANK.value)
                userSel?.lastClickedTab = if (targetClass == UnitClass.NONE.value) null else eqClass
                GameHolder.instance?.ui?.updateEquipmentWindow(targetClass)
            }
        }

        val eqSelCountry2 = byId("eqSelCountry")
        eqSelCountry2?.title = "Click to change country"
        eqSelCountry2?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("changecountry")
        }

        val eqNewBut = byId("eqNewBut")
        eqNewBut?.title = "Buy unit as a new unit"
        eqNewBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("buy")
        }

        val eqUpgradeBut = byId("eqUpgradeBut")
        eqUpgradeBut?.title = "Upgrade selected unit to this unit"
        eqUpgradeBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("upgrade")
        }

        val eqSellBut = byId("eqSellBut")
        eqSellBut?.title = "Disband and sell this unit"
        eqSellBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("sell")
        }

        val eqCloseBut = byId("eqCloseBut")
        eqCloseBut?.title = "Close (Esc)"
        eqCloseBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            // Hide only; deployMode is left as-is so a picked reserve unit can still be placed.
            makeHidden("equipment")
        }

        restructureEquipmentWindow()
    }

    /** OSADA class-tab text labels shown next to the legacy glyphs. */
    private val eqClassTabLabels = mapOf(
        "1" to "Infantry", "2" to "Tanks", "3" to "Recon", "4" to "Anti-tank",
        "8" to "Artillery", "9" to "Air def", "10" to "Fighters", "11" to "Bombers"
    )

    /**
     * Task 0a: rebuilds #equipment as ONE CSS grid with named areas
     * header / mode-tabs / class-tabs / list / detail / footer.
     * Existing nodes are REPARENTED (ids + handlers intact) into the new area
     * containers; the legacy float containers stay in the DOM but end up empty and
     * hidden by CSS. Idempotent — safe if buildEquipmentWindow ever runs twice.
     */
    private fun restructureEquipmentWindow() {
        val eq = byId("equipment") ?: return
        if (byId("eqGridHeader") != null) return

        fun move(id: String, into: HTMLElement) { byId(id)?.let { into.appendChild(it) } }

        // --- header: title · prestige (always visible) · fixed-size close ---
        val header = addTag(eq, "div")
        header.id = "eqGridHeader"
        move("eqInfoText", header)
        val prestigeWrap = addTag(header, "div")
        prestigeWrap.id = "eqPrestigeWrap"
        prestigeWrap.title = "Available prestige"
        move("currentPrestige", prestigeWrap)
        move("currentPrestigeAmount", prestigeWrap)
        move("eqCloseBut", header)

        // --- mode tabs: Purchase · Upgrade · Reserve ---
        val tabs = addTag(eq, "div")
        tabs.id = "eqModeTabs"
        listOf("purchase" to "Purchase", "upgrade" to "Upgrade", "reserve" to "Reserve").forEach { (mode, label) ->
            val tab = addTag(tabs, "div")
            tab.id = "eqModeTab-$mode"
            tab.className = "osada-eq-tab"
            tab.textContent = label
            tab.onclick = { _: org.w3c.dom.events.MouseEvent -> setEquipmentMode(mode) }
        }

        // --- class tabs + tools (country, sort order, sort property) ---
        val classRow = addTag(eq, "div")
        classRow.id = "eqClassTabs"
        move("eqSelClass", classRow)
        val tools = addTag(classRow, "div")
        tools.id = "eqClassTools"
        move("eqSelCountryButton", tools) // kept in DOM, hidden by CSS (replaced by the dropdown)
        buildCountrySelect(tools)
        move("eqSortOrderBut", tools)
        buildSortSelect(tools)

        // --- list pane: unit strip (upgrade/reserve) + equipment/transport lists ---
        val listPane = addTag(eq, "div")
        listPane.id = "eqListPane"
        val reservePane = addTag(listPane, "div")
        reservePane.id = "eqReservePane"
        val reserveHint = addTag(reservePane, "div")
        reserveHint.id = "eqReserveHint"
        reserveHint.textContent =
            "Pick a unit, then click a highlighted deployment hex on the map. The window reopens until every reserve is placed."
        val upgradeHint = addTag(reservePane, "div")
        upgradeHint.id = "eqUpgradeHint"
        upgradeHint.textContent = "Select your unit here, then pick the new model below and press Upgrade."
        val reserveEmpty = addTag(reservePane, "div")
        reserveEmpty.id = "eqReserveEmpty"
        reserveEmpty.textContent = "No purchased units awaiting deployment."
        move("container-unitlist", reservePane)
        move("hscroll-eqUnitList", listPane)
        move("hscroll-eqTransportList", listPane)

        // --- detail column: selected unit record + primary action ---
        val detail = addTag(eq, "div")
        detail.id = "eqDetailPane"
        val detailBody = addTag(detail, "div")
        detailBody.id = "eqDetailBody"
        val actions = addTag(detail, "div")
        actions.id = "eqDetailActions"
        val buyRow = addTag(actions, "div")
        buyRow.className = "osada-eqd-actionrow"
        move("eqNewBut", buyRow); move("eqNewCost", buyRow); move("eqNewText", buyRow)
        val upgradeRow = addTag(actions, "div")
        upgradeRow.className = "osada-eqd-actionrow"
        move("eqUpgradeBut", upgradeRow); move("eqUpgradeCost", upgradeRow); move("eqUpgradeText", upgradeRow)

        // --- footer: secondary (sell/disband) ---
        val footer = addTag(eq, "div")
        footer.id = "eqFooter"
        move("eqSellBut", footer); move("eqSellCost", footer); move("eqSellText", footer)

        renderEquipmentDetail(null)
        setEquipmentMode("purchase")
        // Esc handling for this window is centralized in MenuController.handleGlobalEscape
        // (a single document-level listener; a second one here would double-fire on the same
        // keypress — e.g. closing equipment AND toggling the pause menu on one Escape tap).
    }

    /** Switches the window between purchase / upgrade / reserve. State lives as a CSS class on
     *  #equipment plus a property on #eqUserSel (the user-selection tracker). */
    fun setEquipmentMode(mode: String) {
        val eq = byId("equipment") ?: return
        byId("eqUserSel")?.asDynamic()?.eqmode = mode
        listOf("purchase", "upgrade", "reserve").forEach { m ->
            if (m == mode) eq.classList.add("osada-eq--$m") else eq.classList.remove("osada-eq--$m")
            byId("eqModeTab-$m")?.let {
                if (m == mode) it.setAttribute("selected", "on") else it.removeAttribute("selected")
            }
        }
        // The unit strip is display-toggled by legacy code paths (makeVisible/makeHidden);
        // entering a mode that shows it must undo a stale display:none.
        if (mode != "purchase") byId("container-unitlist")?.style?.display = "block"
        refreshReserveState()
        // BUG FIX: the unit-strip/catalogue filtering (updateEquipmentWindow) depends on eqmode
        // (only "upgrade" scopes the strip to the selected class), but switching mode TABS never
        // re-ran it — so right after a tab switch the strip kept showing whatever the PREVIOUS
        // mode last rendered (e.g. Purchase's unfiltered "all classes" list), until some other
        // click happened to trigger a refresh. That's exactly "click a class -> filters; the tab
        // itself looked unfiltered". Force a refresh on every mode switch so it's never stale.
        val eqclass = byId("eqUserSel")?.asDynamic()?.eqclass as? Int ?: UnitClass.TANK.value
        GameHolder.instance?.ui?.updateEquipmentWindow(eqclass)
    }

    /** Reserve tab truthfulness: when nothing is awaiting deployment the unit strip holds the
     *  DEPLOYED units list (legacy dual use), which must not masquerade as a reserve. */
    fun refreshReserveState() {
        val onReserveTab = byId("equipment")?.classList?.contains("osada-eq--reserve") == true
        val player = GameHolder.instance?.scenario?.map?.currentPlayer
        val hasReserve = player?.hasUndeployedUnits() == true
        byId("eqReserveEmpty")?.style?.display = if (onReserveTab && !hasReserve) "block" else "none"
        if (onReserveTab) {
            byId("container-unitlist")?.style?.display = if (hasReserve) "block" else "none"
        }
        // During the deploy phase the upgrade strip lists the same reserve pool (upgrading
        // BEFORE deployment is legal and cheaper) — the hint must say so.
        byId("eqUpgradeHint")?.textContent = if (hasReserve)
            "These are your reserve units — you can upgrade them before deploying (switch to the Reserve tab to place them)."
        else
            "Select your unit here, then pick the new model below and press Upgrade."
    }

    /** Fills the right detail column for the selected equipment entry. */
    fun renderEquipmentDetail(eq: EquipmentData?) {
        val body = byId("eqDetailBody") ?: return
        clearTag(body)
        if (eq == null) {
            val empty = addTag(body, "div")
            empty.className = "osada-eqd-empty"
            empty.textContent = "Select a unit from the list to see its record."
            return
        }
        val portrait = addTag(body, "div")
        portrait.className = "osada-eqd-portrait"
        val img = addTag(portrait, "div")
        img.className = "osada-eqd-portrait__img"
        img.style.backgroundImage = "url(${eq.icon})"
        val name = addTag(body, "div")
        name.className = "osada-eqd-name"
        // Country flag left of the name (user request): same flags_med.png sprite + 0-based
        // slot convention the start-menu list rows use (eq.country is 1-based, hence -1).
        if (eq.country > 0) {
            val flag = addTag(name, "span")
            flag.className = "osadaFlag osada-eqd-flag"
            flag.style.backgroundImage = "url('resources/ui/flags/${Equipment.unitedName}/flags_med.png')"
            flag.style.backgroundPosition = "${-21 * (eq.country - 1)}px 0px"
            flag.title = Equipment.getCountryName(eq.country - 1)
        }
        val nameText = addTag(name, "span")
        nameText.textContent = eq.name
        val cls = addTag(body, "div")
        cls.className = "osada-eqd-class"
        cls.textContent = "${unitClassNames.getOrNull(eq.uclass) ?: ""} · ${Equipment.getCountryName(eq.country - 1)}"
        val avail = addTag(body, "div")
        avail.className = "osada-eqd-avail"
        avail.textContent = equipmentAvailabilityText(eq)
        val cost = addTag(body, "div")
        cost.className = "osada-eqd-cost"
        cost.innerHTML = "${eq.cost * CURRENCY_MULTIPLIER}${UIBuilder.currencyIcon}"
        val grid = addTag(body, "div")
        grid.className = "osada-eqd-stats"
        fun stat(label: String, value: Any, help: String) {
            val row = addTag(grid, "div")
            row.className = "osada-eqd-stat"
            row.title = help
            row.innerHTML = "<b>$label</b><span>$value</span>"
        }
        stat("Soft attack", eq.softatk, "Attack power vs soft targets — infantry, artillery, unarmoured vehicles.")
        stat("Hard attack", eq.hardatk, "Attack power vs hard targets — tanks and other armoured vehicles.")
        stat("Air attack", eq.airatk, "Attack power vs aircraft.")
        stat("Naval attack", eq.navalatk, "Attack power vs ships — used when firing on naval targets (e.g. coastal guns, or infantry engaging a landing craft).")
        stat("Ground def", eq.grounddef, "Defence when attacked by a ground unit.")
        stat("Air def", eq.airdef, "Defence when attacked from the air.")
        stat("Close def", eq.closedef, "Defence in close combat — when an adjacent enemy attacks at melee range, as opposed to ranged fire.")
        stat("Initiative", eq.initiative, "Higher initiative strikes first in combat, often before the enemy can return fire.")
        stat("Movement", eq.movpoints, "Movement points per turn.")
        stat("Movement type", movMethodNames.getOrNull(eq.movmethod) ?: "Unknown", "How this unit moves — determines terrain cost, and whether it needs a road or (for Rail) is confined to the rail network.")
        stat("Spotting", eq.spotrange, "How many hexes away this unit reveals hidden enemies.")
        stat("Range", if (eq.gunrange == 0) 1 else eq.gunrange, "Firing range in hexes (1 = must be adjacent).")
        stat("Ammo", eq.ammo, "Rounds of ammunition before the unit must resupply.")
        if (eq.fuel > 0) stat("Fuel", eq.fuel, "Fuel available before the unit must resupply.")
        // Narrative hook: only rendered when a reviewed description exists for this unit.
        val description = equipmentDescriptionOrNull(eq)
        if (description != null) {
            val desc = addTag(body, "div")
            desc.className = "osada-eqd-desc"
            desc.textContent = description
        }
    }

    /** Country selector for sides with support countries (e.g. Germany + Romania). Populated by
     *  EquipmentWindowController.syncCountrySelect; hidden when the side has a single country. */
    private fun buildCountrySelect(parent: HTMLElement) {
        val select = addTag(parent, "select")
        select.id = "osadaEqCountry"
        select.title = "Equipment country"
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
    private fun buildSortSelect(parent: HTMLElement) {
        val select = addTag(parent, "select")
        select.id = "osadaEqSort"
        select.title = "Sort equipment by"
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

    fun buildEquipmentSortOptions() {
        val eqSortInfo = byId("eqSortInfo")
        eqSortInfo?.innerHTML = "Sort equipment by: "

        UIBuilder.unitStats.forEach { stat ->
            val property = stat.property ?: return@forEach
            if (!stat.isSortable) return@forEach
            val glyph = stat.glyph ?: return@forEach

            val div = addTag("eqSortOptionsContainer", "div")
            div.title = stat.title
            div.id = "eqsort-$property"
            div.className = "smallButtonSubMenu"
            div.style.marginRight = "0px"
            div.asDynamic().sortproperty = property
            div.innerHTML = glyph
            div.onclick = { _: org.w3c.dom.events.MouseEvent ->
                makeHidden("eqSortOptions")
                val userSel = byId("eqUserSel")?.asDynamic()
                val currentProperty = userSel?.sortproperty as? String ?: "cost"
                toggleButton(byId("eqsort-$currentProperty"), false)
                userSel?.sortproperty = property
                toggleButton(div, true)
                GameHolder.instance?.ui?.updateEquipmentWindow(userSel?.eqclass as? Int ?: UnitClass.TANK.value)
                eqSortInfo?.innerHTML = "Sorted by: ${stat.title}"
                makeVisible("eqSortOptions")
            }
        }

        val eqSortCloseBut = byId("eqSortCloseBut")
        eqSortCloseBut?.title = "Close sorting options"
        eqSortCloseBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("eqSortOptions")
            makeVisible("eqButtonsContainer")
        }
    }

    fun showEquipmentCosts(prestige: Int, buy: Int, upgrade: Int, sell: Int) {
        val eqNewText = byId("eqNewText")
        val eqNewCost = byId("eqNewCost")
        val eqNewBut = byId("eqNewBut")
        if (buy > 0 && buy <= prestige) {
            eqNewText?.innerHTML = "Buy "
            eqNewCost?.innerHTML = "$buy${UIBuilder.currencyIcon}"
            eqNewBut?.style?.display = "inline-block"
        } else {
            if (buy > prestige) {
                val diff = buy - prestige
                eqNewText?.innerHTML = "<span style='color:#BB7575'>Need $diff more prestige to buy.</span>"
            } else {
                eqNewText?.textContent = ""
            }
            eqNewBut?.style?.display = "none"
            eqNewCost?.textContent = ""
        }

        val eqUpgradeText = byId("eqUpgradeText")
        val eqUpgradeCost = byId("eqUpgradeCost")
        val eqUpgradeBut = byId("eqUpgradeBut")
        if (upgrade > 0 && upgrade <= prestige) {
            eqUpgradeText?.innerHTML = " Upgrade "
            eqUpgradeCost?.innerHTML = "$upgrade${UIBuilder.currencyIcon}"
            eqUpgradeBut?.style?.display = "inline-block"
        } else {
            if (upgrade > prestige) {
                val diff = upgrade - prestige
                eqUpgradeText?.innerHTML = "<span style='color:#BB7575'>Need $diff more prestige to upgrade.</span>"
            } else {
                eqUpgradeText?.textContent = ""
            }
            eqUpgradeBut?.style?.display = "none"
            eqUpgradeCost?.textContent = ""
        }

        val eqSellText = byId("eqSellText")
        val eqSellCost = byId("eqSellCost")
        val eqSellBut = byId("eqSellBut")
        if (sell > 0) {
            eqSellText?.innerHTML = " Sell "
            eqSellCost?.innerHTML = "$sell${UIBuilder.currencyIcon}"
            eqSellBut?.style?.display = "inline-block"
        } else {
            eqSellBut?.style?.display = "none"
            eqSellCost?.textContent = ""
            eqSellText?.textContent = ""
        }

        val currentPrestige = byId("currentPrestige")
        currentPrestige?.textContent = if (window.innerWidth >= 800) "Available Prestige: " else "Now: "
        val currentPrestigeAmount = byId("currentPrestigeAmount")
        currentPrestigeAmount?.innerHTML = "$prestige${UIBuilder.currencyIcon}"
    }

    fun showAttackInfo(attacker: GameUnit, defender: GameUnit) {
        val attackerData = attacker.unitData()
        val defenderData = defender.unitData()
        clearTag("statusmsg")
        val statusMsg = byId("statusmsg") ?: return

        val attackerFlag = addTag(statusMsg, "div")
        attackerFlag.className = "playerCountry"
        attackerFlag.style.marginTop = "0px"
        attackerFlag.style.backgroundPosition = "${-21 * (attacker.flag - 1)}px 0px"

        val attackerInfo = addTag(statusMsg, "div")
        attackerInfo.className = "combatInfoStatusBar"
        attackerInfo.innerHTML = "<b>${attackerData.name}</b> ${unitClassNames[attackerData.uclass]}"

        val vs = addTag(statusMsg, "div")
        vs.style.cssFloat = "left"
        vs.style.fontFamily = "openpanzer"
        vs.style.fontSize = "18px"
        vs.innerHTML = "&nbsp!&nbsp;"

        val defenderFlag = addTag(statusMsg, "div")
        defenderFlag.className = "playerCountry"
        defenderFlag.style.marginTop = "0px"
        defenderFlag.style.backgroundPosition = "${-21 * (defender.flag - 1)}px 0px"

        val defenderInfo = addTag(statusMsg, "div")
        defenderInfo.className = "combatInfoStatusBar"
        defenderInfo.innerHTML = "<b>${defenderData.name}</b> ${unitClassNames[defenderData.uclass]}"
    }
}
