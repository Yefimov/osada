package org.osada.ui

import kotlinx.browser.document
import org.osada.CURRENCY_MULTIPLIER
import org.osada.MovMethod
import org.osada.UNIT_NAME_MAX_LENGTH
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.isAvailableIn
import org.osada.rules.GameRules
import org.osada.uiSettings
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Manages the equipment purchase/upgrade/sell window and the deployed-unit list: populates
 * both scrolling lists, handles buy/upgrade/sell button actions, and recomputes cost badges.
 * Extracted from the former [UI] god-class (SRP).
 */
internal class EquipmentWindowController(private val ui: UI) {

    fun toggleUnitsAndEquipmentWindow(show: Boolean) {
        if (show) {
            makeVisible("container-unitlist")
            // Inline grid (not makeVisible's display:inline) so the area layout applies.
            byId("equipment")?.style?.display = "grid"
            EquipmentWindowBuilder.setEquipmentMode("reserve")
            uiSettings.deployMode = true
            AttackRingBuilder.clear() // rings clear while any modal window is open (spec)
        } else {
            makeHidden("container-unitlist")
            makeHidden("equipment")
            uiSettings.deployMode = false
            ui.hideUnitInfoIfNotPinned()
            // Restore the normal turn status line (updateEquipmentWindow overwrote it with the
            // "Units currently deployed on map." / deploy message). Mirrors PM's z() calling y().
            ui.updateStatusBar()
        }
    }

    fun handleReinforcementDeployment() {
        val map = ui.game.scenario?.map ?: return
        val player = map.currentPlayer ?: return
        if (player.hasUndeployedUnits()) {
            makeVisible("container-unitlist")
            updateEquipmentWindow(UnitClass.TANK.value)
        }
    }

    fun equipmentWindowButtons(action: String) {
        val map = ui.game.scenario?.map ?: return
        val player = map.currentPlayer ?: return
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return
        when (action) {
            "changecountry" -> {
                val countryEl = byId("eqSelCountry") ?: return
                val current = countryEl.asDynamic().country as? Int ?: 0
                val next = if (current >= ui.countriesOnSpotSide.size - 1) 0 else current + 1
                countryEl.asDynamic().country = next
                eqUserSel.userunit = -1
                eqUserSel.equnit = -1
                updateEquipmentWindow(eqUserSel.eqclass as? Int ?: UnitClass.TANK.value)
            }
            "buy" -> {
                val equnit = eqUserSel.equnit as? Int ?: -1
                var transport = eqUserSel.eqtransport as? Int ?: -1
                if (equnit <= 0) return
                if (transport < 0) transport = -1
                player.buyUnit(equnit, transport)
                Equipment.getEquipment(equnit)?.let { updateEquipmentWindow(it.uclass) }
                // Refreshes the Reserves button's undeployed-count badge — buying grows the
                // reserve pool, but nothing else on this path calls updateStatusBar (it's
                // normally driven by turn changes / window open-close), so without this the
                // badge silently stayed stale until some unrelated event happened to refresh it.
                ui.updateStatusBar()
            }
            "upgrade" -> {
                val userUnitId = eqUserSel.userunit as? Int ?: -1
                val deployUnitId = eqUserSel.deployunit as? Int ?: -1
                val equnit = eqUserSel.equnit as? Int ?: -1
                var transport = eqUserSel.eqtransport as? Int ?: -1
                if (transport < 0) transport = -1
                if (userUnitId == -1) {
                    val unit = player.getCoreUnitList().getOrNull(deployUnitId)
                    if (unit != null && player.upgradeUnit(unit, equnit, transport)) {
                        updateEquipmentWindow(unit.unitData(true).uclass)
                    }
                } else {
                    if (map.upgradeUnit(userUnitId, equnit, transport)) {
                        ui.render.cacheImages { ui.render.render() }
                        if (equnit > 0) Equipment.getEquipment(equnit)?.let { updateEquipmentWindow(it.uclass) }
                    }
                }
            }
            "sell" -> {
                val userUnitId = eqUserSel.userunit as? Int ?: -1
                val deployUnitId = eqUserSel.deployunit as? Int ?: -1
                val equnit = eqUserSel.equnit as? Int ?: -1
                if (userUnitId == -1) {
                    val unit = player.getCoreUnitList().getOrNull(deployUnitId)
                    if (unit != null && player.sellUnit(unit)) {
                        player.removeUndeployedCoreUnit(deployUnitId)
                        updateEquipmentWindow(unit.unitData(true).uclass)
                        ui.updateStatusBar() // reserve pool shrank — refresh the badge, see "buy" above
                    }
                } else {
                    if (map.disbandUnit(userUnitId)) {
                        ui.render.cacheImages { ui.render.render() }
                        eqUserSel.userunit = -1
                        if (equnit > 0) Equipment.getEquipment(equnit)?.let { updateEquipmentWindow(it.uclass) }
                    }
                }
            }
        }
    }

