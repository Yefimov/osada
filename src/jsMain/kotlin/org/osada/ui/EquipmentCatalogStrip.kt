package org.osada.ui

import org.osada.CURRENCY_MULTIPLIER
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.getCountryEquipmentByClass
import org.osada.model.isAvailableIn
import org.osada.rules.GameRules
import org.osada.rules.isTransportable
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * [EquipmentWindowController.updateEquipmentWindow]'s purchase/upgrade equipment catalog and
 * its matching transport strip. Split out purely to keep [EquipmentWindowController] within the
 * project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object EquipmentCatalogStrip {
    private const val TRANSPORT_DESELECTED = -2

    /** Builds every purchasable/upgradeable equipment card; returns the scroll offset that
     *  centers the selected one. */
    fun populateEquipmentList(
        ui: UI,
        eqUserSel: dynamic,
        map: GameMap,
        currentPlayer: Player,
        equipmentList: List<Int>,
        year: Int,
        month: Int,
        selectedEqId: Int,
        selectedClass: Int,
    ): Int {
        val eqHscroll = byId("hscroll-eqUnitList")
        var eqScrollPos = 0
        equipmentList.forEach { eqid ->
            val eq = Equipment.getEquipment(eqid) ?: return@forEach
            if (!eq.isAvailableIn(year, month)) return@forEach
            if (EquipmentWindowState.isUndeployableOnThisMap(map, eq)) return@forEach
            // The whole side's catalogue is listed here (own nation + support countries from
            // getCountriesBySide), matching original PM, whose list loop filters on availability
            // dates ONLY. Foreign-country entries are deliberately kept even though a campaign
            // may not BUY them (EquipmentCostsCalculator.resolveBuyCost rejects any country but
            // the campaign's own): scenarios routinely field support-country units — Seseña's
            // Soviet campaign fights with a mostly Spanish Republic order of battle — and those
            // units still need their nation's models listed to be UPGRADEABLE, since upgrades
            // key off the unit's own country, not the campaign's. Instead of hiding the cards,
            // the detail pane spells out why Buy is unavailable; see showEquipmentCosts.
            val item = buildEquipmentListItem("eqUnitList", eq)
            item.asDynamic().equnitid = eqid
            // Unaffordable entries stay visible but read as out of reach — the player can see
            // what they are saving toward, matching the Buy button's own "need N more prestige".
            if (eq.cost * CURRENCY_MULTIPLIER > currentPlayer.prestige) {
                item.classList.add("osada-eq-unaffordable")
            }
            if (eqid == selectedEqId) {
                item.setAttribute("selectedUnit", eq.name)
                eqScrollPos = (eqHscroll?.asDynamic()?.offsetWidth as? Int ?: 0) / 2 - (item.offsetWidth / 2)
            }
            item.onclick = { _: MouseEvent ->
                eqUserSel?.equnit = eqid
                eqUserSel?.eqtransport = -1
                eqUserSel?.detailfocus = "unit"
                eqUserSel?.eqscroll = eqHscroll?.asDynamic()?.scrollLeft as? Int ?: 0
                ui.showEquipmentInfo(eq)
                ui.updateEquipmentWindow(selectedClass)
                eqHscroll?.asDynamic()?.scrollLeft = eqUserSel?.eqscroll
            }
        }
        return eqScrollPos
    }

    /** Builds the matching-transport strip for the currently selected equipment, if any is
     *  transportable; clears a stale transport selection when nothing in the list matches it. */
    fun populateTransportList(
        ui: UI,
        eqUserSel: dynamic,
        selectedEqId: Int,
        selectedTransportId: Int,
        sortProperty: String,
        descending: Boolean,
        year: Int,
        month: Int,
        selectedClass: Int,
    ) {
        val selectedEq = Equipment.getEquipment(selectedEqId)
        var transportSelected = false
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
            val transports =
                Equipment.getCountryEquipmentByClass(groundClass, selectedEq.country, sortProperty, descending)
            transports.forEach { transportId ->
                val transport = Equipment.getEquipment(transportId) ?: return@forEach
                if (!transport.isAvailableIn(year, month)) return@forEach
                if ((selectedEq.groundweight and transport.groundweight) == 0) return@forEach
                val item = buildEquipmentListItem("eqTransportList", transport)
                item.asDynamic().eqtransportid = transportId
                if (transportId == selectedTransportId) {
                    item.setAttribute("selectedUnit", transport.name)
                    transportSelected = true
                }
                item.onclick = { _: MouseEvent ->
                    val current = eqUserSel?.eqtransport as? Int ?: -1
                    eqUserSel?.eqtransport = if (current == transportId) TRANSPORT_DESELECTED else transportId
                    eqUserSel?.detailfocus = "transport"
                    ui.showEquipmentInfo(transport)
                    ui.updateEquipmentWindow(selectedClass)
                }
            }
        }
        if (!transportSelected) eqUserSel?.eqtransport = -1
    }

    private fun buildEquipmentListItem(
        containerId: String,
        eq: EquipmentData,
    ): HTMLElement {
        val container = addTag(containerId, "div")
        container.className = "eqUnitBox"
        container.title = "Select ${eq.name} to inspect its statistics, availability, price and special capabilities."
        val img = EquipmentWindowState.buildCardSprite(container)
        val nameDiv = addTag(container, "div")
        val costDiv = addTag(container, "div")
        img.style.backgroundImage = "url(${UnitIconResolver.forCurrentScenario(eq.icon)})"
        nameDiv.textContent = eq.name
        val markings = addTag(container, "span")
        markings.className = "osada-capability-marks"
        EquipmentMarkings.render(markings, eq)
        costDiv.innerHTML = "<b>${eq.cost * CURRENCY_MULTIPLIER}${UIBuilder.currencyIcon}</b>"
        return container
    }
}
