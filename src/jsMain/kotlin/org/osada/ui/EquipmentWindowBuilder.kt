@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.GameHolder
import org.osada.UnitClass
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.UnitDescriptions
import org.osada.monthNamesShort
import org.osada.unitClassNames

/** Unit narrative description when loaded and non-blank, else null — shared by the equipment
 *  window's own detail bay and the unit card's uName/uImage hover tooltip. Units without a
 *  reviewed description (most non-Soviet equipment, for now) simply show no description. */
internal fun equipmentDescriptionOrNull(eq: EquipmentData): String? = UnitDescriptions.get(eq)

/** Explicitly says when an evocative equipment name has no extra rules beyond shown stats. */
internal fun equipmentMechanicsNote(eq: EquipmentData): String? =
    buildList {
        val capabilities = org.osada.rules.UnitCapabilities
        if (capabilities.isHeadquarters(eq)) add(I18n.t("equipment.mechanics.headquarters"))
        if (capabilities.hasPhasedMovement(eq)) add(I18n.t("equipment.mechanics.recon_movement"))
        if (capabilities.canOverrun(eq)) add(I18n.t("equipment.mechanics.tank_overrun"))
    }.takeIf { it.isNotEmpty() }?.joinToString(" ")

/** "Available from Mon YYYY" — shared so the equipment window's detail bay and the unit-card
 *  tooltip always read the same month name for the same eq.monthavailable (1-based; monthNamesShort
 *  is 0-based, hence the -1). */
internal fun equipmentAvailabilityText(eq: EquipmentData): String =
    "Available from ${monthNamesShort.getOrNull(eq.monthavailable - 1) ?: ""} ${eq.yearavailable}"

/** "All Countries" — the country dropdown's leading entry; see syncCountrySelect. */
private const val ALL_COUNTRIES = -1

/**
 * Which country the equipment window opens on, as an index into [UI.countriesOnSpotSide].
 *
 * A campaign may only BUY its own nation's equipment ([EquipmentCostsCalculator] resolveBuyCost),
 * so opening on "All Countries" leads the catalogue with support-country units the player cannot
 * purchase — e.g. the Soviet Red Army campaign opening on Spanish Republic militia at Seseña.
 * Preselect the campaign's own nation instead. Standalone scenarios have no campaign nation and
 * keep "All Countries", which is correct there: the whole side IS buyable.
 *
 * Falls back to "All Countries" when the campaign nation is absent from this side's list, or when
 * the game/UI isn't up yet — this also runs at window-build time, before a scenario is loaded.
 */
private fun defaultCountryIndex(): Int {
    val game = GameHolder.instance
    val campaignCountry = game?.campaign?.country
    val countries = game?.ui?.countriesOnSpotSide
    val index =
        if (campaignCountry != null && countries != null) countries.indexOf(campaignCountry) else -1
    return if (index >= 0) index else ALL_COUNTRIES
}

/**
 * Builds the equipment window (class selector, buy/upgrade/sell buttons, sort options) and
 * renders the cost row and the attack-info status bar. Extracted from the former `UIBuilder`
 * god-object; the equipment class list and stat metadata live on the [UIBuilder] facade.
 */
internal object EquipmentWindowBuilder {
    internal const val FLAG_SPRITE_WIDTH = 21
    internal const val NARROW_PRESTIGE_LABEL_WIDTH_THRESHOLD = 800

    fun setDefaultUserSelections() {
        val eqSelCountry = byId("eqSelCountry")
        eqSelCountry?.asDynamic()?.country = defaultCountryIndex()
        eqSelCountry?.asDynamic()?.owner = 0

        val eqUserSel = byId("eqUserSel")
        eqUserSel?.asDynamic()?.deployunit = -1
        eqUserSel?.asDynamic()?.deployformation = null
        eqUserSel?.asDynamic()?.deployrow = -1
        eqUserSel?.asDynamic()?.deploycol = -1
        eqUserSel?.asDynamic()?.userunit = -1
        eqUserSel?.asDynamic()?.equnit = -1
        eqUserSel?.asDynamic()?.eqtransport = -1
        eqUserSel?.asDynamic()?.sortorder = 0
        eqUserSel?.asDynamic()?.sortproperty = "cost"
    }