    fun updateEquipmentWindow(unitClass: Int) {
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val currentPlayer = map.currentPlayer ?: return
        if (currentPlayer.side != ui.game.spotSide) return

        clearTag("unitlist")
        clearTag("eqUnitList")
        clearTag("eqTransportList")

        val eqSelCountry = byId("eqSelCountry") ?: return
        val countryIndex = eqSelCountry.asDynamic().country as? Int ?: -1
        val allCountries = countryIndex == -1
        // 1-based OG codes (equipmentIndexes' own keying — see Equipment.kt / README's "player.country
        // = OGcode - 1" note) for every country to include: every support country on "All", else just
        // the selected one.
        val countryIds = if (allCountries) {
            ui.countriesOnSpotSide.map { it + 1 }
        } else {
            listOfNotNull(ui.countriesOnSpotSide.getOrNull(countryIndex)?.plus(1))
        }
        val countryId = countryIds.firstOrNull() ?: 1
        eqSelCountry.style.backgroundPosition = "${-21 * (countryId - 1)}px 0px"
        syncCountrySelect(countryIndex)

        val year = scenario.date.getFullYear()
        // 1-based (matches monthavailable/monthexpired's own OG convention), unlike JS's own
        // 0-based Date.getMonth().
        val month = scenario.date.getMonth() + 1
        val previousDeployMode = uiSettings.deployMode
        val unitList: List<GameUnit>
        var selectedUnitId = -1

        val coreList = currentPlayer.getCoreUnitList()
        if (currentPlayer.hasUndeployedUnits()) {
            selectedUnitId = (byId("eqUserSel")?.asDynamic()?.deployunit as? Int) ?: -1
            unitList = coreList.sortedWith(unitComparator(false))
            uiSettings.deployMode = true
            // NOTE: this used to also overwrite #statusmsg with a "Deploy on map grey hexes"
            // hint. #statusmsg is now the persistent top-bar scenario/turn/date line (Task 1),
            // so clobbering it here made it revert to stale deploy text every time a unit was
            // selected until the next full status refresh. The same instruction already lives
            // in the equipment window's own #eqReserveHint, and the top bar already shows a
            // DEPLOY phase chip, so nothing is lost by not writing it here too.
        } else {
            selectedUnitId = (byId("eqUserSel")?.asDynamic()?.userunit as? Int) ?: -1
            unitList = map.getUnits().sortedWith(unitComparator(true))
            uiSettings.deployMode = false
        }
        UIBuilder.setDeployOrCombatLogState(uiSettings.deployMode)
        if (previousDeployMode != uiSettings.deployMode) ui.render.render()

        val eqUserSel = byId("eqUserSel")?.asDynamic()
        val hscroll = byId("hscroll-unitlist")
        var scrollPos = 0

        // Upgrade tab: the class tabs scope the player's OWN unit strip (you can only upgrade a
        // unit to another of its class), so filter the strip to the selected class. This removes
        // the "Tanks tab shows tank offers while my infantry is selected" mismatch.
        val eqmode = eqUserSel?.eqmode as? String ?: "purchase"
        val filterClass = if (eqmode ==
            "upgrade"
        ) {
            normalizeUnitClass(unitClass.toString().toIntOrNull() ?: UnitClass.TANK.value)
        } else {
            -1
        }

        unitList.forEachIndexed { _, unit ->
            if (uiSettings.deployMode && unit.isDeployed) return@forEachIndexed
            if (unit.player?.id != currentPlayer.id) return@forEachIndexed
            if (filterClass != -1 &&
                normalizeUnitClass(unit.unitData(true).uclass) != filterClass
            ) {
                return@forEachIndexed
            }
            val item = buildUnitListItem(unit)
            if (!uiSettings.deployMode && selectedUnitId == -1) {
                selectedUnitId = unit.id
            }
            val deployIndex = if (uiSettings.deployMode) coreList.indexOf(unit) else unit.id
            if (uiSettings.deployMode &&
                (selectedUnitId == -1 || coreList.getOrNull(selectedUnitId)?.isDeployed == true)
            ) {
                eqUserSel?.deployunit = deployIndex
                selectedUnitId = deployIndex
            }
            item.asDynamic().unitid = deployIndex
            item.asDynamic().uniteqid = unit.eqid
            item.asDynamic().eqclass = unit.unitData(true).uclass
            item.asDynamic().country = unit.unitData(true).country - 1
            if (unit.isCore && item.asDynamic().unitid != selectedUnitId) {
                item.setAttribute("coreUnit", unit.unitData(true).name)
            }
            if (item.asDynamic().unitid == selectedUnitId) {
                if (uiSettings.deployMode) {
                    eqUserSel?.userunit = -1
                } else {
                    eqUserSel?.deployunit = -1
                    eqUserSel?.userunit = unit.id
                    ui.uiUnitSelect(unit)
                    unit.getPos()?.let { ui.uiSetCellOnViewPort(it) }
                }
                // Only adopt this unit's existing transport when the user hasn't already picked
                // one. PM (osada.js:6414) guards the same assignment: set it to the unit's
                // transport only if the unit HAS a transport, eqtransport is still -1, and the
                // selected equipment is transportable (or unset). Without the guard this ran on
                // every re-render and wiped the freshly-clicked transport back to -1, so no
                // transport could ever be selected for a purchase.
                val currentTransport = eqUserSel?.eqtransport as? Int ?: -1
                val currentEqunit = eqUserSel?.equnit as? Int ?: -1
                val existingTransportEqid = unit.transport?.eqid
                if (existingTransportEqid != null &&
                    currentTransport == -1 &&
                    !(currentEqunit != -1 && !GameRules.isTransportable(currentEqunit))
                ) {
                    eqUserSel?.eqtransport = existingTransportEqid
                }
                item.setAttribute("selectedUnit", unit.unitData(true).name)
                scrollPos = (hscroll?.asDynamic()?.offsetWidth as? Int ?: 0) / 2 - (item.offsetWidth / 2)
            }
            item.onclick = { _: org.w3c.dom.events.MouseEvent ->
                val clickedId = item.asDynamic().unitid as? Int
                clickedId?.let { id ->
                    if (uiSettings.deployMode) {
                        eqUserSel?.deployunit = id
                        eqUserSel?.userunit = -1
                    } else {
                        eqUserSel?.userunit = id
                        eqUserSel?.deployunit = -1
                    }
                    eqUserSel?.eqtransport = -1
                    eqUserSel?.equnit = item.asDynamic().uniteqid as? Int ?: -1
                    eqUserSel?.unitscroll = hscroll?.asDynamic()?.scrollLeft as? Int ?: 0
                    val selected = if (uiSettings.deployMode) {
                        currentPlayer.getCoreUnitList().getOrNull(id)
                    } else {
                        map.getUnitById(id)
                    }
                    selected?.let {
                        ui.showUnitInfo(it)
                        if (!uiSettings.deployMode) {
                            ui.uiUnitSelect(it)
                            it.getPos()?.let { pos -> ui.uiSetCellOnViewPort(pos) }
                        } else {
                            // Re-render so the airfield deploy highlight reflects whether the
                            // newly selected reserve unit is an aircraft (see MapRenderer).
                            ui.render.render()
                        }
                        updateEquipmentWindow(item.asDynamic().eqclass as? Int ?: UnitClass.TANK.value)
                    }
                    hscroll?.asDynamic()?.scrollLeft = eqUserSel?.unitscroll
                    // Close-on-pick is ONLY for the Reserve tab (the cursor then carries the unit
                    // to a deploy hex). On the Upgrade tab the same reserve unit is a chosen
                    // upgrade target, so the window must stay open to pick the new model.
                    val onReserveTab = byId("equipment")?.classList?.contains("osada-eq--reserve") == true
                    if (uiSettings.deployMode && onReserveTab) {
                        byId("equipment")?.style?.display = "none"
                    }
                }
            }
        }
        if (!uiSettings.deployMode) {
            hscroll?.asDynamic()?.scrollLeft = scrollPos
        }

        // unitClass may arrive as a JS string (class buttons pass their string key
        // via the dynamic js() call) or as an Int; normalise to Int so downstream
        // lookups and the stored eqclass value are always numeric.
        var selectedClass = unitClass.toString().toIntOrNull() ?: UnitClass.TANK.value
        if (selectedClass == UnitClass.FLAK.value) selectedClass = UnitClass.AIR_DEFENCE.value
        val isAll = selectedClass == UnitClass.NONE.value
        // "All" doesn't exist on the Upgrade tab (upgrades are class-locked by the rules — there's
        // no "upgrade to any class"); defensively clamp instead of trusting only the tab's own
        // click-handler guard, since eqclass can also arrive here via setEquipmentMode's re-fetch.
        if (isAll && eqmode == "upgrade") selectedClass = UnitClass.TANK.value
        if (!isAll &&
            !UIBuilder.eqClassButtons.containsKey(selectedClass.toString())
        ) {
            selectedClass = UnitClass.TANK.value
        }
        val previousKey = eqUserSel?.eqclass?.toString() ?: UnitClass.TANK.value.toString()
        if (UIBuilder.eqClassButtons.containsKey(previousKey)) {
            byId("eqclass-$previousKey")?.let { toggleButton(it, false) }
        }
        // No tab lights up for "All" (there's no visible tab for it — see getCountryEquipmentAll).
        byId("eqclass-$selectedClass")?.let { toggleButton(it, true) }
        eqUserSel?.eqclass = selectedClass
        val classLabel = if (isAll) "All" else unitClassNames[selectedClass]
        val countryLabel = if (allCountries) "All Countries" else Equipment.getCountryName(countryId - 1)
        byId("eqInfoText")?.innerHTML = "$year $classLabel upgrades for $countryLabel"

        val selectedEqId = eqUserSel?.equnit as? Int ?: -1
        val sortOrder = eqUserSel?.sortorder as? Int ?: 0
        val sortProperty = eqUserSel?.sortproperty as? String ?: "cost"
        val descending = sortOrder == 1
        val equipmentList = if (isAll) {
            if (allCountries) {
                Equipment.getCountriesEquipmentAll(countryIds, sortProperty, descending)
            } else {
                Equipment.getCountryEquipmentAll(countryId, sortProperty, descending)
            }
        } else {
            val eqClassEnum = UnitClass.values().find { it.value == selectedClass } ?: UnitClass.TANK
            if (allCountries) {
                Equipment.getCountriesEquipmentByClass(eqClassEnum, countryIds, sortProperty, descending)
            } else {
                Equipment.getCountryEquipmentByClass(eqClassEnum, countryId, sortProperty, descending)
            }
        }
        val eqHscroll = byId("hscroll-eqUnitList")
        var eqScrollPos = 0

        equipmentList.forEach { eqid ->
            val eq = Equipment.getEquipment(eqid) ?: return@forEach
            if (!eq.isAvailableIn(year, month)) return@forEach
            if (isUndeployableOnThisMap(map, eq)) return@forEach
            val item = buildEquipmentListItem("eqUnitList", eq)
            item.asDynamic().equnitid = eqid
            // Unaffordable entries stay visible but read as out of reach.
            if (eq.cost * CURRENCY_MULTIPLIER > currentPlayer.prestige) {
                item.classList.add("osada-eq-unaffordable")
            }
            if (eqid == selectedEqId) {
                item.setAttribute("selectedUnit", eq.name)
                eqScrollPos = (eqHscroll?.asDynamic()?.offsetWidth as? Int ?: 0) / 2 - (item.offsetWidth / 2)
            }
            item.onclick = { _: org.w3c.dom.events.MouseEvent ->
                eqUserSel?.equnit = eqid
                eqUserSel?.eqtransport = -1
                eqUserSel?.detailfocus = "unit"
                eqUserSel?.eqscroll = eqHscroll?.asDynamic()?.scrollLeft as? Int ?: 0
                ui.showEquipmentInfo(eq)
                updateEquipmentWindow(selectedClass)
                eqHscroll?.asDynamic()?.scrollLeft = eqUserSel?.eqscroll
            }
        }
        if (selectedEqId > 0) {
            eqHscroll?.asDynamic()?.scrollLeft = eqScrollPos
        }

        val selectedTransportId = eqUserSel?.eqtransport as? Int ?: -1
        var transportSelected = false
        val selectedEq = Equipment.getEquipment(selectedEqId)
        // selectedEq != null is a REQUIRED prerequisite now, not just one of two independent
        // triggers: `selectedTransportId > 0` alone used to be enough to render the whole list —
        // if eqtransport was ever left at a stale positive value while equnit reset to -1 (e.g.
        // the country-select onchange handler resets userunit/equnit but not eqtransport), the
        // list rendered UNFILTERED transports with nothing actually selected (no unit to hide
        // incompatible ones against, since the groundweight filter below is itself gated on
        // selectedEq != null). Picking any unit "fixed" it only because that click handler resets
        // eqtransport too — the actual bug was this clause not requiring a real selection at all.
        if (selectedEq != null && (GameRules.isTransportable(selectedEqId) || selectedTransportId > 0)) {
            val groundClass = UnitClass.GROUND_TRANSPORT
            // The SELECTED unit's own country, not the dropdown's (which, on "All Countries", isn't
            // any single nation) — a transport must match the specific unit it's hauling, not
            // whichever country the browse filter happens to be scoped to.
            val transportCountryId = selectedEq?.country ?: countryId
            val transports = Equipment.getCountryEquipmentByClass(
                groundClass,
                transportCountryId,
                sortProperty,
                descending,
            )
            transports.forEach { transportId ->
                val transport = Equipment.getEquipment(transportId) ?: return@forEach
                if (!transport.isAvailableIn(year, month)) return@forEach
                if (selectedEq != null && (selectedEq.groundweight and transport.groundweight) == 0) return@forEach
                val item = buildEquipmentListItem("eqTransportList", transport)
                item.asDynamic().eqtransportid = transportId
                if (transportId == selectedTransportId) {
                    item.setAttribute("selectedUnit", transport.name)
                    transportSelected = true
                }
                item.onclick = { _: org.w3c.dom.events.MouseEvent ->
                    val current = eqUserSel?.eqtransport as? Int ?: -1
                    eqUserSel?.eqtransport = if (current == transportId) -2 else transportId
                    eqUserSel?.detailfocus = "transport"
                    ui.showEquipmentInfo(transport)
                    updateEquipmentWindow(selectedClass)
                }
            }
        }
        if (!transportSelected) eqUserSel?.eqtransport = -1
        // Detail column follows the last-clicked list: a picked transport shows ITS record
        // (PM behavior); deselecting the transport falls back to the main unit.
        val focusTransportId = eqUserSel?.eqtransport as? Int ?: -1
        val detailEq = if ((eqUserSel?.detailfocus as? String) == "transport" && focusTransportId > 0) {
            Equipment.getEquipment(focusTransportId)
        } else {
            Equipment.getEquipment(selectedEqId)
        }
        EquipmentWindowBuilder.renderEquipmentDetail(detailEq)
        EquipmentWindowBuilder.refreshReserveState()
        updateEquipmentCosts()
    }

