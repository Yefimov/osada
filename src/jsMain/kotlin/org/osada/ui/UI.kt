@file:OptIn(ExperimentalJsExport::class)

package org.osada.ui

import kotlinx.browser.document
import org.osada.CombatLog
import org.osada.Game
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.getCountriesBySide
import org.osada.model.selectUnit
import org.osada.uiSettings

/**
 * Exported UI coordinator. Owns the [Render] instance and the per-side country list, and
 * delegates to focused collaborators:
 * - [AnimationOrchestrator] — move and attack animation sequences
 * - [UnitInfoPanel] — unit/equipment info display and unit-context actions
 * - [EquipmentWindowController] — equipment window population and buy/upgrade/sell
 * - [StartMenuButtonHandler] / [MainMenuButtonHandler] — start-menu and main-menu button actions
 * - [StatusBarController] — status bar, weather tooltip, objectives panel
 * - [ReadyUnitNavigator] / [EndTurnFlow] — ready-unit navigator and end-of-turn sequencing
 * - [StrategicZoomController] — strategic (zoomed-out) map view
 * - [MapInputController] — cursor state and map mouse events
 *
 * All public method signatures are preserved unchanged (exported surface). Most methods live
 * as extension functions in the sibling `UIViewport.kt`, `UIToolTips.kt`, `UIActionDelegates.kt`
 * and `UIPanelBridges.kt` files (same package) to stay within the project's function-count
 * limits; only the members that legacy JS reaches DYNAMICALLY by real name (via `gameRef()` —
 * [startMenuButton], [uiSetCellOnViewPort], [updateStatusBar]) plus the scenario lifecycle
 * remain real members, because a dynamic call cannot see a Kotlin extension function.
 */
@JsExport
@JsName("UI")
class UI(
    internal val game: Game,
) {
    internal val render = Render(game.scenario?.map)
    internal var countriesOnSpotSide: Array<Int> = emptyArray()

    internal val animationOrchestrator = AnimationOrchestrator(this)
    internal val unitInfoPanel = UnitInfoPanel(this)
    internal val eqWindowController = EquipmentWindowController(this)
    private val startMenuButtonHandler = StartMenuButtonHandler(this)
    internal val mainMenuButtonHandler = MainMenuButtonHandler(this)
    internal val statusBarController = StatusBarController(this)
    internal val readyUnitNavigator = ReadyUnitNavigator(this)
    internal val endTurnFlow = EndTurnFlow(this, readyUnitNavigator)
    internal val strategicZoomController = StrategicZoomController(this)
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
        statusBarController.updateStatusBar()
        mainMenuButtonHandler.checkUndeployedUnits()
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
                mainMenuButtonHandler.handleGlobalEscape()
            }
        })
    }

    fun uiSetCellOnViewPort(cell: Cell): Boolean {
        val gameDiv = byId("game") ?: return false
        val screenPos = render.cellToScreen(cell.row, cell.col, true)
        gameDiv.asDynamic().scrollLeft = screenPos.x - (windowInnerWidth() / 2)
        gameDiv.asDynamic().scrollTop = screenPos.y - (windowInnerHeight() / 2)
        return true
    }

    // Map zoom lives in MapZoom.set() (needs anchor/scroll math this class doesn't otherwise
    // touch) — the Settings "Game Map scale" slider calls it directly now.

    fun uiUnitSelect(unit: GameUnit): Boolean {
        game.scenario?.map?.selectUnit(unit)
        buildUnitContext(unit)
        showUnitInfo(unit)
        render.render()
        MinimapBuilder.refresh()
        AttackRingBuilder.refresh()
        return true
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
        statusBarController.updateStatusBar()
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
            statusBarController.updateStatusBar()
            mainMenuButtonHandler.checkUndeployedUnits()
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
        val opened =
            UIBuilder.reopenScenarioBriefing {
                game.uiMessageClicked = true
                game.processTurn()
            }
        if (!opened) game.uiMessageClicked = true
        return opened
    }

    // ---- Menu button handlers ----

    fun startMenuButton(id: String) = startMenuButtonHandler.startMenuButton(id)

    // Public (not internal): called via the dynamic `gameRef()` pattern from StartMenuBuilder
    // (settings checkbox / OK handlers) — a dynamic-typed call to an internal member silently
    // resolves to nothing at runtime (Kotlin/JS only exposes public members by real name to
    // dynamic/JS callers), the same pitfall this session already hit with showEnemyCard.
    fun updateStatusBar() = statusBarController.updateStatusBar()

    private fun windowInnerWidth(): Int = js("window.innerWidth") as Int

    private fun windowInnerHeight(): Int = js("window.innerHeight") as Int
}