    fun buildEquipmentWindow() {
        setDefaultUserSelections()
        wireSortButtons()
        buildClassTabs()
        wireEquipmentActionButtons()
        restructureEquipmentWindow()
    }

    private fun wireSortButtons() {
        val eqSortOrderBut = byId("eqSortOrderBut")
        eqSortOrderBut?.title = I18n.t("equipment.sort.reverse.help")
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
        eqSortOptionsBut?.title = I18n.t("equipment.sort.choose.help")
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
    }

    private fun buildClassTabs() {
        UIBuilder.eqClassButtons.forEach { (eqClass, data) ->
            val div = addTag("eqSelClass", "div")
            div.className = "smallButtonSubMenu"
            // Name the classes merged into this tab (UIBuilder.eqClassTabGroups) so the player can
            // see that e.g. Flak lives under Air defence without having to click through.
            val merged =
                UIBuilder.eqClassTabGroups[eqClass]
                    ?.joinToString(", ") { unitClassNames[it.value] }
            val mergedNote = if (merged.isNullOrEmpty()) "" else " (incl. $merged)"
            div.title = "${data.second}$mergedNote"
            div.id = "eqclass-$eqClass"
            div.asDynamic().eqclass = eqClass
            // Glyph + text label (a bare osada glyph is unreadable as a class tab). Tabs with no
            // known glyph get no span at all rather than an empty one, so the flex gap doesn't
            // leave them visibly indented against their neighbours.
            if (data.first.isNotEmpty()) {
                val glyphSpan = addTag(div, "span")
                glyphSpan.className = "osada-eqtab__glyph"
                glyphSpan.innerHTML = data.first
            }
            val labelSpan = addTag(div, "span")
            labelSpan.className = "osada-eqtab__label"
            labelSpan.textContent = eqClassTabLabels[eqClass] ?: data.second
            div.onclick = { _: org.w3c.dom.events.MouseEvent -> onClassTabClick(eqClass) }
        }
    }

    /**
     * A class tab simply selects its class.
     *
     * Until 2026-07-26 "All" had no tab of its own and was reached by re-clicking the already
     * active tab — an undiscoverable gesture that also needed a defensive `lastClickedTab` field,
     * because `eqUserSel.eqclass` is written by several unrelated paths and so could already equal
     * the clicked tab before the player had ever clicked it. "All" is a real leftmost tab now, so
     * both the gesture and the field are gone.
     */
    private fun onClassTabClick(eqClass: String) {
        val userSel = byId("eqUserSel")?.asDynamic()
        val eqmode = userSel?.eqmode as? String ?: "purchase"
        // Reset the current selection so switching class jumps to (auto-selects) the
        // player's first own unit of that class in the Upgrade tab — the class tabs
        // then act as "switch between my units", not "show a mismatched catalogue".
        userSel?.userunit = -1
        userSel?.deployunit = -1
        userSel?.equnit = -1
        userSel?.eqtransport = -1
        userSel?.detailfocus = "unit"
        val clicked = eqClass.toIntOrNull() ?: UnitClass.TANK.value
        // "All" doesn't exist on the Upgrade tab — upgrades are class-locked by the rules, so
        // there is no "upgrade to any class". Fall back to the tab's own class there.
        val targetClass =
            if (clicked == UnitClass.NONE.value && eqmode == "upgrade") UnitClass.TANK.value else clicked
        GameHolder.instance?.ui?.updateEquipmentWindow(targetClass)
    }

    private fun wireEquipmentActionButtons() {
        val eqSelCountry2 = byId("eqSelCountry")
        eqSelCountry2?.title = I18n.t("equipment.country_cycle.help")
        eqSelCountry2?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("changecountry")
        }