    /** Whether [eq] could never actually be deployed anywhere on [map] — a train with nowhere to
     *  run rail on, or a pure-naval ship on a land-locked map — and should therefore be hidden
     *  from the Purchase list rather than sold as something the player could never place (2026-07-14
     *  user request). AMPHIBIOUS is intentionally excluded: it's land-capable too, so a lack of
     *  water doesn't make it unusable. A DEEP_NAVAL-flagged GROUND-class unit is the legacy
     *  "repurpose the unused deep-naval slot for trains" convention (UnitPredicates.isTrain), not
     *  a real ship, so it's checked against rail, not water. DEEP_NAVAL/NAVAL need OPEN water
     *  (Ocean/Port); only COASTAL can also use a river (movTableDry rows 6/10 mark RIVER
     *  impassable for the other two) — a river-only map like Operation Uranus was wrongly letting
     *  submarines/destroyers/battleships onto the Purchase list before this split (2026-07-15). */
    private fun isUndeployableOnThisMap(map: GameMap, eq: EquipmentData): Boolean {
        val isGroundClass = eq.uclass <= UnitClass.AIR_DEFENCE.value
        return when {
            eq.movmethod == MovMethod.RAIL.value -> !map.hasRailData()
            eq.movmethod == MovMethod.DEEP_NAVAL.value && isGroundClass -> !map.hasRailData()
            eq.movmethod == MovMethod.DEEP_NAVAL.value ||
                eq.movmethod == MovMethod.NAVAL.value -> !map.hasOpenWaterAccess()
            eq.movmethod == MovMethod.COASTAL.value -> !map.hasWaterAccess()
            else -> false
        }
    }

