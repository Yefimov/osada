package org.osada.ui

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.osada.*
import org.osada.model.Equipment
import org.osada.scenario.Campaign
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.events.Event
import kotlin.math.roundToInt

/**
 * Builds the start menu: main buttons, the campaign and scenario selection panels, the
 * in-menu settings (sliders + checkboxes) and the player/AI side setup. Extracted from
 * the former `UIBuilder` god-object. Shared data (image paths, currency icon) lives on
 * the [UIBuilder] facade; layout helpers come from [UILayout] and the save/load sub-screen
 * from [GameStateMenuBuilder].
 */
internal object StartMenuBuilder {

    private const val DIFFICULTY_HISTORICAL = 0
    private const val DIFFICULTY_TACTICAL = 1
    private const val DIFFICULTY_OPERATIONAL = 2

    /** Derived from the SAME [difficultyModifiers] the campaign actually applies (Campaign.kt /
     *  ScenarioLoader.kt / Player.kt) — never hand-copied numbers that could drift from the rules. */
    private fun difficultyHint(difficulty: Int): String {
        val mod = difficultyModifiers[difficulty] ?: return ""
        if (mod.startPrestige == 0.0 && mod.turnPrestige == 0.0 && mod.extraTurns == 1.0 && mod.scoreCoef == 1.0) {
            return "Full difficulty — no starting bonuses, standard turn limit and score."
        }
        // roundToInt(), not toInt(): floating-point subtraction (e.g. 1.2 - 1.0) lands on
        // 0.19999999999999996, and plain truncation turned +20% into a wrong "+19%".
        val startPct = (mod.startPrestige * 100).roundToInt()
        val turnPct = (mod.turnPrestige * 100).roundToInt()
        val turnsPct = ((mod.extraTurns - 1.0) * 100).roundToInt()
        val scorePct = ((1.0 - mod.scoreCoef) * 100).roundToInt()
        return "+$startPct% starting prestige, +$turnPct% prestige per turn, +$turnsPct% extra turns, -$scorePct% score"
    }

    private var startMenuBuilt = false

    fun resetStartMenuBuilt() {
        startMenuBuilt = false
    }

    private fun campaignList(): Array<dynamic> =
        js("typeof campaignlist !== 'undefined' ? campaignlist : []").unsafeCast<Array<dynamic>>()

    private fun scenarioList(): Array<dynamic> =
        js("typeof scenariolist !== 'undefined' ? scenariolist : []").unsafeCast<Array<dynamic>>()

    private fun continueCampaign(outcome: String) {
        makeHidden("smCamp")
        makeHidden("startmenu")
        gameRef()?.continueCampaign(outcome)
    }

    private fun startNewCampaign(campaignId: Int, difficulty: Int) {
        makeHidden("gameToolTip")
        makeHidden("smCamp")
        makeHidden("startmenu")
        val game = gameRef()
        game?.newCampaign(campaignId, difficulty)
        byId("options")?.let { toggleButton(it, false) }
        game?.state?.saveSettings()
    }

    private fun startNewScenario(file: String, description: String) {
        makeHidden("gameToolTip")
        makeHidden("smScen")
        makeHidden("startmenu")
        markScenarioPlayed(file)   // cosmetic New/Played chip only; touches no game save
        val game = gameRef()
        if (game != null) {
            game.campaign = null
            game.newScenario(file, description)
        }
        byId("options")?.let { toggleButton(it, false) }
        game?.state?.saveSettings()
    }

    fun hideStartMenu() {
        makeHidden("smMain")
        makeHidden("smScen")
        makeHidden("smSettings")
        makeHidden("smState")
        makeHidden("startmenu")
        byId("options")?.let { toggleButton(it, false) }
    }

    fun buildStartMenu() {
        if (startMenuBuilt) return
        startMenuBuilt = true
        // OSADA main column: fixed order, condensed uppercase labels, routed to the existing
        // startMenuButton actions. The third field is a visual variant, not a new action.
        // "New Game" is intentionally dropped as a button (it only opened a sub-panel that
        // duplicated Campaigns/Scenarios); its handler in MenuController is left untouched.
        val mainButtons = listOf(
            Triple("continuegame", "Continue", "primary"),
            Triple("newcampaign", "New Campaign", ""),
            Triple("newscenario", "Single Scenario", ""),
            Triple("saveload", "Load Game", ""),
            Triple("settings", "Settings", ""),
            Triple("tutorial", "Tutorial", "muted")
        )
        val difficultyOptions = listOf(
            Triple(DIFFICULTY_HISTORICAL, "Historical", false),
            Triple(DIFFICULTY_TACTICAL, "Tactical", true),
            Triple(DIFFICULTY_OPERATIONAL, "Operational", false)
        )
        // Task 5: regrouped from one flat list into named sections. Same keys/labels (plus the
        // new confirmEndTurn toggle) — CSS + markup only, no checkbox logic changed.
        data class SettingSection(val title: String, val caption: String?, val items: List<Pair<String, String>>)
        val settingSections = listOf(
            SettingSection("Map View", null, listOf(
                "showGridTerrain" to "Show terrain with Hex Grid",
                "markOwnUnits" to "Mark own units on map",
                "markEnemyUnits" to "Mark enemy strength in red",
                "useRetina" to "Zoom to full device resolution"
            )),
            SettingSection("Gameplay", null, listOf(
                "quickAnimation" to "Quick combat and move animations",
                "showDetailInfoToolTips" to "Show optional objectives tooltips",
                "confirmEndTurn" to "Confirm end of turn"
            )),
            SettingSection("Sound", null, listOf(
                "muteUnitSounds" to "Mute unit combat sounds"
            )),
            SettingSection("Observer Mode", "Affects game balance", listOf(
                "noFOW" to "Disable Fog of War",
                "showHiddenVictoryHexes" to "Show hidden victory hexes"
            ))
        )
        // Settings that change what the CANVAS draws — re-rendered on click, not deferred to
        // "Done" (see the click handler below). useRetina is deliberately excluded: it needs a
        // page reload (see its own branch), not a render call.
        val liveRenderSettingIds = setOf(
            "showGridTerrain", "markOwnUnits", "markEnemyUnits",
            "noFOW", "showHiddenVictoryHexes", "showDetailInfoToolTips"
        )

        val menuIcons = mapOf(
            "continuegame" to "star", "newcampaign" to "map", "newscenario" to "attack",
            "saveload" to "supply", "settings" to "settings", "tutorial" to "info"
        )
        val menuSubs = mapOf(
            "continuegame" to "Resume your campaign", "newcampaign" to "Lead a nation through the war",
            "newscenario" to "Fight a standalone battle", "saveload" to "Restore a saved battle",
            "settings" to "Options & display", "tutorial" to "Learn the basics"
        )
        mainButtons.forEach { (id, title, variant) ->
            val button = addTag("smButtons", "div")
            button.id = id
            button.title = title
            button.className = "smMainButton osada-menu-btn" + when (variant) {
                "primary" -> " osada-menu-btn--primary"
                "muted" -> " osada-menu-btn--muted"
                else -> ""
            }
            val ico = addTag(button, "span")
            ico.className = "osada-menu-btn__ico osada-ico osada-ico--${menuIcons[id] ?: "star"}"
            val text = addTag(button, "span")
            text.className = "osada-menu-btn__text"
            val label = addTag(text, "span")
            label.className = "osada-menu-btn__label"
            label.textContent = title
            val sub = addTag(text, "span")
            sub.className = "osada-menu-btn__sub"
            sub.textContent = menuSubs[id] ?: ""
            button.onclick = { _: org.w3c.dom.events.MouseEvent ->
                gameRef()?.ui?.startMenuButton(id)
            }
        }
        applyContinueButtonState()
        // The old #smNewGame sub-panel (Campaigns / Scenarios / Tutorial) is no longer rendered:
        // its buttons shared ids with the new main column and are reached directly from it now.
        // MenuController's "newgame" handler is left intact (it just reveals the empty panel).

        byId("smLogoText")?.innerHTML = "Turn-based strategy of great battles"
        // Display version only — decoupled from the engine VERSION constant, which is baked
        // into the localStorage save keys and must not change (it would orphan existing saves).
        byId("smCredits")?.innerHTML = "v0.5"

        // Rotating quote, bottom-center of the main menu; re-rolled every time the menu is
        // shown (applyContinueButtonState runs on each show).
        val quote = addTag("smMain", "div")
        quote.id = "smQuote"
        quote.className = "osada-quote"
        showRandomQuote()

        val campSelect = addTag("smCampSel", "select")
        campaignList().forEachIndexed { index, campaign ->
            // Hidden campaigns get no <option> at all (not just a hidden row): this also keeps
            // them out of the default selection and the search index. Options carry the ORIGINAL
            // campaignlist index in `value`, which is what every consumer reads — position-based
            // lookups against campaignList() are wrong once the list is filtered here.
            if ((campaign.file as? String) in hiddenCampaignFiles) return@forEachIndexed
            val option = addTag(campSelect, "option")
            option.asDynamic().value = index.toString()
            option.textContent = campaign.title as? String ?: ""
        }

        // The "?" help button used to REPLACE the campaign description with a wall of static
        // text (a hidden mode the player had to discover, and it clobbered the actual campaign
        // blurb). Folded into a hint line that lives right under the control instead — updates
        // on hover (preview) and stays on the SELECTED difficulty otherwise, so touch users
        // (no hover) still see it. The button itself is now redundant; hide rather than delete
        // (it's static HTML in index.html, not builder-created).
        byId("smCampDifHelp")?.style?.display = "none"
        val difHint = addTag(byId("smCampDif")?.parentElement, "div")
        difHint.id = "smCampDifHint"
        difHint.className = "osada-dif-hint"

        // Custom segmented difficulty control (replaces the native <select>). The chosen
        // value is stashed on #smCamp and read by the Start handler.
        difficultyOptions.forEach { (value, text, selected) ->
            val seg = addTag("smCampDif", "div")
            seg.className = "osada-seg" + if (selected) " osada-seg--on" else ""
            seg.textContent = text
            seg.title = text
            seg.asDynamic().diffValue = value
            if (selected) {
                byId("smCamp")?.asDynamic()?.selectedDifficulty = value
                difHint.textContent = difficultyHint(value)
            }
            seg.onmouseenter = { _: org.w3c.dom.events.Event -> difHint.textContent = difficultyHint(value) }
            seg.onmouseleave = { _: org.w3c.dom.events.Event ->
                val current = byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int ?: DIFFICULTY_HISTORICAL
                difHint.textContent = difficultyHint(current)
            }
            seg.onclick = { _: org.w3c.dom.events.MouseEvent ->
                byId("smCamp")?.asDynamic()?.selectedDifficulty = value
                byId("smCampDif")?.children?.let { segs ->
                    for (i in 0 until segs.length) {
                        (segs.asDynamic()[i] as? HTMLElement)?.classList?.remove("osada-seg--on")
                    }
                }
                seg.classList.add("osada-seg--on")
                difHint.textContent = difficultyHint(value)
                // 0b: shown prestige must track difficulty, matching what Start will grant.
                updateCampaignPrestigeDisplay()
            }
        }

        fun onCampSelectChange() {
            val selectedIndex = campSelect.asDynamic().selectedIndex as? Int ?: -1
            if (selectedIndex < 0) return
            val value = campSelect.asDynamic().options[selectedIndex].value as? String ?: return
            val campaign = campaignList().getOrNull(value.toInt()) ?: return
            val country = Equipment.getCountryNameByEqp(campaign.flag as? Int ?: 0, campaign.eqp as? String ?: "")
            byId("smCampDesc")?.innerHTML = campaign.desc as? String ?: ""
            byId("smCampCountry")?.innerHTML = "<b>Country</b><br/>" + country
            byId("smCampScenarios")?.innerHTML = "<b>Operations</b><br/>" +
                    (campaign.scenarios as? Int ?: (campaign.scenarios as? String ?: ""))
            byId("smCamp")?.asDynamic()?.selectedCampaign = value.toInt()
            updateCampaignPrestigeDisplay()
            // OSADA dossier head + register highlight (single source of truth = the hidden select).
            // Campaign title shows just the name (no date); date is in smCampDossierSub below
            val campaignTitleClean = (campaign.title as? String ?: "").replace(Regex("\\s*\\([^)]*\\d{1,4}[^)]*\\)\\s*"), "").trim()
            byId("smCampTitle")?.innerHTML = campaignTitleClean
            byId("smCampDossierSub")?.innerHTML = listOfNotNull(
                country.ifBlank { null },
                extractYears(campaign.title as? String ?: "").ifBlank { null }
            ).joinToString(" &middot; ")
            setTheaterArt(campaign)
            byId("osadaCampList")?.let { syncListHighlight(campSelect, it) }
            byId("smCampPath")?.let { collapsePath(it) }
        }
        campSelect.asDynamic().onchange = { onCampSelectChange() }
        buildCampaignScreen(campSelect)

        byId("smCBackBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smCamp")
            makeVisible("smMain")
        }

