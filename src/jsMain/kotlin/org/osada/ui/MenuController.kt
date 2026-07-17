package org.osada.ui

import kotlinx.browser.window
import org.osada.CombatLog
import org.osada.Game
import org.osada.GroundCondition
import org.osada.PlayerType
import org.osada.UnitClass
import org.osada.WeatherCondition
import org.osada.groundConditionNames
import org.osada.groundIconImg
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.monthNamesShort
import org.osada.rules.GameRules
import org.osada.uiSettings
import org.osada.weatherConditionNames
import org.osada.weatherIconImg
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.MouseEvent

/**
 * Handles start-menu and main-menu button actions, status-bar updates, strategic zoom, and
 * end-of-turn UI sequencing. Extracted from the former [UI] god-class (SRP).
 */
internal class MenuController(private val ui: UI) {

    fun startMenuButton(id: String) {
        when (id) {
            "newgame" -> makeVisible("smNewGame")
            "newcampaign" -> {
                makeHidden("smNewGame")
                makeHidden("smMain")
                makeVisible("smCamp")
                val campSelect = byId("smCampSel")?.firstChild as? HTMLSelectElement
                val campaign = ui.game.campaign
                if (campaign == null) {
                    campSelect?.let { it.selectedIndex = 0 }
                } else {
                    campSelect?.let { setSelectOption(it, campaign.name) }
                }
                (byId("smCampSel")?.firstChild as? HTMLSelectElement)?.let { triggerChange(it) }
            }
            "newscenario" -> {
                makeHidden("smNewGame")
                makeHidden("smMain")
                makeVisible("smScen")
                val scenSelect = byId("smScenSel")?.firstChild as? HTMLSelectElement
                val allScenarios: Array<dynamic> = js(
                    "typeof scenariolist !== 'undefined' ? scenariolist : []",
                ).unsafeCast<Array<dynamic>>()
                val defaultEntry = allScenarios.firstOrNull {
                    (it.length as? Int ?: 0) > 1 &&
                        it[0] as? String == Game.defaultScenario
                }
                val scenarioName = ui.game.scenario?.name
                    ?: (if (defaultEntry != null) defaultEntry[1] as? String else null)
                if (scenarioName != null) scenSelect?.let { setSelectOption(it, scenarioName) }
                (byId("smScenSel")?.firstChild as? HTMLSelectElement)?.let { triggerChange(it) }
            }
            "tutorial" -> {
                makeHidden("smMain")
                makeHidden("startmenu")
                ui.game.campaign = null
                uiSettings.noFOW = false
                uiSettings.isAI[0] = 2
                uiSettings.isAI[1] = 2
                ui.game.newScenario(Game.defaultScenario, null)
                byId("options")?.let { toggleButton(it, false) }
            }
            "continuegame" -> {
                makeHidden("startmenu")
                byId("options")?.let { toggleButton(it, false) }
            }
            "saveload" -> {
                makeHidden("smMain")
                makeHidden("smNewGame")
                // Pre-game the window is "Load Game" with Save muted; mid-game the full pair.
                GameStateMenuBuilder.applySaveLoadContext()
                makeVisible("smState")
            }
            "settings" -> {
                makeHidden("startmenu")
                makeHidden("smNewGame")
                makeVisible("smSettings")
                if (isVisible("ui-message")) {
                    makeHidden("ui-message")
                    byId("smSettings")?.asDynamic()?.messageHidden = true
                }
            }
        }
    }

