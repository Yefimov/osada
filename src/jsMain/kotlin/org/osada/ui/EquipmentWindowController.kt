package org.osada.ui

import org.osada.UnitClass
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.getCountryName
import org.osada.model.hasWaterAccess
import org.osada.uiSettings

/**
 * Manages the equipment purchase/upgrade/sell window and the deployed-unit list: populates
 * both scrolling lists, handles buy/upgrade/sell button actions, and recomputes cost badges.
 * Extracted from the former `UI` god-class (SRP). The two scrolling strips live in
 * [EquipmentUnitStrip] / [EquipmentCatalogStrip]; their shared pure state-resolution in
 * [EquipmentWindowState]; the button actions in [EquipmentWindowButtons]; the cost badges in
 * [EquipmentCostsCalculator].
 */
internal class EquipmentWindowController(
    private val ui: UI,
) {
    companion object {
        private const val FLAG_SPRITE_WIDTH = 21
    }

    private val buttons = EquipmentWindowButtons(ui)
    private val costs = EquipmentCostsCalculator(ui)

    fun toggleUnitsAndEquipmentWindow(show: Boolean) {
        if (show) {
            CompactEquipmentNavigation.showList()
            makeVisible("container-unitlist")
            // Inline grid (not makeVisible's display:inline) so the area layout applies.
            byId("equipment")?.style?.display = "grid"
            EquipmentWindowBuilder.setEquipmentMode("reserve")
            setDeployMode(true, "units-and-equipment window opened")
            AttackRingBuilder.clear() // rings clear while any modal window is open (spec)
        } else {
            makeHidden("container-unitlist")
            hideEquipmentWindow()
            setDeployMode(false, "units-and-equipment window closed")
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

    fun equipmentWindowButtons(action: String) = buttons.handle(action)

    fun updateEquipmentWindow(unitClass: Int) {
        val scenario = ui.game.scenario
        val map = scenario?.map
        val currentPlayer = map?.currentPlayer
        if (scenario == null || map == null || currentPlayer == null) return
        val eqSelCountry = byId("eqSelCountry")
        if (eqSelCountry == null || currentPlayer.side != ui.game.spotSide) return

        clearTag("unitlist")
        clearTag("eqUnitList")
        clearTag("eqTransportList")

        val countryCtx = resolveCountryContext(eqSelCountry)
        val year = scenario.date.getFullYear()
        // 1-based (matches monthavailable/monthexpired's own OG convention), unlike JS's own
        // 0-based Date.getMonth().
        val month = scenario.date.getMonth() + 1
        val coreList = currentPlayer.getCoreUnitList()
        val unitList = EquipmentWindowState.updateDeployModeAndUnitList(ui, currentPlayer, map, coreList)
        ReserveRefitPresenter.refreshBar(ui, currentPlayer)

        val eqUserSel = byId("eqUserSel")?.asDynamic()
        val eqmode = eqUserSel?.eqmode as? String ?: "purchase"
        populateUnitStrip(unitClass, eqmode, eqUserSel, map, currentPlayer, unitList, coreList)

        UIBuilder.syncNavalTabVisibility(map.hasWaterAccess())
        val (selectedClass, isAll) = resolveSelectedClassAndTabs(unitClass, eqmode, eqUserSel)
        // Localized, and it names what the tab actually does. It was one hardcoded English string
        // ending "upgrades for <country>" in all three modes, so the Purchase tab -- where nothing
        // is being upgraded -- read "1942 Infantry upgrades for USSR", and a Russian UI got English.
        byId("eqInfoText")?.textContent =
            I18n.t(
                "equipment.heading.$eqmode",
                mapOf(
                    "year" to year.toString(),
                    "class" to EquipmentWindowState.classLabel(isAll, selectedClass),
                    "country" to
                        EquipmentWindowState.countryLabel(countryCtx.allCountries, countryCtx.countryId),
                ),
            )

        populateCatalogAndTransport(
            eqUserSel,
            map,
            currentPlayer,
            countryCtx,
            isAll,
            selectedClass,
            year,
            month,
        )

        finalizeEquipmentDetail(eqUserSel, eqUserSel?.equnit as? Int ?: -1)
    }

    /** Fills the "your units" reserve/upgrade strip. Upgrade tab: the class tabs scope the
     *  player's OWN unit strip (you can only upgrade a unit to another of its class), so filter
     *  the strip to the selected class. This removes the "Tanks tab shows tank offers while my
     *  infantry is selected" mismatch. */
    private fun populateUnitStrip(
        unitClass: Int,
        eqmode: String,
        eqUserSel: dynamic,
        map: org.osada.model.GameMap,
        currentPlayer: org.osada.model.Player,
        unitList: List<org.osada.model.GameUnit>,
        coreList: List<org.osada.model.GameUnit>,
    ) {
        val filterClass =
            if (eqmode == "upgrade") {
                EquipmentWindowState.normalizeUnitClass(unitClass.toString().toIntOrNull() ?: UnitClass.TANK.value)
            } else {
                -1
            }
        val initialSelectedUnitId = EquipmentWindowState.resolveInitialSelectedUnitId(currentPlayer, eqUserSel)
        val scrollPos =
            EquipmentUnitStrip.populate(
                ui,
                eqUserSel,
                map,
                currentPlayer,
                unitList,
                coreList,
                filterClass,
                initialSelectedUnitId,
            )
        if (!uiSettings.deployMode) {
            byId("hscroll-unitlist")?.asDynamic()?.scrollLeft = scrollPos
        }
    }

    private fun populateCatalogAndTransport(
        eqUserSel: dynamic,
        map: org.osada.model.GameMap,
        currentPlayer: org.osada.model.Player,
        countryCtx: CountryContext,
        isAll: Boolean,
        selectedClass: Int,
        year: Int,
        month: Int,
    ) {
        val selectedEqId = eqUserSel?.equnit as? Int ?: -1
        val sortProperty = eqUserSel?.sortproperty as? String ?: "cost"
        val descending = (eqUserSel?.sortorder as? Int ?: 0) == 1
        val equipmentList =
            EquipmentWindowState.resolveEquipmentList(
                isAll,
                countryCtx.allCountries,
                countryCtx.countryIds,
                countryCtx.countryId,
                selectedClass,
                sortProperty,
                descending,
            )
        val eqScrollPos =
            EquipmentCatalogStrip.populateEquipmentList(
                ui,
                eqUserSel,
                map,
                currentPlayer,
                equipmentList,
                year,
                month,
                selectedEqId,
                selectedClass,
            )
        if (selectedEqId > 0) {
            byId("hscroll-eqUnitList")?.asDynamic()?.scrollLeft = eqScrollPos
        }

        val selectedTransportId = eqUserSel?.eqtransport as? Int ?: -1
        EquipmentCatalogStrip.populateTransportList(
            ui,
            eqUserSel,
            selectedEqId,
            selectedTransportId,
            sortProperty,
            descending,
            year,
            month,
            selectedClass,
        )
    }

    private class CountryContext(
        val countryIds: List<Int>,
        val countryId: Int,
        val allCountries: Boolean,
    )

    private fun resolveCountryContext(eqSelCountry: org.w3c.dom.HTMLElement): CountryContext {
        val countryIndex = eqSelCountry.asDynamic().country as? Int ?: -1
        val allCountries = countryIndex == -1
        val countryIds = EquipmentWindowState.resolveCountryIds(ui, allCountries, countryIndex)
        val countryId = countryIds.firstOrNull() ?: 1
        eqSelCountry.style.backgroundPosition = "${-FLAG_SPRITE_WIDTH * (countryId - 1)}px 0px"
        syncCountrySelect(countryIndex)
        return CountryContext(countryIds, countryId, allCountries)
    }

    /** unitClass may arrive as a JS string (class buttons pass their string key via the dynamic
     *  js() call) or as an Int; normalises to Int, clamps it to a valid/visible tab, and updates
     *  the tab button highlight + stored eqclass. Returns (selectedClass, isAll) -- `isAll` is
     *  the value BEFORE any upgrade-tab clamp, since the equipment-list query below still needs
     *  to know the ORIGINAL "All" intent even when selectedClass itself got forced to Tank. */
    private fun resolveSelectedClassAndTabs(
        unitClass: Int,
        eqmode: String,
        eqUserSel: dynamic,
    ): Pair<Int, Boolean> {
        var selectedClass = unitClass.toString().toIntOrNull() ?: UnitClass.TANK.value
        if (selectedClass == UnitClass.FLAK.value) selectedClass = UnitClass.AIR_DEFENCE.value
        val isAll = selectedClass == UnitClass.NONE.value
        // "All" doesn't exist on the Upgrade tab (upgrades are class-locked by the rules — there's
        // no "upgrade to any class"); defensively clamp instead of trusting only the tab's own
        // click-handler guard, since eqclass can also arrive here via setEquipmentMode's re-fetch.
        if (isAll && eqmode == "upgrade") selectedClass = UnitClass.TANK.value
        if (!isAll && !UIBuilder.eqClassButtons.containsKey(selectedClass.toString())) {
            selectedClass = UnitClass.TANK.value
        }
        val previousKey = eqUserSel?.eqclass?.toString() ?: UnitClass.TANK.value.toString()
        if (UIBuilder.eqClassButtons.containsKey(previousKey)) {
            byId("eqclass-$previousKey")?.let { toggleButton(it, false) }
        }
        // "All" has had its own leftmost tab (class 0) since 2026-07-26, so it highlights like any
        // other; on the Upgrade tab the clamp above has already moved the highlight to Tank.
        byId("eqclass-$selectedClass")?.let { toggleButton(it, true) }
        eqUserSel?.eqclass = selectedClass
        return selectedClass to isAll
    }

    /** Detail column follows the last-clicked list: a picked transport shows ITS record
     *  (PM behavior); deselecting the transport falls back to the main unit. */
    private fun finalizeEquipmentDetail(
        eqUserSel: dynamic,
        selectedEqId: Int,
    ) {
        val focusTransportId = eqUserSel?.eqtransport as? Int ?: -1
        val detailEq =
            if ((eqUserSel?.detailfocus as? String) == "transport" && focusTransportId > 0) {
                Equipment.getEquipment(focusTransportId)
            } else {
                Equipment.getEquipment(selectedEqId)
            }
        EquipmentWindowBuilder.renderEquipmentDetail(detailEq)
        EquipmentWindowBuilder.refreshReserveState()
        updateEquipmentCosts()
        GameplayLocalization.refreshEquipment()
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

    fun updateEquipmentCosts() = costs.update()
}
