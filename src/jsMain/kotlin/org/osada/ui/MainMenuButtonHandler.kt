package org.osada.ui

import org.osada.PlayerType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.delCurrentUnit
import org.osada.rules.GameRules
import org.osada.rules.isAir
import org.osada.uiSettings

/**
 * In-game top-bar button actions (air mode, hex grid, strategic zoom dispatch, unit inspector,
 * buy/deploy window, main menu slide-out, options/pause) plus the global Escape router and the
 * undeployed-units prompt. Split from the former `MenuController` god-class to stay within the
 * project's function-count/class-size limits.
 */
internal class MainMenuButtonHandler(
    private val ui: UI,
) {
    fun mainMenuButton(id: String) {
        val map = ui.game.scenario?.map ?: return
        when (id) {
            "air" -> onAirButton(map)
            "hex" -> onHexButton()
            "zoom" -> {
                ui.toggleStrategicZoom()
                ui.render.render()
            }
            "inspectunit" -> onInspectUnitButton(map)
            "buy" -> onBuyButton(map)
            "endturn" -> ui.onEndTurnClick()
            "mainmenu" -> onMainMenuToggleButton()
            "options" -> onOptionsButton()
        }
    }

    private fun onAirButton(map: GameMap) {
        if (uiSettings.airMode && GameRules.isAir(map.currentUnit)) {
            map.delCurrentUnit()
        }
        uiSettings.airMode = !uiSettings.airMode
        byId("air")?.let { toggleButton(it, uiSettings.airMode) }
        ui.render.render()
    }

    private fun onHexButton() {
        uiSettings.hexGrid = !uiSettings.hexGrid
        byId("hex")?.let { toggleButton(it, uiSettings.hexGrid) }
        ui.removeAllSmallToolTips()
        ui.addSmallToolTips()
        ui.render.render()
    }

    private fun onInspectUnitButton(map: GameMap) {
        byId("unit-info") ?: return
        if (isVisible("unit-info")) {
            makeHidden("unit-info")
            uiSettings.unitInfoVisibility = false
            // Update the toolbar glyph to reflect the toggled-off state (PM's L()).
            byId("inspectunit")?.let { toggleButton(it, false) }
        } else {
            makeVisible("unit-info")
            uiSettings.unitInfoVisibility = true
            byId("inspectunit")?.let { toggleButton(it, true) }
            map.currentUnit?.let { ui.showUnitInfo(it) }
        }
    }

    private fun onBuyButton(map: GameMap) {
        val equipment = byId("equipment")
        if (equipment != null && isVisible("equipment")) {
            hideEquipmentWindow()
            makeHidden("container-unitlist")
            uiSettings.deployMode = false
            byId("buy")?.let { toggleButton(it, false) }
            ui.hideUnitInfoIfNotPinned()
            // Restore the normal turn status; updateEquipmentWindow() had overwritten it
            // with the deploy/"Units currently deployed on map." message (matches PM's z()).
            ui.updateStatusBar()
            ui.render.render()
        } else {
            byId("equipment")?.style?.display = "grid"
            makeVisible("container-unitlist")
            byId("buy")?.let { toggleButton(it, true) }
            val eqclass = (byId("eqUserSel")?.asDynamic()?.eqclass as? Int) ?: UnitClass.TANK.value
            ui.updateEquipmentWindow(eqclass)
            // During the deploy phase the window must open directly on the Reserve tab.
            if (map.currentPlayer?.hasUndeployedUnits() == true) {
                EquipmentWindowBuilder.setEquipmentMode("reserve")
            }
            AttackRingBuilder.clear() // rings clear while any modal window is open (spec)
        }
    }

    private fun onMainMenuToggleButton() {
        byId("slidemenu") ?: return
        if (isVisible("slidemenu")) {
            makeHidden("slidemenu")
            byId("mainmenu")?.let { toggleButton(it, false) }
        } else {
            makeVisible("slidemenu")
            byId("mainmenu")?.let { toggleButton(it, true) }
        }
    }

    private fun onOptionsButton() {
        if (isVisible("startmenu")) {
            makeHidden("smMain")
            makeHidden("smScen")
            makeHidden("smSettings")
            makeHidden("smState")
            makeHidden("startmenu")
            byId("options")?.let { toggleButton(it, false) }
        } else {
            // An open message box (e.g. the scenario-intro briefing left unread) sits on
            // --z-msg, ABOVE the pause menu's own layer — its title floated over the main
            // menu. Dismiss it through its own OK path so any pending
            // callback/uiMessageClicked flag runs exactly as if the player clicked OK.
            if (isVisible("ui-message")) byId("uiokbut")?.click()
            makeVisible("startmenu")
            makeVisible("smMain")
            // Re-check Continue: a save created during this session must surface it.
            StartMenuBuilder.applyContinueButtonState()
            byId("options")?.let { toggleButton(it, true) }
            AttackRingBuilder.clear() // rings clear while any modal window is open (spec)
        }
    }

    /**
     * Single global Escape handler: closes the topmost modal, or — if nothing is open — toggles
     * the pause/options menu. Registered ONCE from UI.init(); anything that wants Escape to close
     * it belongs as a branch here, not a second document-level listener (two independent listeners
     * would both fire on the same keypress, e.g. closing a window AND opening the pause menu).
     *
     * **The branch order IS the z-order, and it has to be maintained by hand (DEFERRED.md §4.13).**
     * `ui-message` used to be commented "topmost layer of all (--z-msg)"; that stopped being true
     * when §7.28/§7.37 rebuilt three dialogs onto the same `--z-msg` tier and none of them was
     * registered here. The visible consequence was that Escape over the attachment picker matched
     * the `equipment` branch and closed the window *underneath* the modal, leaving a
     * prestige-spending dialog floating over the bare map.
     *
     * The promotion dialog is deliberately a **swallow**, not a close: it is the one modal that owes
     * the player a decision and has no cancel path, so Escape must neither dismiss it nor fall
     * through and open the pause menu behind it.
     */
    fun handleGlobalEscape() {
        when {
            isVisible("ui-message") -> byId("uiokbut")?.click()
            HeroPromotionPresenter.isOpen() -> Unit // modal by design — see the doc comment
            CommanderRosterPresenter.isTransferPickerOpen() -> CommanderRosterPresenter.closeTransferPicker()
            AttachmentPickerPresenter.isOpen() -> AttachmentPickerPresenter.close()
            CommanderRosterPresenter.isOpen() -> CommanderRosterPresenter.close()
            isVisible("equipment") -> byId("eqCloseBut")?.click()
            isVisible("combatLog") -> UICombatLog.toggleCombatLog(false, true)
            else -> ui.mainMenuButton("options")
        }
    }

    fun checkUndeployedUnits(): Boolean {
        val map = ui.game.scenario?.map
        val player = map?.currentPlayer
        val eligible = player != null && player.hasUndeployedUnits() && player.type == PlayerType.HUMAN_LOCAL
        if (map == null || player == null || !eligible) return false
        // Deploy phase: open the equipment window directly on the Reserve tab (the old
        // standalone deploy strip no longer exists as a HUD element).
        byId("equipment")?.style?.display = "grid"
        EquipmentWindowBuilder.setEquipmentMode("reserve")
        makeVisible("container-unitlist")
        AttackRingBuilder.clear() // rings clear while any modal window is open (spec)
        val deployCell = findDeploymentCell(map, player.id)
        if (deployCell != null) {
            ui.uiSetCellOnViewPort(deployCell)
        } else {
            byId("buy")?.let { toggleButton(it, true) }
        }
        return true
    }

    private fun findDeploymentCell(
        map: GameMap,
        playerId: Int,
    ): Cell? {
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                val hex = map.map?.get(r)?.get(c) ?: continue
                if (hex.isDeployment == playerId) return Cell(r, c)
            }
        }
        return null
    }
}