        val eqNewBut = byId("eqNewBut")
        eqNewBut?.title =
            "Buy this equipment as a new unit. Prestige is spent now and the unit enters the reserve tray for deployment."
        eqNewBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("buy")
        }

        val eqUpgradeBut = byId("eqUpgradeBut")
        eqUpgradeBut?.title =
            "Upgrade the selected formation to this compatible model. You pay the cost difference and keep its identity, experience and hero."
        eqUpgradeBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("upgrade")
        }

        val eqSellBut = byId("eqSellBut")
        eqSellBut?.title = I18n.t("equipment.action.sell.help")
        eqSellBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            GameHolder.instance?.ui?.equipmentWindowButtons("sell")
        }

        val eqCloseBut = byId("eqCloseBut")
        eqCloseBut?.title = I18n.t("common.close_esc.help")
        eqCloseBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            // Hide only; deployMode is left as-is so a picked reserve unit can still be placed.
            hideEquipmentWindow()
        }
    }

    /** OSADA class-tab text labels shown next to the legacy glyphs. */
    private val eqClassTabLabels =
        mapOf(
            "0" to "All",
            "1" to "Infantry",
            "2" to "Tanks",
            "3" to "Recon",
            "4" to "Anti-tank",
            "6" to "Forts",
            "8" to "Artillery",
            "9" to "Air def",
            "10" to "Fighters",
            "11" to "Bombers",
            "15" to "Naval",
        )

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
        val player =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.currentPlayer
        val hasReserve = player?.hasUndeployedUnits() == true
        byId("eqReserveEmpty")?.style?.display = if (onReserveTab && !hasReserve) "block" else "none"
        if (onReserveTab) {
            byId("container-unitlist")?.style?.display = if (hasReserve) "block" else "none"
        }
        // During the deploy phase the upgrade strip lists the same reserve pool (upgrading
        // BEFORE deployment is legal and cheaper) — the hint must say so.
        byId("eqUpgradeHint")?.textContent =
            if (hasReserve) {
                "These are your reserve units — you can upgrade them before deploying " +
                    "(switch to the Reserve tab to place them)."
            } else {
                "Select your unit here, then pick the new model below and press Upgrade."
            }
    }

    fun buildEquipmentSortOptions() {
        val eqSortInfo = byId("eqSortInfo")
        eqSortInfo?.textContent = I18n.t("equipment.sort.prompt")

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
                eqSortInfo?.textContent =
                    I18n.t("equipment.sort.sorted_by", mapOf("stat" to stat.title))
                makeVisible("eqSortOptions")
            }
        }

        val eqSortCloseBut = byId("eqSortCloseBut")
        eqSortCloseBut?.title = I18n.t("equipment.sort.close.help")
        eqSortCloseBut?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("eqSortOptions")
            makeVisible("eqButtonsContainer")
        }
    }

    fun showEquipmentCosts(
        prestige: Int,
        buy: Int,
        upgrade: Int,
        sell: Int,
        buyBlockedReason: String? = null,
    ) {
        showBuyCost(prestige, buy, buyBlockedReason)
        showUpgradeCost(prestige, upgrade)
        showSellCost(sell)
        showCurrentPrestige(prestige)
    }

    fun showAttackInfo(
        attacker: GameUnit,
        defender: GameUnit,
    ) {
        val attackerData = attacker.unitData()
        val defenderData = defender.unitData()
        clearTag("statusmsg")
        val statusMsg = byId("statusmsg") ?: return

        val attackerFlag = addTag(statusMsg, "div")
        attackerFlag.className = "playerCountry"
        attackerFlag.style.marginTop = "0px"
        attackerFlag.style.backgroundPosition = "${-FLAG_SPRITE_WIDTH * (attacker.flag - 1)}px 0px"

        val attackerInfo = addTag(statusMsg, "div")
        attackerInfo.className = "combatInfoStatusBar"
        attackerInfo.innerHTML = "<b>${attackerData.name}</b> ${GameText.unitClass(attackerData.uclass)}"

        val vs = addTag(statusMsg, "div")
        vs.style.cssFloat = "left"
        vs.style.fontFamily = "osada"
        vs.style.fontSize = "18px"
        vs.innerHTML = "&nbsp!&nbsp;"

        val defenderFlag = addTag(statusMsg, "div")
        defenderFlag.className = "playerCountry"
        defenderFlag.style.marginTop = "0px"
        defenderFlag.style.backgroundPosition = "${-FLAG_SPRITE_WIDTH * (defender.flag - 1)}px 0px"

        val defenderInfo = addTag(statusMsg, "div")
        defenderInfo.className = "combatInfoStatusBar"
        defenderInfo.innerHTML = "<b>${defenderData.name}</b> ${GameText.unitClass(defenderData.uclass)}"
    }
}
