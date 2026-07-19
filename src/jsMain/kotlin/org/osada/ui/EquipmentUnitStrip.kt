package org.osada.ui

import org.osada.model.getUnitById
import org.osada.rules.GameRules
import org.osada.rules.isTransportable
import org.osada.rules.unitUsesFuel
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * [EquipmentWindowController.updateEquipmentWindow]'s "your units" reserve/upgrade strip: the
 * unit-list loop, its per-item building/wiring, and the inline rename card. Split out purely to
 * keep [EquipmentWindowController] within the project's function-count/class-size limits -- not
 * expected to be called from elsewhere.
 */
internal object EquipmentUnitStrip {
    private const val LOW_FUEL_THRESHOLD = 5

    /** Builds every visible unit card, resolving/wiring the currently-selected one along the
     *  way (mutating [eqUserSel] exactly as the original inline loop did); returns the scroll
     *  offset that centers the selected card. */
    fun populate(
        ui: UI,
        eqUserSel: dynamic,
        map: org.osada.model.GameMap,
        currentPlayer: org.osada.model.Player,
        unitList: List<org.osada.model.GameUnit>,
        coreList: List<org.osada.model.GameUnit>,
        filterClass: Int,
        initialSelectedUnitId: Int,
    ): Int {
        val hscroll = byId("hscroll-unitlist")
        var selectedUnitId = initialSelectedUnitId
        var scrollPos = 0
        unitList.forEach { unit ->
            if (!isEligibleUnit(unit, currentPlayer, filterClass)) return@forEach
            val result = processUnitListItem(ui, eqUserSel, map, currentPlayer, unit, coreList, selectedUnitId, hscroll)
            selectedUnitId = result.selectedUnitId
            result.scrollPos?.let { scrollPos = it }
        }
        return scrollPos
    }

    private fun isEligibleUnit(
        unit: org.osada.model.GameUnit,
        currentPlayer: org.osada.model.Player,
        filterClass: Int,
    ): Boolean {
        val deployedOut = uiSettings.deployMode && unit.isDeployed
        val notOwn = unit.player?.id != currentPlayer.id
        val classMismatch =
            filterClass != -1 && EquipmentWindowState.normalizeUnitClass(unit.unitData(true).uclass) != filterClass
        return !deployedOut && !notOwn && !classMismatch
    }

    private class UnitItemResult(
        val selectedUnitId: Int,
        val scrollPos: Int?,
    )

    private fun processUnitListItem(
        ui: UI,
        eqUserSel: dynamic,
        map: org.osada.model.GameMap,
        currentPlayer: org.osada.model.Player,
        unit: org.osada.model.GameUnit,
        coreList: List<org.osada.model.GameUnit>,
        selectedUnitIdIn: Int,
        hscroll: HTMLElement?,
    ): UnitItemResult {
        var selectedUnitId = selectedUnitIdIn
        val item = buildUnitListItem(ui, unit)
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
        var scrollPos: Int? = null
        if (item.asDynamic().unitid == selectedUnitId) {
            selectCurrentUnitItem(ui, eqUserSel, unit)
            item.setAttribute("selectedUnit", unit.unitData(true).name)
            scrollPos = (hscroll?.asDynamic()?.offsetWidth as? Int ?: 0) / 2 - (item.offsetWidth / 2)
        }
        wireUnitItemClick(ui, eqUserSel, map, currentPlayer, item, hscroll)
        return UnitItemResult(selectedUnitId, scrollPos)
    }

    private fun buildUnitListItem(
        ui: UI,
        unit: org.osada.model.GameUnit,
    ): HTMLElement {
        val container = addTag("unitlist", "div")
        container.className = "eqUnitBox"
        val img = EquipmentWindowState.buildCardSprite(container)
        val nameDiv = addTag(container, "div")
        val iconsDiv = addTag(container, "div")
        val data = unit.unitData(true)
        val icon = if (data.uclass > org.osada.UnitClass.SUBMARINE.value) UIBuilder.navalReplacementIcon else data.icon
        img.style.backgroundImage = "url($icon)"
        nameDiv.textContent = unit.customName ?: data.name
        if (unit.customName != null) nameDiv.title = data.name // equipment identity on hover
        iconsDiv.className = if (unit.isDeployed) "eqUnitBoxIconsMenu" else "eqUnitBoxIcons"
        var icons = ""
        if (unit.isDeployed) {
            if (!unit.hasFired) icons += ">"
            if (!unit.hasMoved) icons += "|"
            if ((GameRules.unitUsesFuel(unit) && unit.fuel < LOW_FUEL_THRESHOLD) || unit.ammo < 2) icons += ";"
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
        rename.onclick = { e: MouseEvent ->
            e.stopPropagation() // must not select the card (that re-render would kill the input)
            startCardRename(ui, container, nameDiv, unit)
        }
        return container
    }

    /** Inline rename inside a reserve/upgrade strip card — same Enter/blur/Esc contract as the
     *  bottom unit card's editor (UnitStatCard.startRename). */
    private fun startCardRename(
        ui: UI,
        container: HTMLElement,
        nameDiv: HTMLElement,
        unit: org.osada.model.GameUnit,
    ) {
        if (container.query("input") != null) return
        val input = kotlinx.browser.document.createElement("input") as org.w3c.dom.HTMLInputElement
        input.className = "osada-rename-input osada-rename-input--card"
        input.maxLength = org.osada.UNIT_NAME_MAX_LENGTH
        input.value = unit.customName ?: ""
        input.placeholder = unit.unitData(true).name
        nameDiv.style.visibility = "hidden"
        container.appendChild(input)
        var done = false

        fun finish(commit: Boolean) {
            if (done) return
            done = true
            input.onblur = null // removing a focused element fires blur; don't re-enter
            val value = input.value.trim().take(org.osada.UNIT_NAME_MAX_LENGTH)
            delTag(input)
            nameDiv.style.visibility = ""
            if (commit) unit.customName = value.ifEmpty { null }
            nameDiv.textContent = unit.customName ?: unit.unitData(true).name
            // If this unit is the one on the bottom card, refresh that name too.
            ui.game.scenario
                ?.map
                ?.currentUnit
                ?.let { if (it.id == unit.id) ui.showUnitInfo(it) }
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

    /** The `item.asDynamic().unitid == selectedUnitId` branch: adopts selection state and, for a
     *  non-deploy pick, the unit's existing transport (guarded exactly as PM's osada.js:6414). */
    private fun selectCurrentUnitItem(
        ui: UI,
        eqUserSel: dynamic,
        unit: org.osada.model.GameUnit,
    ) {
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
        val equnitBlocksAdoption = currentEqunit != -1 && !GameRules.isTransportable(currentEqunit)
        val shouldAdoptTransport = existingTransportEqid != null && currentTransport == -1 && !equnitBlocksAdoption
        if (shouldAdoptTransport) {
            eqUserSel?.eqtransport = existingTransportEqid
        }
    }

    private fun wireUnitItemClick(
        ui: UI,
        eqUserSel: dynamic,
        map: org.osada.model.GameMap,
        currentPlayer: org.osada.model.Player,
        item: HTMLElement,
        hscroll: HTMLElement?,
    ) {
        item.onclick = { _: MouseEvent ->
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
                val selected =
                    if (uiSettings.deployMode) {
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
                    ui.updateEquipmentWindow(item.asDynamic().eqclass as? Int ?: org.osada.UnitClass.TANK.value)
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
}
