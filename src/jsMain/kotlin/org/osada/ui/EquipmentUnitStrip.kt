package org.osada.ui

import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroTransferService
import org.osada.i18n.I18n
import org.osada.model.delCurrentUnit
import org.osada.model.getUnitById
import org.osada.rules.Attachments
import org.osada.rules.GameRules
import org.osada.rules.isTransportable
import org.osada.rules.unitUsesFuel
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Hides `#equipment`, taking anything layered over it down with it (DEFERRED.md §4.13).
 *
 * **Every path that closes the equipment window must go through here**, not
 * `makeHidden("equipment")`. [AttachmentPickerPresenter] is opened from this file's unit strip but
 * attached to `mainbody` at `--z-msg`, so hiding the window on its own left a live,
 * prestige-spending modal floating over the bare map with no owner. That is a property of *closing
 * the window*, not of Escape — the ✕, the end-turn teardown and the deploy flow all had it too.
 *
 * It lives here, next to the only code that opens that picker, so the open and the teardown that
 * has to match it are visible in one file.
 */
internal fun hideEquipmentWindow() {
    AttachmentPickerPresenter.close()
    CompactEquipmentNavigation.showList()
    makeHidden("equipment")
}

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

        // Do not silently arm the first reserve card. With no explicit card selection, a click on a
        // deployment hex opens the reserve deck and waits for the player's actual choice.
        if (uiSettings.deployMode &&
            selectedUnitId >= 0 &&
            coreList.getOrNull(selectedUnitId)?.isDeployed == true
        ) {
            DeploymentSelection.clearSelected()
            selectedUnitId = -1
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
        wireUnitItemClick(ui, eqUserSel, map, currentPlayer, unit, item, hscroll)
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
        val icon =
            if (data.uclass > org.osada.UnitClass.SUBMARINE.value) {
                UIBuilder.navalReplacementIcon
            } else {
                UnitIconResolver.forCurrentScenario(unit.eqid, data.icon)
            }
        img.style.backgroundImage = "url($icon)"
        nameDiv.textContent = unit.customName ?: data.name
        if (unit.customName != null) nameDiv.title = data.name // equipment identity on hover
        val location = if (unit.isDeployed) "deployed on the map" else "waiting in reserve"
        val formation = if (unit.isCore) "core formation" else "scenario unit"
        container.title =
            "Select ${unit.customName ?: data.name}: $formation, $location. " +
                "Card symbols show attack available, movement available, low supply, or reserve status."
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
        rename.title = I18n.t("unit_info.rename.label")
        rename.onclick = { e: MouseEvent ->
            e.stopPropagation() // must not select the card (that re-render would kill the input)
            startCardRename(ui, container, nameDiv, unit)
        }
        addAttachmentButton(ui, container, unit)
        addCommanderBadge(container, unit)
        ReserveRefitPresenter.addCardButton(ui, container, unit)
        return container
    }

    /**
     * Marks a formation whose commander is a HERO, with the officer on hover (2026-08-01 request).
     *
     * The Reserve tab is the one place the player picks between formations without seeing any of
     * them on the map, so "which of these has a commander" was invisible exactly where it decides
     * the order things get deployed in. The map has carried a leader badge since Phase 4
     * ([UnitRenderer.drawLeaderBadge]); this is the same fact on the card.
     *
     * [HeroCampaign.heroFor], not `hasAnyCommander`: a legacy `unit.leader` integer has no name, no
     * rank and no dossier, so there is nothing to put in a tooltip and nothing worth a badge. A
     * settling-in commander (§1.10) is shown DIMMED and says so, because for the next few turns
     * their traits are doing nothing and the badge would otherwise promise a bonus that is off.
     */
    private fun addCommanderBadge(
        container: HTMLElement,
        unit: org.osada.model.GameUnit,
    ) {
        val hero = HeroCampaign.heroFor(unit) ?: return
        // Read straight off the roster rather than through `HeroCampaign.dossier`: this runs once
        // per card on every strip rebuild, and a dossier composes a portrait and narrates a whole
        // biography to produce the three words a tooltip needs.
        val name = HeroCampaign.roster().definition(hero.heroId)?.displayName ?: return
        val rank = HeroDisplay.rank(hero.rankId)
        val settling = HeroTransferService.settlingTurnsLeft(hero)
        val badge = addTag(container, "span")
        badge.className = "osada-hero-pip" + if (settling > 0) " osada-hero-pip--settling" else ""
        badge.textContent = "★"
        badge.title =
            if (settling > 0) {
                I18n.t(
                    "equipment.reserve.commander.settling",
                    mapOf("rank" to rank, "name" to name, "turns" to settling),
                )
            } else {
                I18n.t(
                    "equipment.reserve.commander.help",
                    mapOf("rank" to rank, "name" to name, "renown" to HeroDisplay.renown(hero.renown)),
                )
            }
        badge.setAttribute("aria-label", badge.title)
    }

    /** Attachments (DEFERRED.md §1.4, §1.18). This strip is the attachment surface: OG allows
     *  attachment changes at initial HQ only, never mid-scenario, and the equipment window is
     *  where buying and upgrading already happen. It is also the one place carrying a real owned
     *  [org.osada.model.GameUnit] rather than an equipment TYPE, which is what made the picker
     *  possible without the `EquipmentWindowDetail` restructure §7.22 deferred.
     *
     *  Shown only when the unit could actually fit something — an efile with attachments off (14 of
     *  22 campaigns) never renders the control at all, the same hide-don't-disable rule §7.13 took
     *  for the display row and §7.14 for the Naval tab. */
    private fun addAttachmentButton(
        ui: UI,
        container: HTMLElement,
        unit: org.osada.model.GameUnit,
    ) {
        if (Attachments.availableSlots(unit).isEmpty() && Attachments.purchasedSlots(unit).isEmpty()) return
        val player = unit.player ?: return
        val fitted = Attachments.purchasedSlots(unit)
        val button = addTag(container, "span")
        button.className = "osada-atp-btn" + if (fitted.isEmpty()) "" else " osada-atp-btn--fitted"
        // One star per slot, filled for each attachment fitted -- a bare count read as a
        // meaningless "0". This shows BOTH whether the unit carries anything and how much room is
        // left, in the width a digit occupied. Stars are UI-font characters, never osada-menu
        // glyphs (DEFERRED.md §4.12).
        button.textContent =
            "★".repeat(fitted.size) + "☆".repeat(Attachments.MAX_PER_UNIT - fitted.size)
        val summary =
            if (fitted.isEmpty()) {
                I18n.t("attachments.open.help")
            } else {
                fitted.joinToString(", ") { (_, slot) -> slot.name }
            }
        button.title = summary
        button.setAttribute("role", "button")
        button.setAttribute("aria-label", I18n.t("attachments.open.help"))
        button.onclick = { e: MouseEvent ->
            e.stopPropagation() // must not re-select the card out from under the dialog
            AttachmentPickerPresenter.open(unit, player) {
                ui.updateEquipmentWindow(unit.unitData(true).uclass)
            }
        }
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

    // TODO(detekt): CyclomaticComplexMethod (15) — deliberately deferred rather than rushed.
    @Suppress("CyclomaticComplexMethod")
    private fun wireUnitItemClick(
        ui: UI,
        eqUserSel: dynamic,
        map: org.osada.model.GameMap,
        currentPlayer: org.osada.model.Player,
        unit: org.osada.model.GameUnit,
        item: HTMLElement,
        hscroll: HTMLElement?,
    ) {
        item.onclick = click@{ _: MouseEvent ->
            val clickedId = item.asDynamic().unitid as? Int ?: return@click null
            if (uiSettings.deployMode) {
                if (!DeploymentSelection.selectUnit(currentPlayer, unit)) return@click null
                map.delCurrentUnit()
            } else {
                DeploymentSelection.reset()
                eqUserSel?.userunit = clickedId
            }
            eqUserSel?.eqtransport = -1
            eqUserSel?.equnit = item.asDynamic().uniteqid as? Int ?: -1
            eqUserSel?.unitscroll = hscroll?.asDynamic()?.scrollLeft as? Int ?: 0

            val selected = if (uiSettings.deployMode) unit else map.getUnitById(clickedId)
            selected?.let {
                ui.showUnitInfo(it)
                if (!uiSettings.deployMode) {
                    ui.uiUnitSelect(it)
                    it.getPos()?.let { pos -> ui.uiSetCellOnViewPort(pos) }
                } else {
                    // Refresh airfield highlights for the newly selected reserve unit.
                    ui.render.render()
                    // Hex-first deployment: the card click completes the pending placement now,
                    // rather than becoming the choice for the NEXT hex.
                    if (DeploymentSelection.pendingTarget() != null && DeploymentSelection.deployPending(ui)) {
                        return@click null
                    }
                }
                ui.updateEquipmentWindow(item.asDynamic().eqclass as? Int ?: org.osada.UnitClass.TANK.value)
                if (!uiSettings.deployMode) CompactEquipmentNavigation.showDetail()
            }
            hscroll?.asDynamic()?.scrollLeft = eqUserSel?.unitscroll

            // Unit-first deployment: close the catalogue and let the cursor carry this explicit unit.
            val onReserveTab = byId("equipment")?.classList?.contains("osada-eq--reserve") == true
            if (uiSettings.deployMode && onReserveTab) {
                hideEquipmentWindow()
            }
        }
    }
}
