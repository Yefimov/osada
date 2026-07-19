package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.GameHolder
import org.osada.VERSION
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
 * limits.
 */
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
        val game = gameRef()
        game?.newCampaign(campaignId, difficulty)
        byId("options")?.let { toggleButton(it, false) }
        game?.state?.saveSettings()
    }

    fun startNewScenario(
        file: String,
        description: String,
    ) {
        makeHidden("gameToolTip")
        makeHidden("smScen")
        makeHidden("startmenu")
        StartMenuScenarioScreen.markScenarioPlayed(file) // cosmetic New/Played chip only; touches no game save
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
    private val menuQuotes =
        listOf(
            "Tactics are the questions of the day, strategy the questions of the epoch." to "J. Stalin",
            "Who will prevail over whom — that is the fundamental question of every revolution." to "V. I. Lenin",
            "Victory does not come by itself — it must be won." to "J. Stalin",
            "Tactics are a part of strategy, subordinate to it and serving it." to "J. Stalin",
            "Concentrate a great superiority of forces at the decisive point." to "V. I. Lenin",
            "Victory is impossible unless they have learned both how to attack and how to retreat properly." to
                "V. I. Lenin",
            "The philosophers have only interpreted the world, in various ways; the point, however, is to change it." to
                "K. Marx",
            "Workers of the world, unite!" to "K. Marx & F. Engels",
            "Revolutions are the locomotives of history." to "K. Marx",
            "There are no fortresses that Bolsheviks cannot storm." to "J. Stalin",
            "Force is the midwife of every old society pregnant with a new one." to "K. Marx",
            "The weapon of criticism cannot, of course, replace criticism of the weapon. " +
                "Material force must be overthrown by material force." to
                "K. Marx",
            "Mass insurrection, revolutionary war, guerrilla detachments everywhere — this is the only " +
                "method by which a small nation can overcome a large one." to
                "F. Engels",
            "The emancipation of the proletariat will have its own special expression in military " +
                "affairs and will create its own, new military methods." to
                "F. Engels",
            "Nothing is more dependent on economic conditions than precisely the army and the navy. " +
                "Armament, composition, organization, tactics and strategy depend above all on the " +
                "stage reached at the time in production." to
                "F. Engels",
            "War is the continuation of politics by other means." to "V. I. Lenin",
            "Once you have taken up arms, do not lay them down until the enemy is completely crushed." to "V. I. Lenin",
            "A revolution is only worth something if it can defend itself." to "V. I. Lenin",
            "Peace is a breathing-space for war." to "V. I. Lenin",
            "Artillery is the god of war." to "J. Stalin",
            "The art of war in modern conditions consists in mastering all forms of warfare and in " +
                "using them intelligently." to
                "J. Stalin",
            "In modern war, the morale of the people is one of the decisive factors." to "V. I. Lenin",
            "No mercy to the enemy!" to "J. Stalin",
        )

    private fun showRandomQuote() {
        val el = byId("smQuote") ?: return
        val (text, author) = menuQuotes.random()
        el.innerHTML = "<span class=\"osada-quote__text\">“$text”</span>" +
            "<span class=\"osada-quote__author\">— $author</span>"
    }

    /** Show/hide the Continue button based on a saved game in localStorage, and annotate it
     *  with cheap save metadata (scenario name + turn) when that is readable. Re-invoked every
     *  time the main menu is shown (MainMenuButtonHandler "options"), so a save created mid-session
     *  makes Continue appear without a page reload. */
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
        val raw = localStorage.getItem("osada-scenario-$majorVersion") ?: return null
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
}
