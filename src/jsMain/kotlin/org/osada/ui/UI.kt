@file:OptIn(ExperimentalJsExport::class)

package org.osada.ui

import kotlinx.browser.document
import org.osada.*
import org.osada.model.*
import org.osada.rules.GameRules
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Exported UI coordinator. Owns the [Render] instance and the per-side country list, and
 * delegates to focused collaborators:
 * - [AnimationOrchestrator] — move and attack animation sequences
 * - [UnitInfoPanel] — unit/equipment info display and unit-context actions
 * - [EquipmentWindowController] — equipment window population and buy/upgrade/sell
 * - [MenuController] — start-menu and main-menu button handlers, status bar, strategic zoom
 * - [MapInputController] — cursor state and map mouse events
 *
 * All public method signatures are preserved unchanged (exported surface).
 */
@JsExport
@JsName("UI")
class UI(internal val game: Game) {
    internal val render = Render(game.scenario?.map)
    internal var countriesOnSpotSide: Array<Int> = emptyArray()

    private val animationOrchestrator = AnimationOrchestrator(this)
    private val unitInfoPanel = UnitInfoPanel(this)
    private val eqWindowController = EquipmentWindowController(this)
    private val menuController = MenuController(this)
    private val mapInputController = MapInputController(this)

    init {
        UIBuilder.buildStartMenu()
        UIBuilder.buildMainMenu()
        SidebarBuilder.buildSidebar()
        HudLog.reset()
        UIBuilder.buildUnitInfoWindow()
        BottomZoneBuilder.build()
        MinimapBuilder.build()
        AttackRingBuilder.build()
        UIBuilder.buildEquipmentWindow()
        UIBuilder.buildEquipmentSortOptions()
        UIBuilder.setDefaultUserSelections()
        UIBuilder.setEquipmentFlags(game.scenario?.eqp)
        menuController.updateStatusBar()
        menuController.checkUndeployedUnits()
        // Scenario briefings are opened by setNewScenario() after map resources are ready. Keeping
        // the constructor silent prevents duplicate briefings and replay after save restoration.
        render.cacheImages { }
        uiSettings.hasTouch = hasTouch()
        mapInputController.attachMapEventListeners()

        // Escape opens/closes the pause menu, or closes whatever modal is topmost — registered
        // once here since UI is constructed exactly once per page load (Game reuses this instance
        // across scenario/campaign transitions).
        document.addEventListener("keydown", { e ->
            if ((e.asDynamic().key as? String) == "Escape" && !UIBuilder.isScenarioBriefingVisible()) {
                menuController.handleGlobalEscape()
            }
        })
    }

    // ---- Map view helpers ----

    fun uiSetUnitOnViewPort(unit: GameUnit): Boolean {
        val pos = unit.getPos() ?: return false
        return uiSetCellOnViewPort(pos)
    }

    fun uiSetCellOnViewPort(cell: Cell): Boolean {
        val gameDiv = byId("game") ?: return false
        val screenPos = render.cellToScreen(cell.row, cell.col, true)
        gameDiv.asDynamic().scrollLeft = screenPos.x - (windowInnerWidth() / 2)
        gameDiv.asDynamic().scrollTop = screenPos.y - (windowInnerHeight() / 2)
        return true
    }

    /** Scrolls [unit] into view ONLY if it isn't already comfortably visible — unlike
     *  [uiSetUnitOnViewPort] (an unconditional re-center, correct for "jump to" navigation:
     *  clicking an objective, the ready-unit nav), forcibly re-centering after every LOCAL move
     *  shifts everything on screen right as the player is about to click their next target — e.g.
     *  drive a tank up next to an enemy, and by the time the move animation ends the enemy has
     *  slid to a different screen position than where the player was about to click, so the click
     *  lands on the wrong hex instead of the attack. */
    fun uiScrollUnitIntoView(unit: GameUnit): Boolean {
        val pos = unit.getPos() ?: return false
        val gameDiv = byId("game")?.asDynamic() ?: return false
        val screenPos = render.cellToScreen(pos.row, pos.col, true)
        val clientWidth = (gameDiv.clientWidth as? Number)?.toDouble() ?: return uiSetUnitOnViewPort(unit)
        val clientHeight = (gameDiv.clientHeight as? Number)?.toDouble() ?: return uiSetUnitOnViewPort(unit)
        val scrollLeft = (gameDiv.scrollLeft as? Number)?.toDouble() ?: 0.0
        val scrollTop = (gameDiv.scrollTop as? Number)?.toDouble() ?: 0.0
        // Margin so the unit isn't left flush against the very edge either — still comfortably
        // clickable/visible, just not dead-center.
        val marginX = clientWidth * 0.15
        val marginY = clientHeight * 0.15
        val inView = screenPos.x >= scrollLeft + marginX && screenPos.x <= scrollLeft + clientWidth - marginX &&
            screenPos.y >= scrollTop + marginY && screenPos.y <= scrollTop + clientHeight - marginY
        return if (inView) true else uiSetUnitOnViewPort(unit)
    }