    /** Fills the country dropdown from the side's country list, with a leading "All Countries"
     *  entry (value -1); hidden for single-country sides (nothing to union there). */
    private fun syncCountrySelect(selectedIndex: Int) {
        val select = byId("osadaEqCountry") ?: return
        val countries = ui.countriesOnSpotSide
        // Marker class for the window: the country dropdown widens the tools cluster past what
        // the default window width leaves the 8 labeled class tabs (their row overflow:hidden
        // just clips the text) — CSS widens the window / compacts the tabs off this class.
        if (countries.size <= 1) {
            select.style.display = "none"
            byId("equipment")?.classList?.remove("osada-eq--countries")
            return
        }
        select.style.display = ""
        byId("equipment")?.classList?.add("osada-eq--countries")
        val signature = countries.joinToString(",")
        if (select.asDynamic().sig != signature) {
            clearTag(select)
            addSelectOption(select, "All Countries", -1, selectedIndex == -1)
            countries.forEachIndexed { i, country ->
                addSelectOption(select, Equipment.getCountryName(country), i, i == selectedIndex)
            }
            select.asDynamic().sig = signature
        } else {
            // "All Countries" sits at DOM position 0 (value -1); real countries follow at
            // position i+1 — native <select>.selectedIndex is a DOM POSITION, not the option's
            // own value, so the conceptual index (-1=All, 0..N-1=country) needs the +1 offset
            // past "All". (The onchange handler reads .value instead, so it doesn't need this.)
            select.asDynamic().selectedIndex = if (selectedIndex == -1) 0 else selectedIndex + 1
        }
    }

