package org.osada.ui

import org.osada.GameHolder
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * Builds the start menu: main buttons, the campaign and scenario selection panels, the
 * in-menu settings (sliders + checkboxes) and the player/AI side setup. Extracted from
 * the former `UIBuilder` god-object. Shared data (image paths, currency icon) lives on
 * the [UIBuilder] facade; layout helpers come from [UILayout] and the save/load sub-screen
 * from [GameStateMenuBuilder]. The main-buttons column, campaign screen, scenario screen,
 * side picker, settings screen and shared list-toolbar plumbing are split into
 * [StartMenuMainButtons], [StartMenuCampaignScreen] (+ [StartMenuCampaignData]),
 * [StartMenuScenarioScreen] (+ [StartMenuSidePicker]), [StartMenuSettingsBuilder] and
 * [StartMenuListToolbar] respectively, to stay within the project's function-count/class-size
 * limits. Still 12 vs. the 11-function budget after that split; further slicing one member into
 * a seventh file was judged worse for readability than the small overage.
 */
@Suppress("TooManyFunctions")
internal object StartMenuBuilder {
    private var startMenuBuilt = false

    fun resetStartMenuBuilt() {
        startMenuBuilt = false
    }

    fun campaignList(): Array<dynamic> =
        js("typeof campaignlist !== 'undefined' ? campaignlist : []").unsafeCast<Array<dynamic>>()

    fun scenarioList(): Array<dynamic> =
        js("typeof scenariolist !== 'undefined' ? scenariolist : []").unsafeCast<Array<dynamic>>()

    fun continueCampaign(outcome: String) {
        makeHidden("smCamp")
        makeHidden("startmenu")
        gameRef()?.continueCampaign(outcome)
    }

    fun startNewCampaign(
        campaignId: Int,
        difficulty: Int,
    ) {
        makeHidden("gameToolTip")
        makeHidden("smCamp")
        makeHidden("startmenu")
        // A campaign's rules are resolved and locked here, once, and stay immutable through every
        // mission, save, transition and Restart Mission (`docs/design/ruleset-profiles.md` §4).
        // Editing, renaming or deleting the named profile afterwards affects future starts only.
        RulesetSelection.apply(RulesetSelection.Surface.CAMPAIGN)
        val game = gameRef()
        game?.newCampaign(campaignId, difficulty)
        byId("options")?.let { toggleButton(it, false) }
        game?.state?.saveSettings()
    }

    /** Resumes an existing campaign run (the campaign register's default Play action once a run
     *  exists, and "Continue"'s per-row equivalent) instead of starting a fresh one. */
    fun resumeCampaignRun(campaignRunId: String) {
        makeHidden("gameToolTip")
        makeHidden("smCamp")
        makeHidden("startmenu")
        val game = gameRef()
        game?.state?.restoreCampaignRun(
            campaignRunId,
            onSuccess = { byId("options")?.let { toggleButton(it, false) } },
            onFail = {
                console.error("[osada] resumeCampaignRun failed for", campaignRunId)
                makeVisible("startmenu")
                makeVisible("smCamp")
            },
        )
    }

    fun startNewScenario(
        file: String,
        description: String,
    ) {
        makeHidden("gameToolTip")
        makeHidden("smScen")
        makeHidden("startmenu")
        StartMenuScenarioScreen.markScenarioPlayed(file) // cosmetic New/Played chip only; touches no game save
        // A standalone scenario resolves once per launch; replaying it may choose another profile.
        RulesetSelection.apply(RulesetSelection.Surface.SCENARIO)
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
        StartMenuMainButtons.buildMainButtons()
        applyContinueButtonState()

        // Rotating quote, bottom-center of the main menu; re-rolled every time the menu is
        // shown (applyContinueButtonState runs on each show).
        val quote = addTag("smMain", "div")
        quote.id = "smQuote"
        quote.className = "osada-quote"
        showRandomQuote()

        StartMenuCampaignScreen.buildCampaignSelection()
        StartMenuScenarioScreen.buildScenarioSelection()
        StartMenuSettingsBuilder.buildSettingsScreen()

        GameStateMenuBuilder.buildGameStateMenu()
    }

    // Shown one at a time, bottom-center of the main menu (user request).
    private data class MenuQuote(
        val textKey: String,
        val authorKey: String,
    )