    // Map zoom lives in MapZoom.set() (needs anchor/scroll math this class doesn't otherwise
    // touch) — the Settings "Game Map scale" slider calls it directly now.

    // ---- Unit selection / movement / combat (delegate to orchestrator) ----

    fun uiUnitSelect(unit: GameUnit): Boolean {
        game.scenario?.map?.selectUnit(unit)
        buildUnitContext(unit)
        showUnitInfo(unit)
        render.render()
        MinimapBuilder.refresh()
        AttackRingBuilder.refresh()
        return true
    }

    fun uiUnitMove(unit: GameUnit, row: Int, col: Int): Boolean =
        animationOrchestrator.uiUnitMove(unit, row, col)

    fun uiUnitAttack(attacker: GameUnit, defender: GameUnit): Boolean =
        animationOrchestrator.uiUnitAttack(attacker, defender)

    // ---- Tooltip / alert helpers ----

    fun showAlert(row: Int, col: Int, text: String, friendly: Boolean) {
        val pos = render.cellToScreen(row, col, true)
        val color = if (friendly) TooltipColor.PLAYER else TooltipColor.ENEMY
        UIBuilder.gameSmallToolTip(text, pos.x.toInt(), pos.y.toInt(), color, null, TooltipStyle.TEXT)
    }

    fun showGameToolTip(message: String, row: Int, col: Int) {
        val pos = render.cellToScreen(row, col, true)
        UIBuilder.gameToolTip(message, pos.x.toInt(), pos.y.toInt())
    }

    fun removeAllSmallToolTips(clearUnitTooltips: Boolean = false) {
        val list = UIBuilder.smallToolTipList.toList()
        list.reversed().forEach { id ->
            if (clearUnitTooltips || !id.startsWith("gsttu")) {
                delTag(byId(id))
                UIBuilder.smallToolTipList.remove(id)
            }
        }
    }