    fun updateEquipmentCosts() {
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val currentPlayer = map.currentPlayer ?: return
        val eqUserSel = byId("eqUserSel")?.asDynamic() ?: return
        val userUnitId = eqUserSel.userunit as? Int ?: -1
        val deployUnitId = eqUserSel.deployunit as? Int ?: -1
        val eqUnitId = eqUserSel.equnit as? Int ?: -1
        val eqTransportId = eqUserSel.eqtransport as? Int ?: -1
        val year = scenario.date.getFullYear()
        val month = scenario.date.getMonth() + 1

        val selectedUnit = if (deployUnitId ==
            -1
        ) {
            map.getUnitById(userUnitId)
        } else {
            currentPlayer.getCoreUnitList().getOrNull(deployUnitId)
        }
        var upgradeCost = 0
        var sellCost = 0
        var buyCost = -1

        if (selectedUnit != null) {
            val unitClass = selectedUnit.unitData(true).uclass.let {
                if (it ==
                    UnitClass.FLAK.value
                ) {
                    UnitClass.AIR_DEFENCE.value
                } else {
                    it
                }
            }
            val unitCountry = selectedUnit.unitData(true).country - 1
            if (eqUnitId > 0) {
                val newEq = Equipment.getEquipment(eqUnitId)
                val newClass =
                    newEq?.uclass?.let { if (it == UnitClass.FLAK.value) UnitClass.AIR_DEFENCE.value else it } ?: -1
                val newCountry = (newEq?.country ?: 0) - 1
                if (unitClass == newClass && unitCountry == newCountry) {
                    upgradeCost = GameRules.calculateUpgradeCosts(selectedUnit, eqUnitId, eqTransportId)
                }
            }
            if (ui.game.campaign == null || selectedUnit.isCore) {
                sellCost = GameRules.calculateUnitSellCost(selectedUnit)
            }
        }

        if (eqUnitId > 0) {
            val newEq = Equipment.getEquipment(eqUnitId)
            val newCountry = (newEq?.country ?: 0) - 1
            buyCost = when {
                selectedUnit != null &&
                    !UIBuilder.eqClassButtons.containsKey(selectedUnit.unitData(true).uclass.toString()) -> -1
                newEq != null && !newEq.isAvailableIn(year, month) -> -1
                ui.game.campaign != null && ui.game.campaign!!.country != newCountry -> -1
                else -> GameRules.calculateUnitCosts(eqUnitId, eqTransportId)
            }
        }
        UIBuilder.showEquipmentCosts(currentPlayer.prestige, buyCost, upgradeCost, sellCost)
    }