        byId("smCPlayBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            val difficulty = byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int ?: DIFFICULTY_HISTORICAL
            selectedCampaign?.let { startNewCampaign(it, difficulty) }
        }

        byId("smCFlowBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            if (selectedCampaign != null) {
                var campaignRef: Campaign? = null
                val campaign = Campaign(selectedCampaign, DIFFICULTY_HISTORICAL) {
                    campaignRef?.let { byId("smCampDesc")?.innerHTML = it.getCampaignFlow() }
                }
                campaignRef = campaign
            }
        }

        if (DEBUG_CAMPAIGN) {
            byId("smCV")?.onclick = { continueCampaign("victory") }
            byId("smCVB")?.onclick = { continueCampaign("briliant") }
            byId("smCVT")?.onclick = { continueCampaign("tactical") }
            byId("smCL")?.onclick = { continueCampaign("lose") }
        }

        val scenSelect = addTag("smScenSel", "select")
        scenarioList().forEachIndexed { index, scenario ->
            val option = addTag(scenSelect, "option")
            val length = scenario.length as? Int ?: 0
            if (length == 1) {
                option.asDynamic().disabled = true
                option.textContent = "» " + (scenario[0] as? String ?: "")
            } else {
                option.asDynamic().value = index.toString()
                option.textContent = "    " + (scenario[1] as? String ?: "")
            }
        }

        fun onScenSelectChange() {
            val selectedIndex = scenSelect.asDynamic().selectedIndex as? Int ?: -1
            if (selectedIndex < 0) return
            val value = scenSelect.asDynamic().options[selectedIndex].value as? String ?: return
            val scenario = scenarioList().getOrNull(value.toIntOrNull() ?: return) ?: return
            byId("smScenDesc")?.innerHTML = scenario[2] as? String ?: ""
            UIBuilder.setEquipmentFlags(scenario[5] as? String)
            // Reset to the scenario's own default: whichever side player id 0 belongs to is
            // human, everyone else is AI — same default the old per-player toggles used, now
            // driven through the side picker (selectScenarioSide) instead of a second state.
            selectScenarioSide(scenario, defaultHumanSide(scenario))
            byId("smScen")?.asDynamic()?.selectedScenario = value.toInt()
            byId("smScenTitle")?.innerHTML = scenario[1] as? String ?: ""
            // Extract date from scenario description and show in subtitle
            val scenDesc = scenario[2] as? String ?: ""
            val scenDate = extractYears(scenDesc).ifBlank { extractYears(scenario[1] as? String ?: "") }
            byId("smScenDossierSub")?.innerHTML = scenDate
            byId("osadaScenList")?.let { syncListHighlight(scenSelect, it) }
        }
        scenSelect.asDynamic().onchange = { onScenSelectChange() }
        buildScenarioScreen(scenSelect)

        byId("smSBackBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smScen")
            makeVisible("smMain")
            val game = gameRef()
            UIBuilder.setEquipmentFlags(game?.scenario?.eqp as? String)
        }

        byId("smSPlayBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            val selectedScenario = byId("smScen")?.asDynamic()?.selectedScenario as? Int
            val scenario = selectedScenario?.let { scenarioList().getOrNull(it) }
            if (scenario != null) {
                startNewScenario(scenario[0] as? String ?: "", scenario[2] as? String ?: "")
            }
        }

        UILayout.resizeUI(uiSettings.uiSize)
        UILayout.scaleUI(uiSettings.uiScale)
        UILayout.setLayoutConstrains(false)

        // Slider rows need the same row scaffold as the checkbox rows below (and as PM): a
        // `settingContainer left` wrapper with a `settingText left` label and a right-floated div
        // holding the slider. Without it the three sliders had no label and no alignment ("съехали").
        fun sliderSetting(id: String, label: String, value: Double, step: Double, min: Double, max: Double, onInput: () -> Unit): HTMLElement {
            val container = addTag("smSettingsContainer", "div")
            container.className = "settingContainer left"
            val textDiv = addTag(container, "div")
            textDiv.className = "settingText left"
            textDiv.textContent = label
            val sliderWrap = addTag(container, "div")
            sliderWrap.style.cssFloat = "right"
            UILayout.createSlider(sliderWrap, id, value, step, min, max, onInput)
            return container
        }

        // Interface width is obsolete: the HUD is viewport-based now. The row is hidden, not
        // removed — the Settings-OK handler (and UILayout) still read #uiresize, so the slider
        // element stays in the DOM with its stored value.
        sliderSetting("uiresize", "Interface width (px)", uiSettings.uiSize.toDouble(), 10.0, uiSettings.uiSmallSize.toDouble(), 1920.0) {
            UILayout.resizeUI((byId("uiresize")?.asDynamic()?.value as? String)?.toIntOrNull() ?: uiSettings.uiSize)
        }.style.display = "none"
        sliderSetting("uiscale", "Interface scale", uiSettings.uiScale, 0.1, 0.5, 3.0) {
            UILayout.scaleUI((byId("uiscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.uiScale)
        }
        sliderSetting("mapscale", "Game Map scale", uiSettings.zoomLevel, 0.1, MapZoom.MIN, MapZoom.MAX) {
            MapZoom.set((byId("mapscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.zoomLevel)
        }

        settingSections.forEach { section ->
            val header = addTag("smSettingsContainer", "div")
            header.className = "osada-settings-header"
            val title = addTag(header, "span")
            title.className = "osada-settings-header__title"
            title.textContent = section.title
            section.caption?.let { cap ->
                header.classList.add("osada-settings-header--observer")
                val caption = addTag(header, "span")
                caption.className = "osada-settings-header__caption"
                caption.textContent = cap
            }
            section.items.forEach { (id, label) ->
                val container = addTag("smSettingsContainer", "div")
                container.className = "settingContainer left"
                val textDiv = addTag(container, "div")
                // Tooltips that EXPLAIN, not repeat the label. showHiddenVictoryHexes especially:
                // most scenarios have no hidden objectives at all (all their victory hexes carry
                // visible flags), so the toggle legitimately changes nothing there — without this
                // explanation that reads as "the setting is broken" (user report).
                textDiv.title = when (id) {
                    "showHiddenVictoryHexes" ->
                        "Reveals SECRET objectives (victory hexes with no flag on the map). " +
                        "Regular objectives — bordered flags — are always visible; most scenarios " +
                        "have no secret ones, so this often changes nothing. Affects game balance."
                    "noFOW" -> "Removes the fog veil and reveals all enemy units. Affects game balance."
                    "showDetailInfoToolTips" -> "Shows name labels over non-objective owned hexes (towns, airfields) on the map."
                    else -> label
                }
                textDiv.className = "settingText left"
                textDiv.textContent = label
                val valueDiv = addTag(container, "div")
                valueDiv.id = id
                // Image-based checkbox (resources/ui/osada/ico_check_on/off.png, asset-sheet
                // extracts) replaces the openpanzer-menu icon-font C/c glyph pair. Driven by a
                // "checked" class rather than the shared toggleCheckbox() case-flip helper (still
                // used unchanged by the per-player AI toggle elsewhere in this file), since there's
                // no text content left here to flip the case of.
                valueDiv.className = "settingValue right osada-checkbox"
                val enabled = uiSettings.getFlag(id)
                valueDiv.classList.toggle("checked", enabled)
                valueDiv.onclick = { _: org.w3c.dom.events.MouseEvent ->
                    val current = uiSettings.getFlag(id)
                    uiSettings.setFlag(id, !current)
                    valueDiv.classList.toggle("checked", !current)
                    if (id == "useRetina") {
                        byId("smSettings")?.asDynamic()?.needPageReload = true
                        if (window.devicePixelRatio >= 1.0) {
                            if (uiSettings.useRetina && uiSettings.uiScale <= 1.0) {
                                byId("uiscale")?.asDynamic()?.value = 1.6
                            }
                            if (!uiSettings.useRetina && uiSettings.uiScale >= 1.5) {
                                byId("uiscale")?.asDynamic()?.value = 1.0
                            }
                        }
                    }
                    // Observer badge (Task 5): the settings dialog covers the top bar anyway, so
                    // updating it live vs. on close is invisible to the player either way — but
                    // do it here too for the instant the dialog closes, not just on the next
                    // turn-change/selection-driven updateStatusBar refresh.
                    if (id == "noFOW" || id == "showHiddenVictoryHexes") {
                        gameRef()?.ui?.updateStatusBar()
                    }
                    // Live-apply map-visual toggles (Stage 3.5 follow-up): re-render the canvas
                    // immediately instead of only on Settings "Done" — the game map is visible
                    // in the background behind this dialog (it isn't full-screen), and clicking
                    // the checkbox with no visible effect until closing reads as broken.
                    if (id in liveRenderSettingIds) {
                        GameHolder.instance?.ui?.render?.render()
                        GameHolder.instance?.ui?.let { ui ->
                            ui.removeAllSmallToolTips()
                            ui.addSmallToolTips()
                        }
                    }
                }
            }
            // Volume sliders live inside the Sound section, right after its checkbox — a
            // continuation of the section's own items, not separate top-level controls.
            // Two levels (user request): discrete unit/fire cues vs the continuous weather loop.
            if (section.title == "Sound") {
                sliderSetting("soundvolume", "Effects volume", uiSettings.soundVolume, 0.05, 0.0, 1.0) {
                    uiSettings.soundVolume = (byId("soundvolume")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.soundVolume
                }
                sliderSetting("ambientvolume", "Ambient volume", uiSettings.ambientVolume, 0.05, 0.0, 1.0) {
                    uiSettings.ambientVolume = (byId("ambientvolume")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.ambientVolume
                    Sound.refreshAmbientVolume()   // hear it while adjusting, not on next weather change
                }
            }
        }

        byId("smSetOkBut")?.onclick = { _: org.w3c.dom.events.MouseEvent ->
            makeHidden("smSettings")
            // Settings is reached two ways: the PRE-GAME main menu's own "Settings" button (where
            // #startmenu, the fixed full-screen backdrop, was already showing #smMain underneath —
            // re-showing it here is correct and restores that view), or the IN-GAME "options" gear
            // icon (mainMenuButton("options")), which shows #startmenu+#smMain itself as a pause
            // overlay before Settings even opens. Unconditionally showing #startmenu here matched
            // the pre-game case but broke the in-game one: #smMain isn't necessarily showing at
            // this point (a second "options" press hides it), so the player could land on a bare
            // black #startmenu backdrop with no visible menu content and no way back into the game
            // without a page refresh. Mid-game, return to the game instead — matching what the
            // "options" toggle's own close branch already does.
            if (GameHolder.instance?.gameStarted == true) {
                makeHidden("smMain")
                makeHidden("startmenu")
                byId("options")?.let { toggleButton(it, false) }
            } else {
                makeVisible("startmenu")
            }
            UILayout.resizeUI((byId("uiresize")?.asDynamic()?.value as? String)?.toIntOrNull() ?: uiSettings.uiSize)
            UILayout.scaleUI((byId("uiscale")?.asDynamic()?.value as? String)?.toDoubleOrNull() ?: uiSettings.uiScale)
            gameRef()?.state?.saveSettings()
            // Redraw the map so toggles that affect rendering (show hidden victory hexes, mark own
            // units, hex grid, FoW) take effect immediately instead of only after a scenario restart.
            // Typed access (NOT gameRef()?.ui?.render?.render()): UI.render is an internal val and
            // Render.render() is overloaded, so a dynamic-typed call through gameRef() resolves to a
            // mangled name and silently no-ops — the FoW/hidden-victory-hexes/mark-own-units/hex-grid
            // toggles all appeared to do nothing on Settings OK because of this (only a REAL render,
            // e.g. the Grid button's own render() call, ever cleared the fog veil).
            GameHolder.instance?.ui?.render?.render()
            gameRef()?.ui?.updateStatusBar()
            // Hex-name/objective labels (e.g. "Show optional objectives tooltips") are a separate
            // DOM overlay, not part of the canvas render() above — rebuild it too or the toggle
            // has no visible effect until some unrelated trigger (Grid toggle, zoom) forces one.
            GameHolder.instance?.ui?.let { ui ->
                ui.removeAllSmallToolTips()
                ui.addSmallToolTips()
            }
            if (byId("smSettings")?.asDynamic()?.needPageReload == true) {
                window.location.reload()
            }
            if (byId("smSettings")?.asDynamic()?.messageHidden == true) {
                makeVisible("ui-message")
            }
        }

        GameStateMenuBuilder.buildGameStateMenu()
    }

    // ========================================================================
    // OSADA Stage 2 helpers: main-menu Continue gating, and custom register
    // lists synced to the hidden <select> that drive the two-column dossiers.
    // ========================================================================

    /** Show/hide the Continue button based on a saved game in localStorage, and annotate it
     *  with cheap save metadata (scenario name + turn) when that is readable. Re-invoked every
     *  time the main menu is shown (MenuController "options"), so a save created mid-session
     *  makes Continue appear without a page reload. */
    // Shown one at a time, bottom-center of the main menu (user request).
    private val menuQuotes = listOf(
        "Tactics are the questions of the day, strategy the questions of the epoch." to "J. Stalin",
        "Who will prevail over whom — that is the fundamental question of every revolution." to "V. I. Lenin",
        "Victory does not come by itself — it must be won." to "J. Stalin",
        "Tactics are a part of strategy, subordinate to it and serving it." to "J. Stalin",
        "Concentrate a great superiority of forces at the decisive point." to "V. I. Lenin",
        "Victory is impossible unless they have learned both how to attack and how to retreat properly." to "V. I. Lenin",
        "The philosophers have only interpreted the world, in various ways; the point, however, is to change it." to "K. Marx",
        "Workers of the world, unite!" to "K. Marx & F. Engels",
        "Revolutions are the locomotives of history." to "K. Marx",
        "There are no fortresses that Bolsheviks cannot storm." to "J. Stalin",
        "Force is the midwife of every old society pregnant with a new one." to "K. Marx",
        "The weapon of criticism cannot, of course, replace criticism of the weapon. Material force must be overthrown by material force." to "K. Marx",
        "Mass insurrection, revolutionary war, guerrilla detachments everywhere — this is the only method by which a small nation can overcome a large one." to "F. Engels",
        "The emancipation of the proletariat will have its own special expression in military affairs and will create its own, new military methods." to "F. Engels",
        "Nothing is more dependent on economic conditions than precisely the army and the navy. Armament, composition, organization, tactics and strategy depend above all on the stage reached at the time in production." to "F. Engels",
        "War is the continuation of politics by other means." to "V. I. Lenin",
        "Once you have taken up arms, do not lay them down until the enemy is completely crushed." to "V. I. Lenin",
        "A revolution is only worth something if it can defend itself." to "V. I. Lenin",
        "Peace is a breathing-space for war." to "V. I. Lenin",
        "Artillery is the god of war." to "J. Stalin",
        "The art of war in modern conditions consists in mastering all forms of warfare and in using them intelligently." to "J. Stalin",
        "In modern war, the morale of the people is one of the decisive factors." to "V. I. Lenin",
        "No mercy to the enemy!" to "J. Stalin"
    )

    private fun showRandomQuote() {
        val el = byId("smQuote") ?: return
        val (text, author) = menuQuotes.random()
        el.innerHTML = "<span class=\"osada-quote__text\">“$text”</span>" +
                "<span class=\"osada-quote__author\">— $author</span>"
    }

    internal fun applyContinueButtonState() {
        showRandomQuote()
        // Save/Load menu entry mirrors what the window can actually do from here: pre-game
        // only loading is possible; from the in-game pause menu it is the full pair.
        byId("saveload")?.let { btn ->
            val inGame = GameHolder.instance?.gameStarted == true
            (btn.query(".osada-menu-btn__label") as? HTMLElement)?.textContent =
                if (inGame) "Save / Load" else "Load Game"
            (btn.query(".osada-menu-btn__sub") as? HTMLElement)?.textContent =
                if (inGame) "Save or restore a battle" else "Restore a saved battle"
        }
        val button = byId("continuegame") ?: return
        val summary = savedGameSummary()
        if (summary == null) {
            button.style.display = "none"
            return
        }
        button.style.display = ""
        if (summary.isNotBlank()) {
            // Update only the subtitle so the icon + label built above stay intact.
            (button.query(".osada-menu-btn__sub") as? HTMLElement)?.textContent = summary
        }
    }

    /** null -> no saved game (hide Continue); "" -> save exists but metadata unreadable;
     *  otherwise a short "Name · Turn n/m" summary. Reads a single localStorage key. */
    private fun savedGameSummary(): String? {
        val majorVersion = VERSION.split(".").take(2).joinToString(".")
        val raw = localStorage.getItem("openpanzer-scenario-$majorVersion") ?: return null
        return try {
            val data = JSON.parse<dynamic>(raw)
            val name = data.name as? String
            val turn = data.turn as? Int
            val maxTurns = data.maxTurns as? Int
            when {
                name != null && turn != null && maxTurns != null -> "$name · Turn $turn/$maxTurns"
                name != null && turn != null -> "$name · Turn $turn"
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /** 0b: campaign-select prestige display. Uses the exact computation campaign start applies
     *  ([Campaign.computeStartPrestige]) so the shown number always matches what Start grants. */
    private fun updateCampaignPrestigeDisplay() {
        val selected = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int ?: return
        val campaign = campaignList().getOrNull(selected) ?: return
        val base = campaign.prestige as? Int ?: 0
        val difficulty = byId("smCamp")?.asDynamic()?.selectedDifficulty as? Int ?: DIFFICULTY_HISTORICAL
        byId("smCampPrestige")?.innerHTML = "<b>Start prestige</b><br/>" +
                Campaign.computeStartPrestige(base, difficulty) + "&nbsp;" + UIBuilder.currencyIcon
    }

    /** Extract a "1936-1945"-style year span from a campaign title's parentheses. */
    private fun extractYears(title: String): String {
        // Match (content with digits) - handles both 4-digit years and 2-digit BC years like (73-71 BC)
        val m = Regex("\\(([^)]*\\d{1,4}[^)]*)\\)").find(title) ?: return ""
        return m.groupValues[1].trim()
    }

    /**
     * Builds a visible row list mirrored to a hidden native <select> (the source of truth).
     * Clicking a row sets selectedIndex and dispatches a real `change` event so the existing
     * onchange handler runs untouched; [syncListHighlight] keeps the rows in sync when the
     * selection is set programmatically. Disabled options render as non-clickable group rows.
     */
    private fun buildSyncedList(
        select: HTMLElement,
        container: HTMLElement,
        renderRow: (option: HTMLOptionElement, index: Int, row: HTMLElement, selectable: Boolean) -> Unit
    ) {
        clearTag(container)
        val options = select.asDynamic().options
        val length = options.length as? Int ?: 0
        for (i in 0 until length) {
            val option = options[i] as? HTMLOptionElement ?: continue
            val selectable = option.disabled != true
            val row = addTag(container, "div")
            row.className = if (selectable) "osadaListRow" else "osadaListRow osadaListRow--group"
            row.asDynamic().optionIndex = i
            renderRow(option, i, row, selectable)
            if (selectable) {
                row.onclick = { _: org.w3c.dom.events.MouseEvent ->
                    select.asDynamic().selectedIndex = i
                    select.dispatchEvent(Event("change"))
                }
            }
        }
        syncListHighlight(select, container)
    }

    /** Re-highlights the row whose optionIndex matches the select's current selectedIndex. */
    private fun syncListHighlight(select: HTMLElement, container: HTMLElement) {
        val selected = select.asDynamic().selectedIndex as? Int ?: -1
        val rows = container.children
        for (i in 0 until rows.length) {
            val row = rows.asDynamic()[i] as? HTMLElement ?: continue
            val idx = row.asDynamic().optionIndex as? Int ?: -1
            if (idx == selected) row.classList.add("osadaListRow--selected")
            else row.classList.remove("osadaListRow--selected")
        }
    }

    private fun theaterPlaceholder(parent: HTMLElement) {
        val theater = addTag(parent, "div")
        theater.className = "osadaTheater"
    }

    /**
     * Point the campaign screen's theater banner at that campaign's own key art, falling back to
     * the shared placeholder. Art lives at `resources/ui/theater/<campaign-file-stem>.jpg`
     * (e.g. volarm.json -> volarm.jpg).
     *
     * JPEG, not PNG: these are photographic 1920x640 banners, and lossless PNG cost 2.1 MB each
     * (47 MB for the 22 campaigns) — a visible stall on every click in the campaign list, since the
     * browser fetches a banner the first time its campaign is selected. At q88 they are 212 KB and
     * indistinguishable on screen (the box renders at 948x222, so even a retina pass has pixels to
     * spare). Source PNGs live outside the served tree, in art-src/theater-spare/.
     *
     * The fallback needs no existence check: CSS paints background layers front-to-back, and a
     * layer whose URL 404s simply paints nothing — so listing the per-campaign image ABOVE the
     * placeholder yields the art when it exists and the placeholder when it doesn't. (The two
     * gradients must stay on top: they're the scrim the overlaid title/subtitle text reads against.)
     */
    private fun setTheaterArt(campaign: dynamic) {
        val theater = byId("smCampDossierHead")?.query(".osadaTheater") as? HTMLElement ?: return
        val stem = (campaign?.file as? String)?.removeSuffix(".json") ?: ""
        // 50% 0%: show the TOP of the art (user request) — matches .osadaTheater's own CSS rule.
        val layers = listOfNotNull(
            "linear-gradient(180deg, rgba(10,11,13,0) 42%, rgba(8,9,11,.94) 100%)",
            "linear-gradient(rgba(0,0,0,.10), rgba(0,0,0,.14))",
            if (stem.isNotBlank()) "url('resources/ui/theater/$stem.jpg') 50% 0% / cover no-repeat" else null,
            "url('resources/dossier_map_placeholder.png') 50% 0% / cover no-repeat"
        )
        theater.style.background = layers.joinToString(", ")
    }

    // ========================================================================
    // Register sorting + filtering.
    //
    // Both registers grew past the point where scrolling is a reasonable way to find something —
    // the scenario list is 399 entries in 24 campaign groups. Sorting and filtering act ONLY on the
    // rendered rows (reorder via appendChild, hide via display:none); the hidden <select> is left
    // completely alone. Rows keep the `optionIndex` that [buildSyncedList] stamped on them, so
    // clicking a row, [syncListHighlight] and the change-event plumbing all keep working against
    // the select's own (unsorted) indices regardless of what the view is doing.
    // ========================================================================

    private const val SORT_DEFAULT = "Default"
    private const val SORT_NAME = "A–Z"
    private const val SORT_YEAR = "Year"
    private const val SORT_SIZE = "Length"

    /** Sort keys are strings, and numeric ones are zero-padded to a fixed width, so one plain
     *  string comparison serves every mode (no per-mode comparator plumbing). */
    private fun pad(value: Int, width: Int = 5) = value.toString().padStart(width, '0')

    /** Stamps the sort/search keys a row is ranked and filtered by. [year] and [size] are absent
     *  for scenario rows, which sort by campaign (document order) or by name only. [sides] is the
     *  full set of side-filter labels ([countryDisplayLabel]) for every country playable in this
     *  row's scenario/campaign — a row matches the dropdown if ANY of them is selected. Empty
     *  (group headers) never matches a side filter. [forceHidden] permanently excludes the row
     *  regardless of filter/search/sort state (used to hide specific campaigns from this
     *  register — see [hiddenCampaignFiles]). */
    private fun tagRow(
        row: HTMLElement, index: Int, name: String,
        searchText: String = name, year: Int? = null, size: Int? = null, sides: List<String> = emptyList(),
        forceHidden: Boolean = false
    ) {
        val dyn = row.asDynamic()
        dyn.sortDefault = pad(index)
        dyn.sortName = name.lowercase()
        // Undated/unsized rows sort last rather than silently first. Name is appended as a
        // tie-breaker so equal years / equal lengths still come out in a stable, readable order.
        dyn.sortYear = pad(year ?: 9999, 4) + name.lowercase()
        dyn.sortSize = pad(size ?: 99999) + name.lowercase()
        dyn.searchText = searchText.lowercase()
        dyn.sideKeys = sides.toTypedArray()
        dyn.forceHidden = forceHidden
    }

    // ---- Side filter dropdown ---------------------------------------------------------------
    // Labels are keyed by the numeric country CODE, not the resolved name: several factions in
    // Equipment.countryNames share a literal name ("Germany" appears 3 times, "USSR"/"Soviet
    // Union" 3 times) while others read confusingly on their own ("Red Russia" / "White Russia" /
    // "Cossack Hosts" don't visually cluster as "the same country" in an alphabetical dropdown).
    // countryDisplayLabel re-labels the handful of ids where that actually matters (user request);
    // everything else falls back to Equipment.getCountryName so no country can silently vanish.

    private const val SIDE_ALL = "all"

    /** Curated overrides for country ids whose raw [Equipment.countryNames] entry either collides
     *  with another id's name, or would otherwise scatter alphabetically away from the other
     *  factions of the same nation. The dominant/"default" id for a nation (e.g. plain Germany,
     *  id 7, reused across every era after the eqp-merge) is deliberately left unlabeled — only
     *  the rarer, colliding ids get a "Nation — Faction" suffix so they cluster under it. Verified
     *  against the ~400 scenarios in scenariolist.js (2026-07-14): 55 distinct country ids appear;
     *  every id below was confirmed by checking which scenario(s) actually use it. */
    private val countryDisplayOverrides = mapOf(
        // Germany: id 7 stays plain "Germany" (113 scenarios, every era); id 86 is the same
        // regime under a different eqp-lxf code (RD Road To/Siege Of Berlin, 1945) — merge it.
        86 to "Germany",
        117 to "Germany — Empire",           // German Empire (Kaiserreich, WW1-era campaigns)
        196 to "Germany — Revolutionaries",  // German Revolutionaries (1918-19 Räterepublik)
        188 to "Germany — Communists",       // Red Germany
        303 to "Germany — Waffen SS",
        // Russia: id 19/61/89 are three efiles' spelling of the same Soviet Union and stay
        // merged as before; the OTHER Russia-named factions are civil-war-era and distinct from
        // each other AND from the USSR, but read better clustered under "Russia — X".
        19 to "Soviet Union", 61 to "Soviet Union", 89 to "Soviet Union",
        103 to "Russia — Communists",  // Red Russia
        100 to "Russia — Whites",      // White Russia
        189 to "Russia — Greens",      // Russian Green Armies
        191 to "Russia — Cossacks",    // Cossack Hosts
        // Hungary: id 4 stays plain; Red Hungary is the 1919 Soviet Republic.
        187 to "Hungary — Communists",
        // Spain: bn9s00 "Battle of Sesena" (eqp-lxf, id 28) confirmed vs a Soviet Union opponent —
        // a 1936 Nationalist offensive on Madrid, so id 28 is the Nationalist side, same as id 225.
        28 to "Spain — Nationalists",
        225 to "Spain — Nationalists",
        226 to "Spain — Republicans (Popular Army)",
        91 to "Spain — Republicans",
        // USA: id 9 stays plain; the Civil War factions don't share the "USA" word at all.
        150 to "USA — Confederacy",   // Confederate States
        162 to "USA — Union",         // Union States
    )

    /** The side-filter label for country [id], or null (never matches a filter) for an invalid/
     *  blank/"Unknown" code — mirrors the old name-based blank check. */
    private fun countryDisplayLabel(id: Int): String? {
        countryDisplayOverrides[id]?.let { return it }
        val name = Equipment.getCountryName(id)
        if (name.isBlank() || name == "Unknown") return null
        return name
    }

    // ---- Hidden campaigns -------------------------------------------------------------------
    // These Kaiser-efile campaigns were imported "flipped to the Red side" (the player commands
    // the Bolshevik forces), but the underlying scenario TEXT/outcomes were authored for the
    // opposite (White/anti-Bolshevik) campaign path and were not rewritten — so a player winning
    // early missions can still be handed later briefings written for the historical losing side
    // (e.g. "White Army marches forward" after a Red victory). Hiding from Campaign Selection
    // only (user request) until the path is actually reworked; the individual scenarios remain
    // playable, and honestly presented, from Scenario Selection (scenariolist.js is untouched).
    private val hiddenCampaignFiles = setOf(
        "volarm.json",    // The Defeat of Denikin
        "simpob.json",    // Sim Pobedishi! - The Red East
        "acampdf2.json",  // Czech Legion - Siberian Anabasis
        "polsov.json"     // The Polish-Soviet War: The Red Advance
    )

    // ---- Campaign progress ----------------------------------------------------------------

    /** The in-progress campaign from localStorage: (campaign file, 0-based scenario index).
     *  This is the SAME single-slot campaign block the main menu's Continue restores from —
     *  there is no per-campaign progress storage, so at most one campaign can be annotated. */
    private fun activeCampaignProgress(): Pair<String, Int>? {
        val majorVersion = VERSION.split(".").take(2).joinToString(".")
        val raw = localStorage.getItem("openpanzer-campaign-$majorVersion") ?: return null
        return try {
            val data = JSON.parse<dynamic>(raw)
            val file = data.file as? String ?: return null
            // Campaign.setScenarioById treats this id as the index into the campaign's scenario
            // array, so it doubles as the operation ordinal.
            val scenarioIndex = data.scenario as? Int ?: 0
            Pair(file, scenarioIndex)
        } catch (_: Throwable) {
            null
        }
    }

    // ---- Played-scenario history (3c) ------------------------------------------------------
    // A plain localStorage set of scenario FILE names, written when a standalone scenario is
    // started. Touches no game save; purely cosmetic ("New" vs "Played" chip).

    private const val PLAYED_SCENARIOS_KEY = "osada-played-scenarios"

    private fun playedScenarios(): Set<String> {
        val raw = localStorage.getItem(PLAYED_SCENARIOS_KEY) ?: return emptySet()
        return try {
            val arr = JSON.parse<Array<String>>(raw)
            arr.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun markScenarioPlayed(file: String) {
        if (file.isBlank()) return
        val updated = playedScenarios() + file
        localStorage.setItem(PLAYED_SCENARIOS_KEY, JSON.stringify(updated.toTypedArray()))
    }

    private fun rowsOf(list: HTMLElement): List<HTMLElement> {
        val children = list.children
        return (0 until children.length).mapNotNull { children.asDynamic()[it] as? HTMLElement }
    }

    /** Re-sorts and re-filters [list] from the mode/query/side-chip stashed on it by the toolbar,
     *  and updates the results counter. */
    private fun applyListView(list: HTMLElement) {
        val mode = list.asDynamic().sortMode as? String ?: SORT_DEFAULT
        val query = (list.asDynamic().filterQuery as? String ?: "").trim().lowercase()
        val side = list.asDynamic().sideFilter as? String ?: SIDE_ALL
        val rows = rowsOf(list)

        // A campaign-group header only means something in the campaign-ordered view; any other
        // sort interleaves campaigns, so the headers are hidden and the list reads as one flat run.
        val grouped = mode == SORT_DEFAULT
        val sorted = rows.sortedBy { row ->
            val dyn = row.asDynamic()
            when (mode) {
                SORT_NAME -> dyn.sortName as? String
                SORT_YEAR -> dyn.sortYear as? String
                SORT_SIZE -> dyn.sortSize as? String
                else -> dyn.sortDefault as? String
            } ?: ""
        }
        sorted.forEach { list.appendChild(it) }

        // Hide non-matching rows, then hide any group header left with nothing under it.
        var currentGroup: HTMLElement? = null
        var groupHasMatch = false
        var matches = 0
        fun closeGroup() {
            currentGroup?.style?.display = if (grouped && groupHasMatch) "" else "none"
        }
        for (row in sorted) {
            if (row.classList.contains("osadaListRow--group")) {
                closeGroup()
                currentGroup = row
                groupHasMatch = false
                continue
            }
            val forceHidden = row.asDynamic().forceHidden as? Boolean ?: false
            val text = row.asDynamic().searchText as? String ?: ""
            val rowSides = (row.asDynamic().sideKeys as? Array<String>) ?: emptyArray()
            val match = !forceHidden && (query.isEmpty() || text.contains(query)) &&
                    (side == SIDE_ALL || side in rowSides)
            row.style.display = if (match) "" else "none"
            if (match) {
                groupHasMatch = true
                matches++
            }
        }
        closeGroup()

        (list.asDynamic().counterEl as? HTMLElement)?.let { counter ->
            val noun = list.asDynamic().counterNoun as? String ?: "entries"
            val singular = noun.removeSuffix("s")
            counter.textContent = "$matches ${if (matches == 1) singular else noun}"
        }
    }

    /** Filter box + sort segments + side chips + results counter, inserted above [list] inside
     *  its register column. */
    private fun buildListToolbar(
        register: HTMLElement, list: HTMLElement, modes: List<String>,
        placeholder: String, counterNoun: String
    ) {
        val tools = addTag(register, "div")
        tools.className = "osadaListTools"
        // The register is a flex column whose list already exists — put the toolbar above it.
        register.insertBefore(tools, list)

        val filter = addTag(tools, "input")
        filter.className = "osadaListFilter"
        filter.setAttribute("type", "search")
        filter.setAttribute("placeholder", placeholder)
        filter.asDynamic().oninput = {
            list.asDynamic().filterQuery = filter.asDynamic().value as? String ?: ""
            applyListView(list)
        }

        // Second row: side dropdown (left, same control style as the equipment window's country
        // select) + results counter (right).
        val chipRow = addTag(register, "div")
        chipRow.className = "osadaChipRow"
        register.insertBefore(chipRow, list)
        val sideSelect = addTag(chipRow, "select")
        sideSelect.className = "osadaSideSelect"
        sideSelect.title = "Filter by country (any side — including AI-only factions)"
        // Options = every faction actually playable in this register (rows are already built and
        // tagged by the time the toolbar is inserted, one row can carry several sideKeys),
        // alphabetical after "All countries".
        addSelectOption(sideSelect, "All countries", SIDE_ALL, true)
        rowsOf(list)
            .flatMap { (it.asDynamic().sideKeys as? Array<String>)?.toList() ?: emptyList() }
            .distinct().sortedBy { it.lowercase() }
            .forEach { addSelectOption(sideSelect, it, it, false) }
        sideSelect.asDynamic().onchange = {
            val key = sideSelect.asDynamic().value as? String ?: SIDE_ALL
            list.asDynamic().sideFilter = key
            applyListView(list)
        }
        val counter = addTag(chipRow, "div")
        counter.className = "osadaListCount"
        list.asDynamic().counterEl = counter
        list.asDynamic().counterNoun = counterNoun
        list.asDynamic().sideFilter = SIDE_ALL

        val segs = addTag(tools, "div")
        segs.className = "osadaListSorts"
        modes.forEach { mode ->
            val seg = addTag(segs, "div")
            seg.className = "osada-seg" + if (mode == SORT_DEFAULT) " osada-seg--on" else ""
            seg.textContent = mode
            seg.title = when (mode) {
                SORT_DEFAULT -> "Campaign order"
                SORT_NAME -> "Alphabetical"
                SORT_YEAR -> "Earliest year first"
                SORT_SIZE -> "Fewest operations first"
                else -> mode
            }
            seg.onclick = { _: org.w3c.dom.events.MouseEvent ->
                rowsOf(segs).forEach { it.classList.remove("osada-seg--on") }
                seg.classList.add("osada-seg--on")
                list.asDynamic().sortMode = mode
                applyListView(list)
            }
        }
        list.asDynamic().sortMode = SORT_DEFAULT
        list.asDynamic().filterQuery = ""
        // Apply the initial view once: without this, forceHidden rows stayed visible and the
        // results counter stayed empty until the user first touched a filter/sort control.
        applyListView(list)
    }

    /** Restructures #smCamp into header / register / dossier / footer and fills the register. */
    private fun buildCampaignScreen(campSelect: HTMLElement) {
        val root = byId("smCamp") ?: return
        byId("smCampSel")?.classList?.add("osadaHiddenSelect")

        val header = addTag(root, "div")
        header.id = "smCampHeader"
        header.className = "osadaScreenHeader"
        header.textContent = "Campaign Selection"

        val body = addTag(root, "div")
        body.id = "smCampBody"
        body.className = "osadaScreenBody"

        val register = addTag(body, "div")
        register.id = "smCampRegister"
        register.className = "osadaRegister"
        val list = addTag(register, "div")
        list.id = "osadaCampList"
        list.className = "osadaList"

        val dossier = addTag(body, "div")
        dossier.id = "smCampDossier"
        dossier.className = "osadaDossier"

        val head = addTag(dossier, "div")
        head.id = "smCampDossierHead"
        head.className = "osadaDossierHead"
        theaterPlaceholder(head)
        val headText = addTag(head, "div")
        headText.className = "osadaDossierHeadText"
        val title = addTag(headText, "div")
        title.id = "smCampTitle"
        title.className = "osadaDossierTitle"
        val sub = addTag(headText, "div")
        sub.id = "smCampDossierSub"
        sub.className = "osadaDossierSub"

        byId("smCampInfo")?.let { dossier.appendChild(it) }
        byId("smCampDesc")?.let { dossier.appendChild(it) }

        val path = addTag(dossier, "div")
        path.id = "smCampPath"
        path.className = "osadaCollapse"
        val summary = addTag(path, "div")
        summary.className = "osadaCollapseSummary"
        summary.innerHTML = "Campaign path"
        val pathBody = addTag(path, "div")
        pathBody.id = "smCampPathBody"
        pathBody.className = "osadaCollapseBody"
        summary.onclick = { _: org.w3c.dom.events.MouseEvent -> toggleCampaignPath() }

        byId("smCampButtons")?.let { root.appendChild(it) }
        // The native flow glyph is superseded by the collapsible "Campaign path" line.
        byId("smCFlowBut")?.style?.display = "none"

        val progress = activeCampaignProgress()
        buildSyncedList(campSelect, list) { option, index, row, _ ->
            // option.value = the campaign's ORIGINAL campaignlist index; `index` is only the
            // option's position, and the two diverge once hidden campaigns are skipped at
            // option-build time (buildStartMenu).
            val campaignIndex = (option.asDynamic().value as? String)?.toIntOrNull() ?: index
            val campaign = campaignList().getOrNull(campaignIndex)
            val flag = addTag(row, "div")
            flag.className = "osadaFlag"
            val eqp = campaign?.eqp as? String ?: ""
            val flagId = campaign?.flag as? Int ?: 0
            if (eqp.isNotBlank()) {
                flag.style.backgroundImage = "url('resources/ui/flags/${Equipment.unitedName}/flags_med.png')"
                flag.style.backgroundPosition = "${-21 * flagId}px 0px"
            }
            val text = addTag(row, "div")
            text.className = "osadaListRowText"
            val name = addTag(text, "div")
            name.className = "osadaListRowName"
            name.textContent = option.text
            val rowSub = addTag(text, "div")
            rowSub.className = "osadaListRowSub"
            val ops = campaign?.scenarios as? Int
            rowSub.innerHTML = listOfNotNull(
                extractYears(option.text).ifBlank { null },
                ops?.let { "$it operations" }
            ).joinToString(" &middot; ")
            // In-progress annotation, right-aligned. Only ever ONE campaign can carry it: the
            // storage holds a single campaign slot (the one Continue resumes) — there is no
            // per-campaign progress history, and therefore no "Completed" state to show either.
            val file = campaign?.file as? String
            if (progress != null && file != null && progress.first == file) {
                val note = addTag(row, "div")
                note.className = "osadaListRowNote"
                val operation = progress.second + 1
                note.textContent = if (ops != null) "In progress · operation $operation/$ops"
                    else "In progress · operation $operation"
                note.title = "This is the campaign Continue resumes"
            }
            // Country is searchable too, so "soviet"/"spain" finds a campaign whose title says neither.
            val country = Equipment.getCountryNameByEqp(flagId, eqp)
            val sideKey = countryDisplayLabel(flagId)
            tagRow(row, index, option.text, "${option.text} $country", startYear(option.text), ops,
                sides = listOfNotNull(sideKey), forceHidden = file != null && file in hiddenCampaignFiles)
        }
        buildListToolbar(register, list, listOf(SORT_DEFAULT, SORT_NAME, SORT_YEAR, SORT_SIZE),
            "Filter campaigns…", "campaigns")
    }

    /** First 4-digit year in a campaign title ("Red Army Campaign (1936-1945)" -> 1936), used as
     *  the chronological sort key. Spartacus is dated "(73-71 BC)" — no 4-digit year to find, and
     *  it must sort FIRST, not last, so map any BC title to year 0 rather than to "unknown". */
    private fun startYear(title: String): Int? {
        if (title.contains("BC")) return 0
        return Regex("\\b(\\d{4})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Lazily computes and toggles the collapsible campaign-path (victory/defeat tree). */
    private fun toggleCampaignPath() {
        val path = byId("smCampPath") ?: return
        val body = byId("smCampPathBody") ?: return
        val open = path.classList.toggle("osadaCollapse--open")
        if (open && body.innerHTML.isBlank()) {
            val selectedCampaign = byId("smCamp")?.asDynamic()?.selectedCampaign as? Int
            if (selectedCampaign != null) {
                var campaignRef: Campaign? = null
                val campaign = Campaign(selectedCampaign, DIFFICULTY_HISTORICAL) {
                    campaignRef?.let { body.innerHTML = it.getCampaignFlow() }
                }
                campaignRef = campaign
            }
        }
    }

    private fun collapsePath(path: HTMLElement) {
        path.classList.remove("osadaCollapse--open")
        byId("smCampPathBody")?.innerHTML = ""
    }

    /** Restructures #smScen into header / register / dossier / footer and fills the register. */
    private fun buildScenarioScreen(scenSelect: HTMLElement) {
        val root = byId("smScen") ?: return
        byId("smScenSel")?.classList?.add("osadaHiddenSelect")

        val header = addTag(root, "div")
        header.id = "smScenHeader"
        header.className = "osadaScreenHeader"
        header.textContent = "Scenario Selection"

        val body = addTag(root, "div")
        body.id = "smScenBody"
        body.className = "osadaScreenBody"

        val register = addTag(body, "div")
        register.id = "smScenRegister"
        register.className = "osadaRegister"
        val list = addTag(register, "div")
        list.id = "osadaScenList"
        list.className = "osadaList"

        val dossier = addTag(body, "div")
        dossier.id = "smScenDossier"
        dossier.className = "osadaDossier"

        val head = addTag(dossier, "div")
        head.id = "smScenDossierHead"
        head.className = "osadaDossierHead"
        theaterPlaceholder(head)
        val headText = addTag(head, "div")
        headText.className = "osadaDossierHeadText"
        val title = addTag(headText, "div")
        title.id = "smScenTitle"
        title.className = "osadaDossierTitle"
        val sub = addTag(headText, "div")
        sub.id = "smScenDossierSub"
        sub.className = "osadaDossierSub"

        byId("smScenPlayers")?.let { dossier.appendChild(it) }
        byId("smScenDesc")?.let { dossier.appendChild(it) }

        byId("smScenButtons")?.let { root.appendChild(it) }

        // Group headers now name the CAMPAIGN each scenario belongs to (scenariolist.js was regrouped
        // from per-efile to per-campaign), so carry the group name into each row: it becomes the
        // row's second line, and makes the campaign searchable from the scenario filter.
        var group = ""
        val played = playedScenarios()
        buildSyncedList(scenSelect, list) { option, index, row, selectable ->
            if (!selectable) {
                group = option.text.replace("»", "").trim()
                row.textContent = group
                tagRow(row, index, group)
                return@buildSyncedList
            }
            // `dynamic` indexes with brackets, NOT ?.get(n) — a safe-call `get` compiles to a real
            // .get() METHOD call, which a JS array doesn't have ("scenario.get is not a function").
            val scenario: dynamic = scenarioList().getOrNull(index)
            val text = addTag(row, "div")
            text.className = "osadaListRowText"
            val title = option.text.trim()
            val name = addTag(text, "div")
            name.className = "osadaListRowName"
            name.textContent = title
            val rowSub = addTag(text, "div")
            rowSub.className = "osadaListRowSub"
            rowSub.textContent = group

            // Side chip / flag come from the country the HUMAN would play — player id 0, the one
            // the scenario screen itself defaults to human (see onScenSelectChange). A scenario has
            // two sides, so "this scenario's side" can only mean the playable one.
            val eqpName = (if (scenario != null) scenario[5] else null) as? String ?: ""
            val humanCountry = humanCountryOf(scenario)
            val countryName = if (humanCountry != null)
                Equipment.getCountryNameByEqp(humanCountry, eqpName) else ""
            if (humanCountry != null && eqpName.isNotBlank()) {
                val flag = addTag(row, "div")
                flag.className = "osadaFlag"
                flag.style.backgroundImage = "url('resources/ui/flags/${Equipment.unitedName}/flags_med.png')"
                flag.style.backgroundPosition = "${-21 * humanCountry}px 0px"
                flag.title = countryName
                // The flag belongs before the text column, which is already appended.
                row.insertBefore(flag, text)
            }

            val note = addTag(row, "div")
            val file = (if (scenario != null) scenario[0] else null) as? String ?: ""
            val isPlayed = file.isNotBlank() && file in played
            note.className = "osadaListRowNote" + if (isPlayed) " osadaListRowNote--played" else " osadaListRowNote--new"
            note.textContent = if (isPlayed) "Played" else "New"

            // Side FILTER covers every country playable in the scenario, not just the human's
            // default (id 0) — a scenario like Battle of Sesena (Soviet Union vs Spain) must be
            // findable under "Spain" too, since the scenario dossier's own AI/human toggles let
            // you take either side. All country names go into the search text for the same reason.
            val allCountries = allCountriesOf(scenario)
            val allCountryNames = allCountries.mapNotNull { Equipment.getCountryName(it).takeIf { n -> n.isNotBlank() && n != "Unknown" } }
            tagRow(row, index, title, "$title $group ${allCountryNames.joinToString(" ")}",
                sides = allCountries.mapNotNull { countryDisplayLabel(it) })
        }
        // No Year sort: scenariolist.js carries no date per scenario (it's in the scenario XML, which
        // isn't loaded until you start one). Campaign order already reads chronologically anyway.
        buildListToolbar(register, list, listOf(SORT_DEFAULT, SORT_NAME), "Filter scenarios…", "scenarios")
    }

    /** The country of player id 0 — the one the scenario screen makes HUMAN by default. Scans
     *  both side arrays (scenariolist entries put side 0 at index 3, side 1 at index 4), since
     *  the human player is not always on side 0. */
    private fun humanCountryOf(scenario: dynamic): Int? {
        if (scenario == null) return null
        for (side in 0..1) {
            val players = scenario[3 + side] as? Array<dynamic> ?: continue
            for (player in players) {
                if ((player.id as? Int ?: -1) == 0) return player.country as? Int
            }
        }
        return null
    }

    /** Every country playable in [scenario] — both sides, every player slot, distinct — for the
     *  side filter and search text. Unlike [humanCountryOf] this is NOT limited to player id 0:
     *  the scenario dossier's own AI/human toggles let you take any player's side, so a scenario
     *  must be findable by whichever country you'd actually play, not just the default human one.
     *  Same raw indexing as [humanCountryOf] (scenariolist `country` indexes Equipment.countryNames
     *  and the flag sheet directly — no ±1 shift). Also includes each player's `support` countries
     *  (tools/eqp-merge/add_support_countries.py, backfilled from the scenario XML's own
     *  `<player support="...">`) — an extra playable nationality fighting under the same player,
     *  e.g. Makhno's Black Army alongside Red Russia in "1920 Siege of Perekop". Without this, a
     *  scenario whose own blurb says "you will also command Makhno's Black Army" was unfindable by
     *  filtering for that faction (2026-07-14 user report) since scenariolist.js's `country` field
     *  only ever recorded each side's primary nationality. */
    private fun allCountriesOf(scenario: dynamic): List<Int> {
        if (scenario == null) return emptyList()
        val result = mutableListOf<Int>()
        for (side in 0..1) {
            val players = scenario[3 + side] as? Array<dynamic> ?: continue
            for (player in players) {
                (player.country as? Int)?.let { if (it !in result) result.add(it) }
                (player.support as? Array<dynamic>)?.forEach { s ->
                    (s as? Int)?.let { if (it !in result) result.add(it) }
                }
            }
        }
        return result
    }

    // ---- Scenario side picker (#smScenPlayers / #smSide0 / #smVS / #smSide1) ----------------
    // A scenario always has exactly two sides (scenariolist indices 3 and 4); a side can carry
    // more than one player entry when it has an AI-controlled auxiliary/reinforcement force
    // (e.g. bn9s06's two Soviet commands) or a distinct supporting nationality (player.support,
    // e.g. Makhno's Black Army). Only ONE player is ever "the human" — the picker toggles that
    // between each side's PRIMARY player (the side's first array entry) and leaves any
    // additional players on that side at AI, exactly mirroring the old "player id 0 defaults to
    // human, everyone else AI" rule this replaces (see selectScenarioSide).

    /** The side whose primary player is id 0 — the scenario's own default human side. */
    private fun defaultHumanSide(scenario: dynamic): Int {
        for (side in 0..1) {
            val players = scenario[3 + side] as? Array<dynamic> ?: continue
            if ((players.firstOrNull()?.id as? Int) == 0) return side
        }
        return 0
    }

    /** Distinct countries fighting on [side], primary player first, followed by any additional
     *  player/support countries — same de-dup rules as [allCountriesOf] but scoped to one side. */
    private fun sideCountries(scenario: dynamic, side: Int): List<Int> {
        val players = scenario[3 + side] as? Array<dynamic> ?: return emptyList()
        val result = mutableListOf<Int>()
        for (player in players) {
            (player.country as? Int)?.let { if (it !in result) result.add(it) }
            (player.support as? Array<dynamic>)?.forEach { s ->
                (s as? Int)?.let { if (it !in result) result.add(it) }
            }
        }
        return result
    }

    /** (primary display name, count of additional distinct countries on that side) — naming
     *  fallback chain: curated [countryDisplayLabel] -> raw country name -> "Side N". */
    private fun sideLabel(scenario: dynamic, side: Int, eqpName: String): Pair<String, Int> {
        val countries = sideCountries(scenario, side)
        val primary = countries.firstOrNull()
        val name = primary?.let { c ->
            countryDisplayLabel(c) ?: Equipment.getCountryNameByEqp(c, eqpName)
                .let { n -> if (n.isBlank() || n == "Unknown") null else n }
        } ?: "Side ${side + 1}"
        return Pair(name, maxOf(0, countries.size - 1))
    }

    /** Sets which side is human (mutating the SAME [uiSettings.isAI] the game launch reads —
     *  deliberately not a second selection state), then rebuilds the two cards and the Start
     *  button label. A side with no players at all (shouldn't happen in real data, but the UI
     *  contract requires it) can't be selected. */
    private fun selectScenarioSide(scenario: dynamic, side: Int) {
        val players0 = scenario[3] as? Array<dynamic> ?: emptyArray()
        val players1 = scenario[4] as? Array<dynamic> ?: emptyArray()
        val target = if (side == 0) players0 else players1
        if (target.isNotEmpty()) {
            for (s in 0..1) {
                val players = if (s == 0) players0 else players1
                val primaryId = players.firstOrNull()?.id as? Int
                for (player in players) {
                    val pid = player.id as? Int ?: continue
                    uiSettings.isAI[pid] = if (pid == primaryId && s == side) 0 else 1
                }
            }
        }
        // renderSideCards falls back to whichever side IS available if [side] turns out to be
        // empty (shouldn't happen in real data); use its resolved side for the Start button too,
        // so the two never disagree.
        val effectiveSide = renderSideCards(scenario, side)
        updateStartButtonLabel(scenario, effectiveSide)
    }

    private fun updateStartButtonLabel(scenario: dynamic, side: Int) {
        val eqpName = scenario[5] as? String ?: ""
        val (name, _) = sideLabel(scenario, side, eqpName)
        byId("smSPlayBut")?.setAttribute("data-label", "Start as $name")
    }

    /** Rebuilds the two side cards + divider in place. Never recreates #smScenPlayers/#smSide0/
     *  #smSide1/#smVS themselves (load-bearing ids the launch wiring and legacy CSS depend on) —
     *  only their contents/attributes, exactly like the code this replaces did. */
    private fun renderSideCards(scenario: dynamic, selectedSide: Int): Int {
        val eqpName = scenario[5] as? String ?: ""
        val players0 = scenario[3] as? Array<dynamic> ?: emptyArray()
        val players1 = scenario[4] as? Array<dynamic> ?: emptyArray()
        val available = booleanArrayOf(players0.isNotEmpty(), players1.isNotEmpty())
        val effectiveSelected = if (available.getOrElse(selectedSide) { false }) selectedSide
            else available.indexOfFirst { it }.coerceAtLeast(0)
        val focusSide = effectiveSelected

        val playersRoot = byId("smScenPlayers")
        playersRoot?.apply {
            className = "osada-side-picker"
            setAttribute("role", "radiogroup")
            setAttribute("aria-label", "Choose your side")
        }
        val side0El = byId("smSide0")
        if (playersRoot != null && byId("osadaSidePickerHeading") == null) {
            // Real markup has #smSide0 already nested under #smScenPlayers -> insert before it so
            // it reads first; a flat/synthetic fixture (no such nesting) just appends instead,
            // since insertBefore requires the reference node to actually be a child of parentNode.
            val heading = if (side0El != null && side0El.parentNode === playersRoot)
                insertTag(playersRoot, "div", side0El) else addTag(playersRoot, "div")
            heading.id = "osadaSidePickerHeading"
            heading.className = "osada-side-picker__heading"
            heading.textContent = "Choose your side"
        }

        byId("smVS")?.apply {
            className = "osada-side-divider"
            setAttribute("aria-hidden", "true")
            innerHTML = "<span class=\"osada-side-divider__medallion\">VS</span>"
        }

        for (side in 0..1) {
            val container = byId("smSide$side") ?: continue
            clearTag(container)
            buildSideCardContent(container, scenario, side, effectiveSelected, available[side], focusSide, eqpName)
        }
        return effectiveSelected
    }

    private fun buildSideCardContent(
        container: HTMLElement, scenario: dynamic, side: Int,
        selectedSide: Int, available: Boolean, focusSide: Int, eqpName: String
    ) {
        val (name, extra) = sideLabel(scenario, side, eqpName)
        val primaryCountry = sideCountries(scenario, side).firstOrNull()
        val isSelected = available && side == selectedSide

        container.className = "osada-side-card" +
            (if (isSelected) " is-selected" else "") +
            (if (!available) " is-disabled" else "")
        container.setAttribute("role", "radio")
        container.setAttribute("aria-checked", if (isSelected) "true" else "false")
        if (!available) container.setAttribute("aria-disabled", "true") else container.removeAttribute("aria-disabled")
        container.tabIndex = if (available && side == focusSide) 0 else -1

        val row = addTag(container, "div")
        row.className = "osada-side-card__row"
        if (primaryCountry != null) {
            val flag = addTag(row, "div")
            flag.className = "playerCountry osada-side-card__flag"
            flag.style.backgroundPosition = "${-21 * primaryCountry}px 0px"
        }
        val nameEl = addTag(row, "div")
        nameEl.className = "osada-side-card__name"
        nameEl.textContent = name
        nameEl.title = name

        val badgeRow = addTag(container, "div")
        badgeRow.className = "osada-side-card__badgerow"
        if (extra > 0) {
            val sub = addTag(badgeRow, "span")
            sub.className = "osada-side-card__sub"
            sub.textContent = "+$extra"
            sub.title = "$extra additional " + (if (extra == 1) "nation" else "nations") + " fighting on this side"
        }
        val badge = addTag(badgeRow, "span")
        badge.className = "osada-side-card__badge"
        badge.textContent = when {
            !available -> "AI CONTROLLED"
            isSelected -> "✓ PLAYER"
            else -> "SELECT SIDE"
        }

        if (available) {
            container.onclick = { _: org.w3c.dom.events.MouseEvent -> selectScenarioSide(scenario, side) }
            container.onkeydown = { e -> onSideCardKeydown(e, scenario, side) }
        } else {
            container.onclick = null
            container.onkeydown = null
        }
    }

    private fun onSideCardKeydown(e: org.w3c.dom.events.KeyboardEvent, scenario: dynamic, side: Int) {
        when (e.asDynamic().key as? String) {
            "Enter", " " -> { e.preventDefault(); selectScenarioSide(scenario, side) }
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown" -> {
                e.preventDefault()
                val other = 1 - side
                byId("smSide$other")?.let { el -> if (el.getAttribute("aria-disabled") != "true") el.focus() }
            }
        }
    }
}