    fun addSmallToolTips(all: Boolean = false) {
        val map = game.scenario?.map ?: return
        val currentPlayer = map.currentPlayer ?: return
        val side = currentPlayer.side
        val rows = map.rows
        val cols = map.cols
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val hex = map.map?.get(r)?.get(c) ?: continue
                var text: String? = null
                var color = TooltipColor.ENEMY
                var style = TooltipStyle.TEXT
                if (!all && hex.flag != -1 && hex.owner != -1) {
                    text = if (hex.name.isNotEmpty()) hex.name else if (hex.victorySide != -1) "Objective" else null
                    if (!uiSettings.showDetailInfoToolTips && hex.victorySide == -1) text = null
                    // hex.owner is a player id, not a side; comparing directly misreports
                    // ownership color whenever a side has more than one player (pre-existing bug,
                    // same root cause as the sidebar objectives "held" bug this session fixed).
                    if (map.getPlayer(hex.owner).side == side) color = TooltipColor.PLAYER
                    if (hex.terrain == TerrainType.AIRFIELD.value && currentPlayer.airTransports > 0) {
                        text = "${currentPlayer.airTransports}&nbsp;<span style='font-family: openpanzer-menu;'>&#xe900;</span> "
                        style = TooltipStyle.PIN
                    }
                    if (hex.terrain == TerrainType.PORT.value && currentPlayer.navalTransports > 0) {
                        text = "${currentPlayer.navalTransports}&nbsp;<span style='font-family: openpanzer-menu;'>&#xe901;</span>"
                        style = TooltipStyle.PIN
                    }
                    text?.let {
                        val pos = render.cellToScreen(r, c, true)
                        UIBuilder.gameSmallToolTip(it, pos.x.toInt(), pos.y.toInt(), color, null, style)
                    }
                }
                val unit = hex.getUnit(uiSettings.airMode)
                if (unit != null && unit.player?.side == side && text == null) {
                    val unitTipId = "gsttu${unit.id}"
                    if (GameRules.unitLowAmmo(unit, 1)) {
                        val pos = render.cellToScreen(r, c, true)
                        UIBuilder.gameSmallToolTip("No Ammo", pos.x.toInt(), pos.y.toInt(), 0, unitTipId, TooltipStyle.TEXT)
                    }
                    if (GameRules.unitLowFuel(unit, 1)) {
                        val pos = render.cellToScreen(r, c, true)
                        UIBuilder.gameSmallToolTip("No Fuel", pos.x.toInt(), pos.y.toInt(), 0, unitTipId, TooltipStyle.TEXT)
                    }
                    // Bad weather silently empties an air unit's attack range (CombatResolver.
                    // airGroundedByWeather) with zero explanation otherwise — reads exactly like a
                    // bug ("my plane can't shoot") rather than the OG rule it actually is. Own id
                    // suffix (not the shared unitTipId): gameSmallToolTip sets the DOM id literally,
                    // so reusing unitTipId here would collide with the ammo/fuel tooltip's own id
                    // for the same unit if more than one condition is true at once.
                    if (GameRules.isAir(unit) && GameRules.airGroundedByWeather(unit)) {
                        val pos = render.cellToScreen(r, c, true)
                        // "Grounded" not "Grounded (Weather)": .smallToolTip is a fixed 101x15px
                        // box sized for "No Ammo"/"No Fuel" — the longer text overflowed it, with
                        // "(Weather)" clipped outside the box. Matches the unit-card badge's own
                        // wording (osadaUcWeather), which carries the full explanation on hover.
                        UIBuilder.gameSmallToolTip("Grounded", pos.x.toInt(), pos.y.toInt(), 0, "${unitTipId}w", TooltipStyle.TEXT)
                    }
                }
            }
        }
    }

    fun removeUnitToolTip(unitId: Int) {
        val id = "gsttu$unitId"
        delTag(byId(id))
        UIBuilder.smallToolTipList.remove(id)
    }

    // ---- Scenario lifecycle ----

    fun setNewScenario() {
        val map = game.scenario?.map ?: return
        val rawBriefing = game.takeScenarioBriefing()
        val showBriefing = game.takeScenarioBriefingEnabled()
        // Prevent AI or scripted turns from running while the briefing is loading or visible.
        game.uiMessageClicked = !showBriefing
        render.setNewMap(map)
        countriesOnSpotSide = map.getCountriesBySide(game.spotSide)
        UIBuilder.setEquipmentFlags(game.scenario?.eqp)
        UIBuilder.setDefaultUserSelections()
        CombatLog.reset()
        HudLog.reset()
        TurnSleep.reset()
        UICombatLog.forceClose()
        makeHidden("statusbar-extension")
        UIBuilder.closeDossier()
        // Operational sidebar is a flex column; makeVisible would set display:inline.
        byId("osada-sidebar")?.style?.display = "flex"
        menuController.updateStatusBar()
        console.log("[OSADA] UI.setNewScenario starting image cache")
        render.cacheImages {
            console.log("[OSADA] UI.setNewScenario cacheImages callback")
            render.render()
            render.setIconsetTint(game.scenario?.iconset ?: 0)
            // Build hex name/objective tooltips AFTER positionLayers has run inside cacheImages —
            // doing it before (stale/zero game geometry) placed labels off the map (e.g. "Sirki",
            // "Objective" floating beyond the edge until a scenario restart).
            removeAllSmallToolTips()
            addSmallToolTips()
            eqWindowController.updateEquipmentWindow(UnitClass.TANK.value)
            menuController.updateStatusBar()
            menuController.checkUndeployedUnits()
            WeatherRenderer.start(game.scenario?.atmosferic ?: 0)
            WeatherModel.init(game.scenario)
            if (!showBriefing) {
                UIBuilder.clearScenarioBriefing()
                game.uiMessageClicked = true
            } else {
                val title = game.scenario?.name ?: ""
                val intro = game.scenario?.getDescription() ?: ""
                val finishOpening = {
                    game.uiMessageClicked = true
                    game.processTurn()
                }
                val showLegacyScenarioMessage = {
                    // Preserve the original scenario-opening popup after the campaign conversation
                    // and operational summary. Standalone scenarios use this path directly.
                    UIBuilder.message(title, intro, narrative = true, callback = finishOpening)
                }
                if (game.campaign != null && rawBriefing != null && rawBriefing != undefined) {
                    UIBuilder.showScenarioBriefing(title, rawBriefing, showLegacyScenarioMessage)
                } else {
                    UIBuilder.clearScenarioBriefing()
                    showLegacyScenarioMessage()
                }
            }
        }
    }

    /** Reopen the latest campaign briefing without changing scenario or campaign state. */
    fun reopenScenarioBriefing(): Boolean {
        game.uiMessageClicked = false
        val opened = UIBuilder.reopenScenarioBriefing {
            game.uiMessageClicked = true
            game.processTurn()
        }
        if (!opened) game.uiMessageClicked = true
        return opened
    }

    fun uiEndTurnInfo() {
        render.render()
        menuController.showStatusExtension()
    }

    /** Refresh the status-bar weather/ground glyph after the per-turn weather simulation changes it. */
    fun refreshWeatherDisplay() {
        menuController.updateStatusBar()
    }

    fun toggleUnitsAndEquipmentWindow(show: Boolean) = eqWindowController.toggleUnitsAndEquipmentWindow(show)

    fun handleReinforcementDeployment() = eqWindowController.handleReinforcementDeployment()

    // ---- Menu button handlers ----

    fun startMenuButton(id: String) = menuController.startMenuButton(id)

    fun mainMenuButton(id: String) = menuController.mainMenuButton(id)

    // Public (not internal): called via the dynamic `gameRef()` pattern from StartMenuBuilder
    // (settings checkbox / OK handlers) — a dynamic-typed call to an internal member silently
    // resolves to nothing at runtime (Kotlin/JS only exposes public members by real name to
    // dynamic/JS callers), the same pitfall this session already hit with showEnemyCard.
    fun updateStatusBar() = menuController.updateStatusBar()

    /** Top-bar turn controls (ready-unit navigator + End Turn state); refreshed after actions. */
    internal fun updateTurnControls() = menuController.updateTurnControls()

    fun cycleReadyUnit(direction: Int) = menuController.cycleReadyUnit(direction)

    fun onEndTurnClick() = menuController.onEndTurnClick()

    internal fun isUnitAsleep(unit: GameUnit): Boolean = menuController.isUnitAsleep(unit)

    internal fun toggleUnitSleep(unit: GameUnit) = menuController.toggleUnitSleep(unit)

    internal fun hasAnyAction(unit: GameUnit): Boolean = menuController.hasAnyAction(unit)

    // ---- Equipment window ----

    fun equipmentWindowButtons(action: String) = eqWindowController.equipmentWindowButtons(action)

    fun updateEquipmentWindow(unitClass: Int) = eqWindowController.updateEquipmentWindow(unitClass)

    // ---- Internal cross-collaborator bridges (same package, not exported) ----

    internal fun buildUnitContext(unit: GameUnit?) = unitInfoPanel.buildUnitContext(unit)

    internal fun showUnitInfo(unit: GameUnit?) = unitInfoPanel.showUnitInfo(unit)

    internal fun showEquipmentInfo(eq: EquipmentData?) = unitInfoPanel.showEquipmentInfo(eq)

    internal fun updateHoverInfo(row: Int, col: Int) = unitInfoPanel.updateHoverInfo(row, col)

    internal fun showEnemyCard(unit: GameUnit) = unitInfoPanel.showEnemyCard(unit)

    /** Restore the unit-info panel after closing the buy/deploy window (hide unless user-pinned). */
    internal fun hideUnitInfoIfNotPinned() = unitInfoPanel.hideUnitInfoIfNotPinned()

    internal fun toggleStrategicZoom() = menuController.toggleStrategicZoom()

    private fun windowInnerWidth(): Int = js("window.innerWidth") as Int
    private fun windowInnerHeight(): Int = js("window.innerHeight") as Int
}