    private fun buildUnitListItem(unit: GameUnit): HTMLElement {
        val container = addTag("unitlist", "div")
        container.className = "eqUnitBox"
        val img = buildCardSprite(container)
        val nameDiv = addTag(container, "div")
        val iconsDiv = addTag(container, "div")
        val data = unit.unitData(true)
        val icon = if (data.uclass > UnitClass.SUBMARINE.value) UIBuilder.navalReplacementIcon else data.icon
        img.style.backgroundImage = "url($icon)"
        nameDiv.textContent = unit.customName ?: data.name
        if (unit.customName != null) nameDiv.title = data.name // equipment identity on hover
        iconsDiv.className = if (unit.isDeployed) "eqUnitBoxIconsMenu" else "eqUnitBoxIcons"
        var icons = ""
        if (unit.isDeployed) {
            if (!unit.hasFired) icons += ">"
            if (!unit.hasMoved) icons += "|"
            if ((GameRules.unitUsesFuel(unit) && unit.fuel < 5) || unit.ammo < 2) icons += ";"
        } else {
            icons = "Z"
        }
        iconsDiv.textContent = icons
        // Rename pencil (Stage 3.5, Task 2). This strip only ever lists the CURRENT player's
        // units (updateEquipmentWindow filters on player id), so no ownership check is needed.
        val rename = addTag(container, "span")
        rename.className = "osada-rename-btn osada-rename-btn--card"
        rename.innerHTML = "&#9998;" // ✎
        rename.title = "Rename"
        rename.onclick = { e: org.w3c.dom.events.MouseEvent ->
            e.stopPropagation() // must not select the card (that re-render would kill the input)
            startCardRename(container, nameDiv, unit)
        }
        return container
    }

