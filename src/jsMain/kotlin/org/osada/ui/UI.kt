package org.osada.ui

import org.osada.CombatLog
import org.osada.Game
import org.osada.UnitClass
import org.osada.evaluateScenarioEvents
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.getCountriesBySide
import org.osada.model.getCountryName
import org.osada.model.selectUnit
import org.osada.scenario.objectiveReport
import org.osada.ui.briefing.BriefingIntroTracker
import org.osada.ui.briefing.ScenarioFacts
import org.osada.ui.keyboard.CommandRouter
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

    // The campaign ceremony now opens BEFORE the map's images are cached, so the player can
    // finish reading before the map is drawable. These two carry that race: BEGIN is honoured
    // immediately once the map is ready, and parked until then if it is not.
    private var mapReady = false
    private var pendingBriefingFinish: (() -> Unit)? = null

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
        // Layout services first: the pointer pipeline, the renderer and the drawer all ask
        // MobileLayoutController which shell they are in, so it must have measured once already.
        MobileLayoutController.install()
        MobileDrawer.install()
        // Kept only as a derived compatibility flag for the few renderers that still read it
        // (attack-cursor sizing). Layout and input decisions go through MobileLayoutController —
        // `('ontouchstart' in window)` cannot tell a phone from a touchscreen laptop.
        uiSettings.hasTouch = MobileLayoutController.isCoarsePointer
        mapInputController.attachMapEventListeners()
        MobileOnboarding.showIfNeeded()

        // The one document-level gameplay keyboard listener, registered once here since UI is
        // constructed exactly once per page load (Game reuses this instance across scenario/
        // campaign transitions). It owns Escape as well: a second independent listener would fire
        // on the same press, which is how Escape once closed a window underneath a modal
        // (DEFERRED.md §4.13).
        CommandRouter.install(this)
    }

    /**
     * Centres [cell] in the MAP viewport — `#game`'s own client box — not in the browser window.
     * The window includes the top bar, the bottom dock and any device cutout, so centring against
     * it pushed the target cell down behind the HUD on any short landscape phone. `#game` already
     * excludes all three, so no compensating offset is added here (spec §35).
     */
    fun uiSetCellOnViewPort(cell: Cell): Boolean {
        val gameDiv = byId("game")?.asDynamic() ?: return false
        val screenPos = render.cellToScreen(cell.row, cell.col, true)
        val clientWidth = (gameDiv.clientWidth as? Number)?.toDouble() ?: 0.0
        val clientHeight = (gameDiv.clientHeight as? Number)?.toDouble() ?: 0.0
        val maxLeft = (((gameDiv.scrollWidth as? Number)?.toDouble() ?: 0.0) - clientWidth).coerceAtLeast(0.0)
        val maxTop = (((gameDiv.scrollHeight as? Number)?.toDouble() ?: 0.0) - clientHeight).coerceAtLeast(0.0)
        gameDiv.scrollLeft = (screenPos.x - clientWidth / 2.0).coerceIn(0.0, maxLeft)
        gameDiv.scrollTop = (screenPos.y - clientHeight / 2.0).coerceIn(0.0, maxTop)
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
        mapReady = false
        pendingBriefingFinish = null
        // The campaign conversation opens BEFORE cacheImages rather than in its callback. The
        // briefing is a full-screen overlay with its own backdrop, so raising it first means the
        // player goes start menu -> conversation without the bare, half-drawn map flashing
        // between them, and the unit/terrain images finish loading underneath while they read.
        // Standalone scenarios keep the legacy timing: their small popup still waits for the map,
        // because it is a message ABOUT a map the player is looking at.
        val campaignCeremony = showBriefing && game.campaign != null
        if (campaignCeremony) {
            openScenarioCeremony(rawBriefing)
            // The ceremony is its own full-screen backdrop and outranks the curtain, so the curtain
            // has nothing left to hide and would only sit between the briefing and the map it is
            // deliberately covering for.
            ScenarioLoadingCurtain.hide()
        }
        console.log("[OSADA] UI.setNewScenario starting image cache")
        render.cacheImages {
            console.log("[OSADA] UI.setNewScenario cacheImages callback")
            render.render()
            // The new battle is now painted; this is the earliest moment the curtain can come down
            // without exposing a half-drawn map.
            ScenarioLoadingCurtain.hide()
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
            mapReady = true
            if (!showBriefing) {
                UIBuilder.clearScenarioBriefing()
                game.uiMessageClicked = true
                // The restore path never reaches releaseToBattle, so scenario events get their
                // start-of-battle evaluation here instead. Already-fired events are restored as
                // fired and are no-ops; this is what stops a save taken before an authored `start`
                // event fired from resuming into a battle where it never happens.
                game.evaluateScenarioEvents()
            } else if (!campaignCeremony) {
                openScenarioCeremony(rawBriefing)
            }
            // A player who finished the ceremony faster than the images loaded is released here.
            pendingBriefingFinish?.let {
                pendingBriefingFinish = null
                it()
            }
        }
    }

    /** Hands control to the battle once BOTH the opening ceremony is done and the map is
     *  drawable. Whichever finishes second calls the other's completion. */
    private fun releaseToBattle() {
        val start = {
            // Dialogue decisions and their immediate campaign effects are committed by now, while
            // neither the player nor the AI has received control yet: this is the true mission start.
            // Authored `start` scenario events run FIRST, before the checkpoint is taken, for two
            // reasons: they may depend on a flag a briefing choice just set, and "Restart mission"
            // must reproduce the same opening situation rather than a map without them.
            game.evaluateScenarioEvents()
            game.missionRestartCheckpoint.capture()
            game.uiMessageClicked = true
            game.processTurn()
        }
        if (mapReady) start() else pendingBriefingFinish = start
    }

    /** Campaign scenarios always get the briefing ritual (dialogue if authored, briefing
     *  always); standalone scenarios keep the legacy small popup, augmented only with any
     *  engine-authored extended conditions the scenario description may have omitted. */
    private fun openScenarioCeremony(rawBriefing: dynamic) {
        val tutorial = game.scenario?.file == Game.defaultScenario
        val title =
            if (tutorial) I18n.t("tutorial.welcome.title") else game.scenario?.name ?: ""
        val intro =
            if (tutorial) I18n.t("tutorial.welcome.body") else game.scenario?.getDescription() ?: ""
        val extendedObjectives =
            game.scenario
                ?.objectiveReport(game.spotSide, revealHidden = false)
                ?.extended
                .orEmpty()
        val finishOpening = { releaseToBattle() }
        val campaign = game.campaign
        if (campaign == null) {
            // Standalone scenarios still get no dialogue or full briefing, but imported engine
            // conditions must not remain invisible until the player happens to trigger one.
            UIBuilder.clearScenarioBriefing()
            UIBuilder.message(
                title,
                extendedObjectiveOpeningHtml(intro, extendedObjectives),
                narrative = true,
                callback = finishOpening,
            )
            return
        }

        val facts =
            ScenarioFacts(
                title = title,
                // Not `toDateString()`: its expanded-year form for a BC date is implementation
                // defined, and it is not localized either.
                dateLabel =
                    game.scenario
                        ?.date
                        ?.let(GameplayLocalization::scenarioDateLabel)
                        .orEmpty(),
                sidesLabel = sidesLabel(),
                ordersText = intro,
                extendedObjectives = extendedObjectives,
            )
        val campaignFile = campaign.file
        val scenarioFile = game.scenario?.file ?: ""
        if (BriefingIntroTracker.isSeen(campaignFile, scenarioFile)) {
            // Retry fast-path: keep the briefing reachable via the reopen button, but skip
            // straight to gameplay — no dialogue, no briefing window.
            UIBuilder.primeScenarioBriefing(campaignFile, scenarioFile, facts, rawBriefing)
            finishOpening()
        } else {
            BriefingIntroTracker.markSeen(campaignFile, scenarioFile)
            // The campaign ritual replaces the small legacy scenario-start popup entirely; its
            // text now lives as the briefing's final ORDERS section (facts.ordersText above).
            UIBuilder.showScenarioBriefing(campaignFile, scenarioFile, facts, rawBriefing, finishOpening)
        }
    }

    /** Reopen the latest campaign briefing without changing scenario or campaign state.
     *  CombatLogHeader reaches it dynamically, which is why it must stay a real public member of
     *  this @JsExport class. */
    @Suppress("unused")
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

    // Prefer real country names ("Romania vs Soviet Union") over the generic side labels; each side's
    // first country is its primary belligerent. Falls back to Axis/Allies when the map has no players.
    private fun sidesLabel(): String {
        val map = game.scenario?.map
        val name0 = map?.getCountriesBySide(0)?.firstOrNull()?.let { Equipment.getCountryName(it) }
        val name1 = map?.getCountriesBySide(1)?.firstOrNull()?.let { Equipment.getCountryName(it) }
        val real = { name: String? -> !name.isNullOrBlank() && name != "Unknown" }
        if (real(name0) && real(name1)) {
            return I18n.t("briefing.sides", mapOf("left" to name0, "right" to name1))
        }
        val axis = GameText.side(0)
        val allies = GameText.side(1)
        val commanded = game.campaignPlayer?.getSideName()
        return if (commanded != null) {
            I18n.t(
                "briefing.sides.commanded",
                mapOf("left" to axis, "right" to allies, "commanded" to commanded),
            )
        } else {
            I18n.t("briefing.sides", mapOf("left" to axis, "right" to allies))
        }
    }
}