    fun mainMenuButton(id: String) {
        val map = ui.game.scenario?.map ?: return
        when (id) {
            "air" -> {
                if (uiSettings.airMode && GameRules.isAir(map.currentUnit)) {
                    map.delCurrentUnit()
                }
                uiSettings.airMode = !uiSettings.airMode
                byId("air")?.let { toggleButton(it, uiSettings.airMode) }
                ui.render.render()
            }
            "hex" -> {
                uiSettings.hexGrid = !uiSettings.hexGrid
                byId("hex")?.let { toggleButton(it, uiSettings.hexGrid) }
                ui.removeAllSmallToolTips()
                ui.addSmallToolTips()
                ui.render.render()
            }
            "zoom" -> {
                toggleStrategicZoom()
                ui.render.render()
            }
            "inspectunit" -> {
                val unitInfo = byId("unit-info")
                if (unitInfo != null) {
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
            }
            "buy" -> {
                val equipment = byId("equipment")
                if (equipment != null && isVisible("equipment")) {
                    makeHidden("equipment")
                    makeHidden("container-unitlist")
                    uiSettings.deployMode = false
                    byId("buy")?.let { toggleButton(it, false) }
                    ui.hideUnitInfoIfNotPinned()
                    // Restore the normal turn status; updateEquipmentWindow() had overwritten it
                    // with the deploy/"Units currently deployed on map." message (matches PM's z()).
                    updateStatusBar()
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
            "endturn" -> onEndTurnClick()
            "mainmenu" -> {
                val slideMenu = byId("slidemenu")
                if (slideMenu != null) {
                    if (isVisible("slidemenu")) {
                        makeHidden("slidemenu")
                        byId("mainmenu")?.let { toggleButton(it, false) }
                    } else {
                        makeVisible("slidemenu")
                        byId("mainmenu")?.let { toggleButton(it, true) }
                    }
                }
            }
            "options" -> {
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
        }
    }

    // Short weather/ground words for the top bar (spec: "Snow · Frozen").
    private val weatherWords = listOf("Clear", "Overcast", "Rain", "Snow") // by atmosferic 0..3

    /** Top-bar height the map area starts below — the same 30px RenderContext.positionLayers
     *  itself uses as #game's `top` (and #statusbar's own CSS height). */
    private val TOPBAR_H = 30.0

    fun updateStatusBar() {
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val currentPlayer = map.currentPlayer ?: return

        // --- scenario · turn · date (NOT the campaign name) ---
        val phaseChip = if (currentPlayer.hasUndeployedUnits() && currentPlayer.type == PlayerType.HUMAN_LOCAL) {
            "<span class=\"osada-tb-field osada-tb-field--phase\" title=\"Place your units on the highlighted deployment hexes\"><b>Phase</b>DEPLOY</span>"
        } else {
            ""
        }
        byId("statusmsg")?.innerHTML =
            "<span class=\"osada-tb-op\" title=\"${scenario.name}\">${scenario.name}</span>" +
            "<span class=\"osada-tb-field\"><b>Turn</b>${map.turn}/${map.maxTurns}</span>" +
            "<span class=\"osada-tb-field osada-tb-date\">${formatScenarioDate(scenario)}</span>" +
            phaseChip

        // --- weather as words + weather/ground image icons (asset-sheet extracts) ---
        val atmos = scenario.atmosferic
        val ground = scenario.ground
        byId("weathermsg")?.let { w ->
            w.innerHTML =
                weatherIconImg(atmos, "osada-tb-weather-img") +
                groundIconImg(ground, "osada-tb-weather-img") +
                "<span class=\"osada-tb-weather-txt\">${weatherWords.getOrNull(
                    atmos,
                ) ?: ""} · ${groundConditionNames.getOrNull(ground) ?: ""}</span>"
            // Rich hover panel replaces the bare native title: it spells out what the current
            // weather/ground actually DO to the rules — bonuses green, penalties red.
            w.asDynamic().title = ""
            w.onmouseenter = { _: org.w3c.dom.events.MouseEvent -> showWeatherTooltip(w) }
            w.onmouseleave = { _: org.w3c.dom.events.MouseEvent -> byId("osadaWeatherTip")?.style?.display = "none" }
        }

        updatePrestigeDisplay(currentPlayer, map)
        updateReservesBadge(currentPlayer)
        updateTurnControls()
        updateObjectivesPanel()
        updateObserverBadge()
        // Covers turn-change and (via the Task 1 move/attack-finish hooks that already call
        // updateStatusBar for the human player) unit-move/combat-end refresh triggers too.
        MinimapBuilder.refresh()
        AttackRingBuilder.refresh()
    }

    private fun formatScenarioDate(scenario: org.osada.scenario.Scenario): String {
        val d = scenario.date
        return "${d.getDate()} ${monthNamesShort.getOrNull(d.getMonth()) ?: ""} ${d.getFullYear()}"
    }

    // ---- Weather hover panel -----------------------------------------------------------------
    // Every effect line below states something the RULES actually do, sourced from:
    // CombatResolver.airGroundedByWeather (any non-Fair weather blocks air ATTACKS, defence still
    // works), Scenario.setMoveTable + movTableFrozen/movTableMud (frozen: rivers/swamps become
    // crossable, wheeled bogs down in forest; mud: most ground costs up, swamps shut), and
    // WeatherModel.onChange (rain→Mud / snow→Frozen only when the scenario sets weatherchg).

    /** Builds/shows the weather panel under the top bar, left-aligned to [anchor]. */
    private fun showWeatherTooltip(anchor: HTMLElement) {
        val scenario = ui.game.scenario ?: return
        val tip = byId("osadaWeatherTip") ?: run {
            val t = addTag("mainbody", "div")
            t.id = "osadaWeatherTip"
            t.className = "osada-wtip"
            t
        }
        tip.innerHTML = weatherTooltipHtml(scenario)
        tip.style.display = "block"
        val rect = anchor.asDynamic().getBoundingClientRect()
        val left = ((rect.left as? Number)?.toDouble() ?: 0.0)
            .coerceAtMost(window.innerWidth - 360.0) // keep the 340px panel on-screen
            .coerceAtLeast(6.0)
        tip.style.left = "${left.toInt()}px"
        tip.style.top = "${((rect.bottom as? Number)?.toDouble() ?: 40.0).toInt() + 6}px"
    }

    private fun weatherTooltipHtml(scenario: org.osada.scenario.Scenario): String {
        val atmos = scenario.atmosferic
        val ground = scenario.ground
        val story = StringBuilder()
        story.append(
            when (atmos) {
                WeatherCondition.FAIR.value -> "Clear skies over the front."
                WeatherCondition.OVERCAST.value -> "Low cloud hangs over the battlefield."
                WeatherCondition.RAIN.value -> "Steady rain soaks the front."
                else -> "Snow squalls sweep across the field."
            },
        )
        story.append(" ")
        story.append(
            when (ground) {
                GroundCondition.FROZEN.value -> "The earth is frozen hard."
                GroundCondition.MUD.value -> "The ground has turned to mud."
                else -> "The ground is firm."
            },
        )

        val lines = StringBuilder()
        fun line(kind: String, text: String) {
            lines.append("<div class=\"osada-wtip__line osada-wtip__line--$kind\">$text</div>")
        }
        if (atmos == WeatherCondition.FAIR.value) {
            line("good", "Aircraft operate freely — air attacks allowed.")
        } else {
            line("bad", "Aircraft cannot attack — grounded by the weather (they still defend themselves).")
        }
        when (ground) {
            GroundCondition.FROZEN.value -> {
                line("good", "Frozen rivers and swamps can be crossed by ground units.")
                line("bad", "Wheeled transport struggles off-road (forests cost all movement).")
            }
            GroundCondition.MUD.value -> {
                line("bad", "Ground movement much slower — wheeled vehicles bog down hardest.")
                line("bad", "Swamps are impassable morass.")
            }
            else -> line("good", "Firm going — normal movement costs for all units.")
        }
        if (scenario.weatherCanChangeGround) {
            when (atmos) {
                WeatherCondition.RAIN.value -> line(
                    "dim",
                    "Continued rain keeps the ground muddy; a clear spell dries it out.",
                )
                WeatherCondition.SNOW.value -> line("dim", "Snowfall keeps the ground frozen; a clear spell thaws it.")
                else -> {}
            }
        }

        val title = "${weatherConditionNames.getOrNull(atmos) ?: ""} · " +
            "${groundConditionNames.getOrNull(ground) ?: ""} ground"
        return "<div class=\"osada-wtip__title\">$title</div>" +
            "<div class=\"osada-wtip__story\">$story</div>" +
            lines.toString()
    }

    private fun updatePrestigeDisplay(player: Player, map: GameMap) {
        val el = byId("osadaPrestige") ?: return
        val delta = player.prestigePerTurn.getOrNull(map.turn) ?: 0
        val deltaHtml = if (delta > 0) "<span class=\"osada-tb-delta\">+$delta</span>" else ""
        el.innerHTML = "<span class=\"osada-tb-prestige-val\">${player.prestige}</span>$deltaHtml"
        el.title = "Prestige: ${player.prestige}" + if (delta > 0) " (+$delta next turn)" else ""
    }

    /** Task 5: persistent "OBSERVER" badge in the top bar while either fog-of-war or hidden-
     *  victory-hex disclosure is on — both are "affects game balance" toggles the player should
     *  never forget are active. */
    private fun updateObserverBadge() {
        val on = uiSettings.noFOW || uiSettings.showHiddenVictoryHexes
        byId("osadaObserverBadge")?.style?.display = if (on) "flex" else "none"
    }

    private fun updateReservesBadge(player: Player) {
        val badge = byId("osadaReservesBadge") ?: return
        val count = player.getCoreUnitList().count { !it.isDeployed }
        if (count > 0) {
            badge.textContent = count.toString()
            badge.style.display = "inline-flex"
        } else {
            badge.style.display = "none"
        }
    }

    /** Single global Escape handler: closes the topmost modal, or — if nothing is open — toggles
     *  the pause/options menu. Registered ONCE from UI.init(); anything that wants Escape to close
     *  it belongs as a branch here, not a second document-level listener (two independent listeners
     *  would both fire on the same keypress, e.g. closing a window AND opening the pause menu). */
    fun handleGlobalEscape() {
        when {
            isVisible("ui-message") -> byId("uiokbut")?.click() // topmost layer of all (--z-msg)
            isVisible("equipment") -> byId("eqCloseBut")?.click()
            isVisible("combatLog") -> UICombatLog.toggleCombatLog(false, true)
            else -> ui.mainMenuButton("options")
        }
    }

    // ---- Ready-unit navigator + End-Turn state machine (Task 1) ----

    /** Own units that have done NOTHING at all yet this turn — the End Turn badge/confirm-nag
     *  definition. Deliberately narrower than [actionableUnits] below: a unit that already moved
     *  but can still fire is not "fully ready" for this count, even though it can still act. */
    private fun fullyReadyUnits(): List<GameUnit> {
        val map = ui.game.scenario?.map ?: return emptyList()
        val player = map.currentPlayer ?: return emptyList()
        if (player.type != PlayerType.HUMAN_LOCAL) return emptyList()
        return map.getUnits().filter {
            it.player?.id == player.id && !it.hasMoved && !it.hasFired && !it.destroyed && hasAnyAction(it)
        }
    }

    /** Whether [unit] has any action actually available. Units that can still move always count;
     *  a unit that CANNOT move (fortifications like Zborow's Ukreplenie have 0 move points by
     *  design) only counts while it can still shoot at something reachable — otherwise the End
     *  Turn badge nags "N units can still act" every turn about a foxhole with nothing to do,
     *  and the navigator keeps cycling to it. Reuses [GameRules.getUnitAttackCells] (the same
     *  check the attack ring uses) rather than re-deriving range/target/spotting rules; the
     *  target scan only runs for immobile units, so the cost stays negligible. */
    internal fun hasAnyAction(unit: GameUnit): Boolean {
        if (unit.getMovesLeft() > 0) return true
        val map = ui.game.scenario?.map ?: return false
        return GameRules.getUnitAttackCells(map.map, unit, map.rows, map.cols).isNotEmpty()
    }

    /** Own units that can STILL act this turn: haven't moved yet, OR have moved but can still fire
     *  (mirrors AttackRingBuilder's own !hasFired && ammo>0 check). Drives the navigator arrows/
     *  count and cycling — broader than [fullyReadyUnits] so a moved-but-not-fired unit isn't
     *  skipped as "done" when cycling through what's left to do this turn. */
    private fun actionableUnits(): List<GameUnit> {
        val map = ui.game.scenario?.map ?: return emptyList()
        val player = map.currentPlayer ?: return emptyList()
        if (player.type != PlayerType.HUMAN_LOCAL) return emptyList()
        return map.getUnits().filter {
            it.player?.id == player.id &&
                !it.destroyed &&
                (!it.hasMoved || (!it.hasFired && it.getAmmo() > 0)) &&
                hasAnyAction(it) &&
                !TurnSleep.isAsleep(map, it)
        }
    }

    /** Whether [unit] is currently asleep (excluded from the navigator/its count, but still
     *  counted by the End Turn nag — see [TurnSleep]). */
    fun isUnitAsleep(unit: GameUnit): Boolean {
        val map = ui.game.scenario?.map ?: return false
        return TurnSleep.isAsleep(map, unit)
    }

    /** Toggles [unit]'s asleep state and refreshes the top-bar turn controls immediately so the
     *  navigator count/cycle reflect it without waiting for the next move/attack. */
    fun toggleUnitSleep(unit: GameUnit) {
        val map = ui.game.scenario?.map ?: return
        TurnSleep.toggle(map, unit)
        updateTurnControls()
    }

    /** Refreshes the two turn-scoped top-bar widgets (navigator + End Turn). Cheap; called from
     *  [updateStatusBar] and after every move/attack so the count stays live. */
    fun updateTurnControls() {
        val navCount = actionableUnits().size
        byId("osadaNavCount")?.textContent = navCount.toString()
        byId("osadaNav")?.let {
            if (navCount ==
                0
            ) {
                it.classList.add("osada-tb-nav--empty")
            } else {
                it.classList.remove("osada-tb-nav--empty")
            }
        }
        updateEndTurnButton(fullyReadyUnits().size)
    }

    private fun updateEndTurnButton(n: Int) {
        val btn = byId("osadaEndTurn") ?: return
        if (btn.getAttribute("confirming") == "on") return // don't clobber an active inline confirm
        btn.className = "osada-et " + if (n > 0) "osada-et--warn" else "osada-et--ready"
        clearTag(btn)
        val label = addTag(btn, "span")
        label.className = "osada-et__label"
        label.textContent = if (n > 0) "End turn · $n" else "End turn"
        btn.title = if (n > 0) "$n unit(s) haven't acted yet — click to end the turn" else "End turn"
        btn.onclick = { e: MouseEvent ->
            e.stopPropagation()
            onEndTurnClick()
        }
    }

    /** Cycles map selection through the ready units. Reuses [UI.uiUnitSelect] (the existing
     *  selection path) rather than a new one; wraps around the filtered ready list. */
    fun cycleReadyUnit(direction: Int) {
        val list = actionableUnits()
        if (list.isEmpty()) return
        val current = ui.game.scenario?.map?.currentUnit
        val idx = if (current != null) list.indexOfFirst { it.id == current.id } else -1
        val nextIdx = if (idx == -1) {
            (if (direction > 0) 0 else list.size - 1)
        } else {
            (((idx + direction) % list.size) + list.size) % list.size
        }
        val unit = list[nextIdx]
        ui.uiUnitSelect(unit)
        unit.getPos()?.let { ui.uiSetCellOnViewPort(it) }
    }

    private var endTurnConfirmTimer: Int = 0

    fun onEndTurnClick() {
        val map = ui.game.scenario?.map ?: return
        if (map.currentPlayer?.type != PlayerType.HUMAN_LOCAL) return
        if (ui.game.waitUIAnimation || ui.game.gameEnded) return
        val n = fullyReadyUnits().size
        if (n == 0 || !uiSettings.confirmEndTurn) {
            performEndTurn()
        } else {
            showEndTurnConfirm(n)
        }
    }

    /** Inline (no-modal) confirm: the button morphs into "N can still act. End turn? ✓ ✗" for ~3s. */
    private fun showEndTurnConfirm(n: Int) {
        val btn = byId("osadaEndTurn") ?: return
        btn.setAttribute("confirming", "on")
        btn.className = "osada-et osada-et--confirm"
        btn.title = ""
        clearTag(btn)
        val msg = addTag(btn, "span")
        msg.className = "osada-et__msg"
        msg.textContent =
            "$n unit(s) haven't acted. End turn?"
        val yes = addTag(btn, "span")
        yes.className = "osada-et__yes"
        yes.innerHTML = "✓"
        yes.title =
            "Confirm — end the turn"
        val no = addTag(btn, "span")
        no.className = "osada-et__no"
        no.innerHTML = "✗"
        no.title = "Cancel"
        yes.onclick = { e: MouseEvent ->
            e.stopPropagation()
            cancelEndTurnConfirm()
            performEndTurn()
        }
        no.onclick = { e: MouseEvent ->
            e.stopPropagation()
            cancelEndTurnConfirm()
            updateTurnControls()
        }
        btn.onclick = { e: MouseEvent -> e.stopPropagation() }
        endTurnConfirmTimer = window.setTimeout({
            cancelEndTurnConfirm()
            updateTurnControls()
        }, 3000)
    }

    private fun cancelEndTurnConfirm() {
        if (endTurnConfirmTimer != 0) {
            window.clearTimeout(endTurnConfirmTimer)
            endTurnConfirmTimer = 0
        }
        byId("osadaEndTurn")?.removeAttribute("confirming")
    }

    /** The actual turn-end: closes transient windows and hands off to the game engine, then
     *  refreshes for the next player. Extracted from the old double-tap handler. */
    private fun performEndTurn() {
        val map = ui.game.scenario?.map ?: return
        cancelEndTurnConfirm()
        if (isVisible("equipment")) {
            makeHidden("equipment")
            makeHidden("container-unitlist")
            uiSettings.deployMode = false
            byId("buy")?.let { toggleButton(it, false) }
            ui.hideUnitInfoIfNotPinned()
        }
        if (isVisible("unit-info")) makeHidden("unit-info")
        UICombatLog.forceClose()
        makeHidden("uiToolTip")
        if (map.currentPlayer?.type == PlayerType.HUMAN_LOCAL) {
            ui.game.endTurn()
            if (ui.game.gameEnded && ui.game.gameStarted) {
                UIBuilder.message("DEFEAT", "<br><br>You didn't capture the objectives in time")
            } else {
                ui.countriesOnSpotSide = map.getCountriesBySide(ui.game.spotSide)
                UIBuilder.setDefaultUserSelections()
                CombatLog.reset()
                ui.updateEquipmentWindow(UnitClass.TANK.value)
                uiUnitSelectNext()
                updateStatusBar()
                // map.currentPlayer is already the NEXT player at this point (GameMap.endTurn
                // advances it); log whose turn is starting.
                map.currentPlayer?.let { next ->
                    HudLog.add("Turn ${map.turn}/${map.maxTurns} — ${next.getCountryName()} begins")
                }
            }
        }
    }

    /** Fills the sidebar OBJECTIVES panel: a flat list of victory hexes (no grouping), name
     *  (the hex's own name — victory hexes are conventionally named after the city/town they
     *  represent — or its coordinates when unnamed) with a right-aligned held/enemy status.
     *  Uses the same visibility rule as the map tooltips (flag/owner set), so hidden victory
     *  hexes are not leaked — fog-of-war discipline. Cheap (one map sweep), called from
     *  updateStatusBar (covers both turn-change and post-capture refreshes, since capturing a
     *  hex as the human player already triggers updateStatusBar via the move-finish hook). */
    private fun updateObjectivesPanel() {
        val container = byId("osadaObjectives") ?: return
        val map = ui.game.scenario?.map ?: return
        val side = map.currentPlayer?.side ?: return
        clearTag(container)
        var total = 0
        var held = 0
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                val hex = map.map?.get(r)?.get(c) ?: continue
                if (hex.victorySide == -1 || hex.flag == -1 || hex.owner == -1) continue
                // hex.owner is a PLAYER ID, not a side — comparing it to `side` directly is wrong
                // whenever a side has more than one player (support countries), which is exactly
                // why some held hexes were misreported as captured. Resolve owner -> side first.
                val isHeld = map.getPlayer(hex.owner).side == side
                val row = addTag(container, "div")
                row.className = "osada-obj" + if (isHeld) " osada-obj--held" else ""
                val name = addTag(row, "span")
                name.className = "osada-obj__name"
                name.textContent = if (hex.name.isNotEmpty()) hex.name else "($c,$r)"
                name.title = name.textContent ?: ""
                val state = addTag(row, "span")
                state.className = "osada-obj__state"
                val mark = addTag(state, "span")
                mark.className = "osada-obj__mark"
                mark.textContent = if (isHeld) "✓" else "⚑" // check / flag
                val label = addTag(state, "span")
                label.textContent = if (isHeld) "Held" else "Enemy"
                row.onclick = { _: org.w3c.dom.events.MouseEvent -> ui.uiSetCellOnViewPort(Cell(r, c)) }
                total++
                if (isHeld) held++
            }
        }
        if (total == 0) {
            val empty = addTag(container, "div")
            empty.className = "osada-side-empty"
            empty.textContent = "No visible objectives"
        }
        byId("osadaRailObjCounter")?.textContent = "$held/$total"
    }

