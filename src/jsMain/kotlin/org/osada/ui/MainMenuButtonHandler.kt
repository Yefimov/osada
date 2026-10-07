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
 * In-game top-bar button actions (air mode, hex grid, artillery range, strategic zoom dispatch, unit inspector,
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
            "arty" -> onArtilleryButton(map)
            "zoom" -> {
                ui.toggleStrategicZoom()
                ui.render.render()
            }

            "inspectunit" -> onInspectUnitButton()
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
        setMapLayerMode(air = !uiSettings.airMode, artillery = false)
        ui.render.render()
    }

    /**
     * The sidebar's Artillery toggle (`ui/ArtilleryRangeOverlay`). A view switch that owns no map
     * state of its own -- but it is exclusive with Air mode (see [setMapLayerMode]), so switching it
     * on leaves Air mode, with the same current-unit bookkeeping [onAirButton] does on the way out.
     */
    private fun onArtilleryButton(map: GameMap) {
        val on = !uiSettings.artilleryRange
        if (on && uiSettings.airMode && GameRules.isAir(map.currentUnit)) {
            map.delCurrentUnit()
        }
        setMapLayerMode(air = if (on) false else uiSettings.airMode, artillery = on)
        ui.render.render()
    }

    private fun onHexButton() {
        uiSettings.hexGrid = !uiSettings.hexGrid
        byId("hex")?.let { toggleButton(it, uiSettings.hexGrid) }
        ui.removeAllSmallToolTips()
        ui.addSmallToolTips()
        ui.render.render()
    }

    /** The toolbar's Inspect Unit glyph — one of three ways into the same state, so it delegates
     *  to [UnitCardCollapse] rather than keeping a second copy of the show/hide logic. */
    private fun onInspectUnitButton() {
        byId("unit-info") ?: return
        UnitCardCollapse.setCollapsed(isVisible("unit-info"))
    }

    private fun onBuyButton(map: GameMap) {
        val equipment = byId("equipment")
        if (equipment != null && isVisible("equipment")) {
            hideEquipmentWindow()
            makeHidden("container-unitlist")
            setDeployMode(false, "Reserves button closed the window")
            byId("buy")?.let { toggleButton(it, false) }
            ui.hideUnitInfoIfNotPinned()
            // Restore the normal turn status; updateEquipmentWindow() had overwritten it
            // with the deploy/"Units currently deployed on map." message (matches PM's z()).
            ui.updateStatusBar()
            ui.render.render()
        } else {
            // Not on someone else's turn. `updateEquipmentWindow` already refuses to populate for a
            // player whose side is not the spotting side, so opening it then produced a stale window
            // over a live AI turn — and the deploy overlay it implies belongs to whoever is moving.
            if (map.currentPlayer?.type != PlayerType.HUMAN_LOCAL) return
            CompactEquipmentNavigation.showList()
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
            // Back to the battle: whatever the menu was holding back gets its turn now.
            MessageDialogs.resumeDynamicMessages()
            byId("options")?.let { toggleButton(it, false) }
        } else {
            // An open message box (e.g. the scenario-intro briefing left unread) sits on
            // --z-msg, ABOVE the pause menu's own layer — its title floated over the main
            // menu. Dismiss it through its own OK path so any pending
            // callback/uiMessageClicked flag runs exactly as if the player clicked OK.
            if (isVisible("ui-message")) byId("uiokbut")?.click()
            // The QUEUED boxes (hero emergence, casualties, prototype awards) are the same layer
            // and were the other half of that report — they have no callback to run, so they are
            // held rather than dismissed and come back when the player continues.
            MessageDialogs.suspendDynamicMessages()
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
     * **The branch order IS the z-order, and it has to be maintained by hand.**
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
            org.osada.ui.music.MusicLibraryPresenter
                .isOpen() ->
                org.osada.ui.music.MusicLibraryPresenter
                    .close()
            isVisible("ui-message") -> byId("uiokbut")?.click()
            // Same `--z-msg` tier as `ui-message`, and previously unregistered here: Escape fell
            // through to the pause menu and opened it UNDER the popup that was still on screen.
            MessageDialogs.isDynamicMessageOpen() -> MessageDialogs.dismissDynamicMessage()
            HeroPromotionPresenter.isOpen() -> Unit // modal by design — see the doc comment
            CommanderRosterPresenter.isTransferPickerOpen() -> CommanderRosterPresenter.closeTransferPicker()
            ModalPickers.isOpen() -> ModalPickers.close()
            CommanderRosterPresenter.isOpen() -> CommanderRosterPresenter.close()
            // The Hero Desk layers a dossier over itself, so Escape has to peel them in that order
            // -- the same ordered-stack rule the transfer picker above follows.
            HeroDeskPresenter.isDossierOpen() -> HeroDeskPresenter.closeDossier()
            HeroDeskPresenter.isOpen() -> HeroDeskPresenter.close()
            isVisible("equipment") -> byId("eqCloseBut")?.click()
            isVisible("combatLog") -> UICombatLog.toggleCombatLog(false, true)
            MinimapPings.armed -> MinimapPings.cancel()
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

/**
 * The ONE writer of the two map-layer toggles, which are mutually exclusive: Air mode shows the
 * air-defence hatch and Artillery the gun hatch, drawn in the same two inks, so both at once would
 * leave the player unable to tell which envelope a red hex belongs to. Switching one on switches
 * the other off, and both sidebar buttons are resynchronised here so neither can show a stale state.
 */
internal fun setMapLayerMode(
    air: Boolean,
    artillery: Boolean,
) {
    uiSettings.airMode = air
    uiSettings.artilleryRange = artillery && !air
    byId("air")?.let { toggleButton(it, uiSettings.airMode) }
    byId("arty")?.let { toggleButton(it, uiSettings.artilleryRange) }
}