    /** Inline rename inside a reserve/upgrade strip card — same Enter/blur/Esc contract as the
     *  bottom unit card's editor (UnitInfoPanel.startRename). */
    private fun startCardRename(container: HTMLElement, nameDiv: HTMLElement, unit: GameUnit) {
        if (container.query("input") != null) return
        val input = document.createElement("input") as HTMLInputElement
        input.className = "osada-rename-input osada-rename-input--card"
        input.maxLength = UNIT_NAME_MAX_LENGTH
        input.value = unit.customName ?: ""
        input.placeholder = unit.unitData(true).name
        nameDiv.style.visibility = "hidden"
        container.appendChild(input)
        var done = false
        fun finish(commit: Boolean) {
            if (done) return
            done = true
            input.onblur = null // removing a focused element fires blur; don't re-enter
            val value = input.value.trim().take(UNIT_NAME_MAX_LENGTH)
            delTag(input)
            nameDiv.style.visibility = ""
            if (commit) unit.customName = value.ifEmpty { null }
            nameDiv.textContent = unit.customName ?: unit.unitData(true).name
            // If this unit is the one on the bottom card, refresh that name too.
            ui.game.scenario?.map?.currentUnit?.let { if (it.id == unit.id) ui.showUnitInfo(it) }
        }
        input.onkeydown = { e ->
            e.stopPropagation() // typing must not trigger document-level game hotkeys
            when (e.asDynamic().key as? String) {
                "Enter" -> finish(true)
                "Escape" -> finish(false)
                else -> {}
            }
        }
        input.onblur = { finish(true) }
        input.onclick = { e -> e.stopPropagation() }
        input.onmousedown = { e -> e.stopPropagation() }
        input.focus()
        input.select()
    }