    fun checkUndeployedUnits(): Boolean {
        val map = ui.game.scenario?.map ?: return false
        val player = map.currentPlayer ?: return false
        if (!player.hasUndeployedUnits() || player.type != PlayerType.HUMAN_LOCAL) return false
        // Deploy phase: open the equipment window directly on the Reserve tab (the old
        // standalone deploy strip no longer exists as a HUD element).
        byId("equipment")?.style?.display = "grid"
        EquipmentWindowBuilder.setEquipmentMode("reserve")
        makeVisible("container-unitlist")
        AttackRingBuilder.clear() // rings clear while any modal window is open (spec)
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                val hex = map.map?.get(r)?.get(c) ?: continue
                if (hex.isDeployment == player.id) {
                    ui.uiSetCellOnViewPort(Cell(r, c))
                    return true
                }
            }
        }
        byId("buy")?.let { toggleButton(it, true) }
        return true
    }

    fun showStatusExtension() {
        makeHidden("statusbar-extension")
        val scenario = ui.game.scenario ?: return
        val map = scenario.map
        val player = map.currentPlayer ?: return
        updateStatusBar()
        if (player.type == PlayerType.HUMAN_LOCAL || player.type == PlayerType.AI_SCRIPTED) {
            UICombatLog.toggleCombatLog(true, false)
            ui.removeAllSmallToolTips(true)
            // NOT addSmallToolTips(true): all=true skips the hex-name/objective block entirely
            // (see its own `if (!all && ...)` gate) and only rebuilds unit ammo/fuel warnings.
            // This ran on every turn start, so hex-name and "show optional objectives tooltips"
            // labels were wiped by the removeAllSmallToolTips(true) just above and never rebuilt —
            // they only reappeared after an unrelated full rebuild (Grid toggle, map zoom).
            ui.addSmallToolTips()
        } else {
            UIBuilder.showAIStatus(true)
        }
    }

    fun toggleStrategicZoom() {
        val gameDiv = byId("game") ?: return
        if (uiSettings.strategicZoom) {
            gameDiv.style.width = "${windowInnerWidth()}px"
            gameDiv.style.height = "${windowInnerHeight()}px"
            // .style.zoom, NOT a bare .zoom on the element: `zoom` isn't a standard reflected
            // IDL attribute, so `gameDiv.asDynamic().zoom = ...` (the pre-existing code here)
            // only ever set a meaningless custom expando property — no CSS zoom was EVER applied,
            // so Strategic Map has been visually non-functional (confirmed via getComputedStyle:
            // stayed at 1/100% regardless). `.style` is a real CSSStyleDeclaration; `zoom` isn't
            // in Kotlin's typed binding for it (non-standard property), so it needs .asDynamic()
            // on THAT (a legitimate typed→dynamic cast, unlike casting the element itself).
            gameDiv.style.asDynamic().zoom = "100%"
            gameDiv.style.transform = ""
            uiSettings.strategicZoom = false
            uiSettings.strategicZoomLevel = 1.0
            // Drop the backdrop and hand #game's geometry (width/height/left/top) back to its
            // owner — positionLayers is the single place that computes the normal-view layout,
            // so reverting means re-running it, not re-deriving those numbers here.
            byId("mainbody")?.classList?.remove("osada-strategic")
            ui.render.positionLayers()
        } else {
            val mapCanvas: dynamic = ui.render.getMapCanvas()
            val mapWidth = mapCanvas?.width as? Int ?: windowInnerWidth()
            val mapHeight = mapCanvas?.height as? Int ?: windowInnerHeight()
            val scaleX = 100.0 * windowInnerWidth() / (mapWidth * uiSettings.zoomLevel)
            val scaleY = 100.0 * windowInnerHeight() / (mapHeight * uiSettings.zoomLevel)
            val percent = minOf(scaleX, scaleY)
            gameDiv.style.asDynamic().zoom = "${percent.toInt()}%"
            // Size #game to the MAP, not to the viewport (which is what left the shrunken map in
            // the corner of a full-viewport box, with the rest of it reading as a gray void). These
            // are pre-zoom lengths: CSS `zoom` multiplies them, so the rendered box comes out at
            // map * zoomLevel * percent — which fits the viewport by construction of `percent`.
            gameDiv.style.width = "${(mapWidth * uiSettings.zoomLevel).toInt()}px"
            gameDiv.style.height = "${(mapHeight * uiSettings.zoomLevel).toInt()}px"
            uiSettings.strategicZoom = true
            uiSettings.strategicZoomLevel = 100.0 / percent
            byId("mainbody")?.classList?.add("osada-strategic")
            centerStrategicMap(gameDiv)
        }
        byId("zoom")?.let { toggleButton(it, uiSettings.strategicZoom) }
    }

    /**
     * Centers #game (the whole map box) in the viewport for strategic view.
     *
     * Centering the box itself — rather than the canvases inside it — is deliberate: the attack-ring
     * overlay and the hex-name tooltips are absolutely positioned children of #game in MAP
     * coordinates, so anything that shifts the canvases alone would desync them. Moving #game moves
     * all of them together.
     *
     * The offset is MEASURED, not derived: #game carries a CSS `zoom`, and how zoom scales an
     * element's own `left`/`top` (as opposed to its width/height) is exactly the kind of detail that
     * differs between engines. So probe it — read the rendered rect at left/top = 0, again at a known
     * offset, and solve for the px-per-css-px factor. Two forced layouts, once per toggle.
     */
    private fun centerStrategicMap(gameDiv: org.w3c.dom.HTMLElement) {
        val style = gameDiv.style
        style.left = "0px"
        style.top = "0px"
        val base = gameDiv.getBoundingClientRect()
        val probe = 100.0
        style.left = "${probe}px"
        style.top = "${probe}px"
        val moved = gameDiv.getBoundingClientRect()
        val scaleX = (moved.left - base.left) / probe
        val scaleY = (moved.top - base.top) / probe
        // A zero factor would mean `left`/`top` don't move the box at all (some future engine
        // quirk) — leave it where positionLayers put it rather than dividing by zero.
        if (scaleX == 0.0 || scaleY == 0.0) {
            style.left = "0px"
            style.top = "${TOPBAR_H}px"
            return
        }
        val targetLeft = maxOf(0.0, (windowInnerWidth() - base.width) / 2.0)
        val targetTop = TOPBAR_H + maxOf(0.0, (windowInnerHeight() - TOPBAR_H - base.height) / 2.0)
        style.left = "${(targetLeft - base.left) / scaleX}px"
        style.top = "${(targetTop - base.top) / scaleY}px"
    }

    private fun uiUnitSelectNext() {
        val map = ui.game.scenario?.map ?: return
        val units = map.getUnits()
        val player = map.currentPlayer ?: return
        for (u in units) {
            if (u.player?.id == player.id) {
                val uclass = u.unitData(true).uclass as? Int ?: continue
                if (uclass != UnitClass.FIGHTER.value &&
                    uclass != UnitClass.LEVEL_BOMBER.value &&
                    uclass != UnitClass.TACTICAL_BOMBER.value
                ) {
                    ui.uiUnitSelect(u)
                    u.getPos()?.let { ui.uiSetCellOnViewPort(it) }
                    break
                }
            }
        }
    }

    private fun triggerChange(select: HTMLSelectElement) {
        select.dispatchEvent(org.w3c.dom.events.Event("change"))
    }

    private fun windowInnerWidth(): Int = js("window.innerWidth") as Int
    private fun windowInnerHeight(): Int = js("window.innerHeight") as Int
}
