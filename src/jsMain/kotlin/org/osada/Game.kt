package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.model.Player
import org.osada.model.getPlayers
import org.osada.scenario.Campaign
import org.osada.scenario.Scenario
import org.osada.ui.UI
import org.osada.ui.UIBuilder
import org.osada.ui.briefing.BriefingIntroTracker
import org.osada.ui.makeVisible
import org.osada.ui.messageDynamic
import org.osada.ui.showAIStatus

private const val DEFAULT_SCENARIO = "tutorial.xml"
private val DEFAULT_SCENARIO_AI = listOf(0, 1, 0, 0)

object GameHolder {
    var instance: Game? = null
}

val Game.Companion.current: Game? get() = GameHolder.instance

@JsExport
@JsName("Game")
class Game {
    var scenario: Scenario? = null
    var campaign: Campaign? = null
    var state: GameState? = null
    var ui: UI? = null

    var gameStarted: Boolean = false
    var gameEnded: Boolean = false
    var waitUIAnimation: Boolean = false
    var spotSide: Int = -1
    var uiMessageClicked: Boolean = false

    internal var humanSides: Int = -1
    internal var campaignPlayer: Player? = null
    internal var savedCampaignPlayer: Player? = null
    internal var continueCampaignFlag: Boolean = false
    internal var removeNonCampaignUnitsFlag: Boolean = false
    internal var buildCoreUnitsFlag: Boolean = false
    internal var awardPrototype: Boolean = false
    internal var nextScenarioData: dynamic = null

    /** Raw optional briefing data and visibility flag for the scenario currently being loaded. */
    internal var pendingScenarioBriefing: dynamic = null
    private var pendingScenarioBriefingEnabled: Boolean = true

    companion object {
        internal const val END_TURN_INFO_DELAY_MS = 2500

        // camelCase kept (not SCREAMING_SNAKE_CASE) because `Game` is @JsExport: this constant
        // is part of the exported JS-facing API surface as `Game.defaultScenario`.
        @Suppress("ktlint:standard:property-naming")
        const val defaultScenario: String = DEFAULT_SCENARIO
        val defaultScenarioAI: List<Int> = DEFAULT_SCENARIO_AI
    }

    init {
        GameHolder.instance = this
    }

    fun init() {
        console.log("[OSADA] Game.init start")
        state = GameState(this)
        console.log("[OSADA] Game.init calling state.restore")
        state?.restore(
            onSuccess = {
                console.log("[OSADA] Game.init restore onSuccess")
                onScenarioLoadFinished(null, true)
            },
            onFail = {
                console.log("[OSADA] Game.init restore onFail -> creating UI and showing start menu")
                ui = UI(this)
                makeVisible("startmenu")
                makeVisible("smMain")
            },
        )
        console.log("[OSADA] Game.init state.restore dispatched")
    }

    fun processTurn() {
        if (!gameStarted || gameEnded) return
        if (nextScenarioData != null && continueCampaignFlag && uiMessageClicked) {
            startPendingScenarioTransition()
        } else {
            continueCurrentTurn()
        }
    }

    fun endTurn() {
        val side = scenario?.map?.currentPlayer?.side ?: return
        waitUIAnimation = false
        scenario?.endTurn()
        if (scenario?.checkDefeat(side, humanSides) == true) {
            if (campaign != null) {
                continueCampaign("lose", EndGameType.NO_TURNS_LEFT)
            } else {
                gameEnded = true
            }
        } else {
            state?.save()
            campaign?.let { state?.saveCampaign() }
            setCurrentSide()
            org.osada.ui.WeatherModel
                .advance(scenario)
            deployReinforcements(scenario!!.map.turn, scenario!!.map.currentPlayer!!.id)
            // Show the "Computer turn in progress" banner the moment the AI turn begins (was only
            // shown via the delayed end-turn-info path, so it never appeared while the AI was thinking).
            val nextType = scenario?.map?.currentPlayer?.type
            if (nextType == PlayerType.AI_LOCAL || nextType == PlayerType.AI_SCRIPTED) {
                UIBuilder.showAIStatus(true)
            }
        }
    }

