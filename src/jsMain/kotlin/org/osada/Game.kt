package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.i18n.I18n
import org.osada.model.Player
import org.osada.model.getPlayers
import org.osada.rules.GameRandomSource
import org.osada.scenario.Campaign
import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioTextLocalization
import org.osada.ui.ScenarioMusic
import org.osada.ui.UI
import org.osada.ui.UIBuilder
import org.osada.ui.briefing.BriefingIntroTracker
import org.osada.ui.makeVisible
import org.osada.ui.messageDynamic
import org.osada.ui.showAIStatus
import kotlin.js.Date

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

    internal val missionRestartCheckpoint = MissionRestartCheckpoint(this)

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

    /** A restored save's core roster, parked until [setupPlayers] has assigned [campaignPlayer].
     *  Consumed exactly once, by `handleCampaignScenarioLoaded`. */
    internal var pendingCoreUnitRestore: PendingCoreUnitRestore? = null
    internal var awardPrototype: Boolean = false
    internal var nextScenarioData: dynamic = null

    /** Raw optional briefing data and visibility flag for the scenario currently being loaded. */
    internal var pendingScenarioBriefing: dynamic = null
    private var pendingScenarioBriefingEnabled: Boolean = true

    companion object {
        internal const val END_TURN_INFO_DELAY_MS = 2500

        // camelCase kept (not SCREAMING_SNAKE_CASE) because `Game` is @JsExport: this constant
        // is part of the exported JS-facing API surface as `Game.defaultScenario`.
        @Suppress("ConstPropertyName", "ktlint:standard:property-naming")
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
        // BEFORE scenario.endTurn(): that call hands the turn over and, for an AI player, builds
        // its ENTIRE action list for the turn in one pass (GameMap.endTurn -> handler.buildActions).
        // A unit an event places after that point is invisible to the plan already made, so the
        // garrison would ignore freshly revealed prisoners for a whole activation. The move-animation
        // hook is what normally fires proximity events mid-turn; this keeps the turn-hand-off safety
        // net behaving identically instead of one turn late.
        evaluateScenarioEvents()
        scenario?.endTurn()
        val timedOutcome = scenario?.checkTimedOutcome(side, humanSides)
        if (timedOutcome != null) {
            if (campaign != null) {
                continueCampaign(timedOutcome, EndGameType.NO_TURNS_LEFT)
            } else {
                gameEnded = true
            }
        } else {
            setCurrentSide()
            org.osada.ui.WeatherModel
                .advance(scenario)
            deployArrivingReinforcements()
            // AFTER the new turn is fully set up, not before it. The autosave used to be taken
            // here first, which wrote a snapshot of a turn that had already been handed over --
            // `map.turn` incremented -- but had not yet had its weather rolled or its
            // reinforcements placed. Neither step is replayed on load (`WeatherModel.init` seeds
            // `lastTurn` from the save's own turn, so `advance` refuses to fire for it, and
            // reinforcements are only deployed from this method), so resuming that save skipped
            // the turn's weather transition outright and left its arrivals waiting a further
            // round. Saving last means the save is the state the player actually resumes into.
            state?.save()
            campaign?.let { state?.saveCampaign() }
            // Show the "Computer turn in progress" banner the moment the AI turn begins (was only
            // shown via the delayed end-turn-info path, so it never appeared while the AI was thinking).
            val nextType = scenario?.map?.currentPlayer?.type
            if (nextType == PlayerType.AI_LOCAL || nextType == PlayerType.AI_SCRIPTED) {
                UIBuilder.showAIStatus(true)
            }
        }
    }

    /**
     * OG's *"Reinforces arrive when player is active: reinforcements arrive in the player turn, not
     * at the start of the Player 1 turn"* (`Manual_OSuite-Scenario.pdf` p.23) — **202 of the 397
     * deployed scenarios whose source parses**, wired 2026-08-30.
     *
     * **OSADA has always done the option-ON behaviour**, because [deployReinforcements] was only
     * ever called for the player whose turn was starting. So the 202 scenarios that author it were
     * already right and the other 195 were not: OG's DEFAULT is that every side's arrivals appear
     * together, at the top of the round.
     *
     * Nothing about the arriving units changes — a formation that appears at the top of the round
     * still cannot act until its own player's turn. What changes is when the player SEES it, which
     * is the whole point of the option: the author chose whether the enemy's build-up is visible
     * before you move or only after.
     *
     * An unreadable source (null) keeps today's per-player behaviour, so the 105 scenarios whose
     * `.xscn` this project cannot read are untouched.
     */
    private fun Game.deployArrivingReinforcements() {
        val map = scenario?.map ?: return
        val current = map.currentPlayer ?: return
        if (scenario?.reinforcementsWhenActive != false) {
            deployReinforcements(map.turn, current.id)
        } else if (current.id == map.getPlayers().firstOrNull()?.id) {
            // Option OFF: everybody's arrivals land at the top of the round, which is the first
            // player's turn. `getReinforcements` filters by owner and `removeReinforcement`
            // consumes each one, so a later pass in the same round finds nothing left to place.
            map.getPlayers().forEach { player -> deployReinforcements(map.turn, player.id) }
        }
    }

    fun setupGameState() {
        console.log("[OSADA] setupGameState")
        savedCampaignPlayer = null
        // The restore path's copy of the scenario-text load: this completion never runs
        // `onScenarioLoadFinished`, and a restored battle still fires events.
        ScenarioTextLocalization.ensure(scenario?.file)
        setupPlayers()
        // Claims a campaign restore's parked reserve/roster now that `campaignPlayer` exists
        // (`GameScenarioLoading.applyPendingCoreUnitRestore`'s own doc comment). This completion
        // path never runs `onScenarioLoadFinished`, so without this call a save's undeployed core
        // units -- the reserve tray -- came back empty on every "Continue", campaign-run resume and
        // mission restart, even though the save itself had them.
        applyPendingCoreUnitRestore()
        humanSides = countHumanSides(scenario?.map?.getPlayers()?.toList() ?: emptyList())
        if (DEBUG_AI_MOVES) humanSides = 2
        setCurrentSide()
        gameStarted = true
        gameEnded = false
        // The restore path's copy of the same call [onScenarioLoadFinished] makes -- this completion
        // never runs that one. It repairs a save written while the autosave was still taken BEFORE
        // the new turn's arrivals were placed, and is a no-op for every save written since, because
        // a wave that has landed is no longer in the pending map. `gameStarted` first: the deploy
        // announces itself through the HUD.
        deployArrivingReinforcements()
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
        // A checkpoint belongs to exactly one operation. Clear it before loading another one;
        // restoreFromString does not pass through here, so restarting keeps its immutable source.
        missionRestartCheckpoint.clear()
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
            UIBuilder.messageDynamic(
                I18n.t("game.error.title"),
                I18n.t("game.error.loading_scenario", mapOf("file" to (scenario?.file ?: "—"))),
            )
            gameEnded = true
            return
        }
        // isNotBlank, not just non-null: a campaign's per-scenario `intro` is "" (not absent) for the
        // 10 imported campaigns whose OG briefing text files use a filename convention cam_to_json
        // didn't recognise. Overwriting with that blanked the briefing popup on every campaign start,
        // even though Scenario's own ctor had already seeded a good description from the scenariolist
        // entry — the very text the scenario-select screen shows. Fall back to it instead.
        intro?.takeIf { it.isNotBlank() }?.let { scenario?.setDescription(it) }
        // A NEW battle gets a fresh gameplay random stream; a restored one keeps the stream position
        // its save recorded, which `GameStatePersistence.restoreFromString` has already put back by
        // the time this runs. Re-seeding here on a restore would re-roll every outcome still ahead
        // of the save (`rules/GameRandomSource`).
        if (!fromRestore) GameRandomSource.start(Date.now().toLong())
        // OG's custom music track for this battle. `ScenarioMusic` plays the licensed files listed
        // by its manifest and stays silent for an absent source or unsupported format.
        ScenarioMusic.play(scenario?.musicTrack)
        // The battle's own translated prose (`<event message>`, `<reinforce message>`). Started
        // here so the bundle is in place long before the first event can fire -- the earliest
        // evaluation happens after the UI's image cache completes, which is far slower than a
        // small JSON fetch. An untranslated scenario asks for nothing.
        ScenarioTextLocalization.ensure(scenario?.file)
        setupPlayers()
        humanSides = countHumanSides(scenario?.map?.getPlayers()?.toList() ?: emptyList())
        setCurrentSide()
        // Turn 1's own wave. `deployArrivingReinforcements` used to be reachable only from
        // [endTurn], so a `<reinforce turn="1">` -- 8 shipped scenarios author one, 10 formations
        // between `ga4_2`, `ga4_7`, `ga4_9` and `rcampfa1` -- was not placed until the end of turn
        // 1 handed the turn over, i.e. a full round after the author asked for it. Safe on a
        // restore too, and deliberately not skipped there: `removeReinforcement` consumes each
        // wave as it lands and the save carries only what is still pending, so this is a no-op for
        // a save that already placed its arrivals and the repair for one written before the
        // autosave was moved after this step.
        deployArrivingReinforcements()
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
        org.osada.multiplayer.client.OsadaMultiplayer
            .onScenarioLoaded()
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