    private fun buildEquipmentListItem(containerId: String, eq: EquipmentData): HTMLElement {
        val container = addTag(containerId, "div")
        container.className = "eqUnitBox"
        val img = buildCardSprite(container)
        val nameDiv = addTag(container, "div")
        val costDiv = addTag(container, "div")
        img.style.backgroundImage = "url(${eq.icon})"
        nameDiv.textContent = eq.name
        costDiv.innerHTML = "<b>${eq.cost * CURRENCY_MULTIPLIER}${UIBuilder.currencyIcon}</b>"
        return container
    }

    /** One facing frame of the unit's 9-frame icon strip, as a background-image div — the legacy
     *  `<img>` + `clip: rect(...)` hack showed a fixed 240..320px window, which only equals frame 3
     *  when frames are exactly 80px wide; strips with other frame sizes (e.g. pl12.png's 72px
     *  frames) rendered as chopped slices of two neighbouring frames. background-size in frame
     *  units (900% = 9 frames scaled so one frame fills the box) is geometry-independent; the
     *  x-position formula for frame k is k/(9-1)*100%. */
    private fun buildCardSprite(container: HTMLElement): HTMLElement {
        val sprite = addTag(container, "div")
        sprite.className = "eqUnitBoxSprite"
        return sprite
    }

    /** FLAK and AIR_DEFENCE share one equipment tab; collapse them so strip filtering matches
     *  the tab the player actually clicked. */
    private fun normalizeUnitClass(uclass: Int): Int =
        if (uclass == UnitClass.FLAK.value) UnitClass.AIR_DEFENCE.value else uclass

    private fun unitComparator(byDeployed: Boolean): Comparator<GameUnit> =
        compareBy<GameUnit> { it.unitData(true).uclass }
            .thenBy { it.unitData(true).name }
            .thenBy { it.id }
            .thenBy { if (byDeployed) !it.isCore else false }
}