    private val menuQuotes =
        listOf(
            MenuQuote("menu.quotes.stalin.tactics_day.text", "menu.quotes.stalin.tactics_day.author"),
            MenuQuote("menu.quotes.lenin.who_will_prevail.text", "menu.quotes.lenin.who_will_prevail.author"),
            MenuQuote("menu.quotes.stalin.victory_must_be_won.text", "menu.quotes.stalin.victory_must_be_won.author"),
            MenuQuote(
                "menu.quotes.stalin.tactics_part_of_strategy.text",
                "menu.quotes.stalin.tactics_part_of_strategy.author",
            ),
            MenuQuote("menu.quotes.lenin.decisive_point.text", "menu.quotes.lenin.decisive_point.author"),
            MenuQuote("menu.quotes.lenin.attack_and_retreat.text", "menu.quotes.lenin.attack_and_retreat.author"),
            MenuQuote("menu.quotes.marx.change_the_world.text", "menu.quotes.marx.change_the_world.author"),
            MenuQuote("menu.quotes.marx_engels.workers_unite.text", "menu.quotes.marx_engels.workers_unite.author"),
            MenuQuote("menu.quotes.marx.locomotives_of_history.text", "menu.quotes.marx.locomotives_of_history.author"),
            MenuQuote("menu.quotes.stalin.no_fortresses.text", "menu.quotes.stalin.no_fortresses.author"),
            MenuQuote("menu.quotes.marx.force_midwife.text", "menu.quotes.marx.force_midwife.author"),
            MenuQuote("menu.quotes.marx.weapon_of_criticism.text", "menu.quotes.marx.weapon_of_criticism.author"),
            MenuQuote("menu.quotes.engels.mass_insurrection.text", "menu.quotes.engels.mass_insurrection.author"),
            MenuQuote("menu.quotes.engels.new_military_methods.text", "menu.quotes.engels.new_military_methods.author"),
            MenuQuote(
                "menu.quotes.engels.army_economic_conditions.text",
                "menu.quotes.engels.army_economic_conditions.author",
            ),
            MenuQuote("menu.quotes.lenin.war_politics.text", "menu.quotes.lenin.war_politics.author"),
            MenuQuote("menu.quotes.lenin.do_not_lay_down_arms.text", "menu.quotes.lenin.do_not_lay_down_arms.author"),
            MenuQuote(
                "menu.quotes.lenin.revolution_defend_itself.text",
                "menu.quotes.lenin.revolution_defend_itself.author",
            ),
            MenuQuote("menu.quotes.lenin.peace_breathing_space.text", "menu.quotes.lenin.peace_breathing_space.author"),
            MenuQuote("menu.quotes.stalin.artillery_god_of_war.text", "menu.quotes.stalin.artillery_god_of_war.author"),
            MenuQuote("menu.quotes.stalin.master_all_forms.text", "menu.quotes.stalin.master_all_forms.author"),
            MenuQuote("menu.quotes.lenin.morale_decisive.text", "menu.quotes.lenin.morale_decisive.author"),
            MenuQuote("menu.quotes.stalin.no_mercy.text", "menu.quotes.stalin.no_mercy.author"),
        )

    private fun showRandomQuote() {
        val el = byId("smQuote") ?: return
        val quote = menuQuotes.random()
        val text = I18n.t(quote.textKey)
        val author = I18n.t(quote.authorKey)
        el.innerHTML = "<span class=\"osada-quote__text\">“$text”</span>" +
            "<span class=\"osada-quote__author\">— $author</span>"
    }

    internal fun refreshRandomQuote() = showRandomQuote()

    /** Show/hide the Continue button based on a saved game in localStorage, and annotate it
     *  with cheap save metadata (scenario name + turn) when that is readable. Re-invoked every
     *  time the main menu is shown (MainMenuButtonHandler "options"), so a save created mid-session
     *  makes Continue appear without a page reload. */
    internal fun applyContinueButtonState() {
        showRandomQuote()
        applyRestartButtonState()
        // Save/Load menu entry mirrors what the window can actually do from here: pre-game
        // only loading is possible; from the in-game pause menu it is the full pair.
        byId("saveload")?.let { btn ->
            val inGame = GameHolder.instance?.gameStarted == true
            val keyPrefix = if (inGame) "menu.main.save_load" else "menu.main.load_game"
            (btn.query(".osada-menu-btn__label") as? HTMLElement)?.textContent =
                I18n.t("$keyPrefix.label")
            (btn.query(".osada-menu-btn__sub") as? HTMLElement)?.textContent =
                I18n.t("$keyPrefix.subtitle")
            btn.title = I18n.t("$keyPrefix.subtitle")
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

    /** Restart is an in-game action backed by the immutable opening checkpoint. It is absent on
     *  the pre-game menu, for old saves without a checkpoint, and in multiplayer. */
    internal fun applyRestartButtonState() {
        val available = GameHolder.instance?.missionRestartCheckpoint?.isAvailable() == true
        byId("restartmission")?.style?.display = if (available) "" else "none"
    }

    /**
     * null -> no saved game (hide Continue); "" -> a save exists but its metadata is unreadable
     * (show Continue, leave the subtitle alone); otherwise a short "Name · Turn n/m" summary.
     *
     * The save state itself comes from [org.osada.GameStatePersistence.savedGameSummary], which
     * owns the storage keys. This used to read `osada-scenario-<major>` directly -- a key the
     * per-campaign-run repository no longer writes and `clear()` deletes -- so Continue vanished
     * from the menu entirely. Only the localization of an already-decided answer belongs here.
     *
     * Not private: [LiveLocalization] re-renders the same subtitle after a language switch and
     * must phrase it identically.
     */
    internal fun savedGameSummary(): String? {
        val summary = GameHolder.instance?.state?.savedGameSummary() ?: return null
        return when {
            summary.name.isBlank() -> ""
            summary.maxTurns > 0 ->
                I18n.t(
                    "menu.save.summary_full",
                    mapOf("name" to summary.name, "turn" to summary.turn, "maxTurns" to summary.maxTurns),
                )

            else ->
                I18n.t(
                    "menu.save.summary_short",
                    mapOf("name" to summary.name, "turn" to summary.turn),
                )
        }
    }
}