    fun setupGameState() {
        console.log("[OSADA] setupGameState")
        savedCampaignPlayer = null
        setupPlayers()
        humanSides = countHumanSides(scenario?.map?.getPlayers()?.toList() ?: emptyList())
        if (DEBUG_AI_MOVES) humanSides = 2
        setCurrentSide()
        gameStarted = true
        gameEnded = false
        campaign?.let { state?.saveCampaign() }
        state?.save()
        // setupGameState() is the disk/import restore path. A battle already in progress must
        // resume immediately instead of replaying its campaign briefing.
        pendingScenarioBriefing = null
        pendingScenarioBriefingEnabled = false
        ui?.setNewScenario()
    }

    fun newScenario(
        file: String,
        intro: String?,
    ) {
        console.log("[OSADA] newScenario", file, "intro:", intro)
        if (file == "failRestore") {
            console.log("[OSADA] newScenario failRestore -> reset AI and default scenario")
            for (i in uiSettings.isAI.indices) {
                uiSettings.isAI[i] = defaultScenarioAI[i]
            }
            return newScenario(defaultScenario, null)
        }
        cleanup()
        scenario = Scenario(file)
        scenario?.load {
            console.log("[OSADA] Scenario.load callback for", file)
            onScenarioLoadFinished(intro, false)
        }
    }

    fun onScenarioLoadFinished(
        intro: String?,
        fromRestore: Boolean,
    ) {
        console.log("[OSADA] onScenarioLoadFinished fromRestore:", fromRestore, "isLoaded:", scenario?.isLoaded)
        if (scenario?.isLoaded != true) {
            UIBuilder.messageDynamic("Error", "Error Loading scenario ${scenario?.file}")
            gameEnded = true
            return
        }
        // isNotBlank, not just non-null: a campaign's per-scenario `intro` is "" (not absent) for the
        // 10 imported campaigns whose OG briefing text files use a filename convention cam_to_json
        // didn't recognise. Overwriting with that blanked the briefing popup on every campaign start,
        // even though Scenario's own ctor had already seeded a good description from the scenariolist
        // entry — the very text the scenario-select screen shows. Fall back to it instead.
        intro?.takeIf { it.isNotBlank() }?.let { scenario?.setDescription(it) }
        setupPlayers()
        humanSides = countHumanSides(scenario?.map?.getPlayers()?.toList() ?: emptyList())
        setCurrentSide()
        waitUIAnimation = false
        gameStarted = true
        gameEnded = false
        // A restored battle resumes immediately; a newly loaded battle is unblocked only after
        // the player closes its briefing.
        uiMessageClicked = fromRestore
        pendingScenarioBriefingEnabled = !fromRestore

        if (campaign != null) {
            handleCampaignScenarioLoaded()
        } else {
            handleStandaloneScenarioLoaded(fromRestore)
        }
        state?.save()

        ensureUiCreated()
        console.log("[OSADA] onScenarioLoadFinished calling setNewScenario")
        ui?.setNewScenario()
    }

    fun newCampaign(
        id: Int,
        difficulty: Int,
    ) {
        console.log("[OSADA] newCampaign", id, difficulty)
        pendingScenarioBriefing = null
        pendingScenarioBriefingEnabled = true
        BriefingIntroTracker.reset()
        // A new run starts with no remembered outcomes, choices, flags or queued effects.
        CampaignNarrative.reset()
        // ...and with no formations or heroes carried over from a previous run.
        HeroCampaign.reset()
        campaign = Campaign(id, difficulty) { onCampaignLoadFinished() }
    }

    /** Return and consume briefing data for the battle that has just finished loading. */
    internal fun takeScenarioBriefing(): dynamic {
        val result = pendingScenarioBriefing
        pendingScenarioBriefing = null
        return result
    }

    /** Whether the currently loaded battle should show its opening briefing. Consumed once. */
    internal fun takeScenarioBriefingEnabled(): Boolean {
        val result = pendingScenarioBriefingEnabled
        pendingScenarioBriefingEnabled = true
        return result
    }

    val loop =
        js("setInterval")(fun() {
            processTurn()
        }, 1000)
}
