package org.osada

import org.osada.ai.AI
import org.osada.ai.AIScripted
import org.osada.model.*
import org.osada.rules.GameRules
import org.osada.scenario.Campaign
import org.osada.scenario.Scenario
import org.osada.ui.UI
import org.osada.ui.UIBuilder
import org.osada.ui.briefing.CampaignBriefingCatalog
import org.osada.ui.makeVisible
import kotlin.js.JsExport
import kotlin.js.JsName

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

    private var humanSides: Int = -1
    private var campaignPlayer: Player? = null
    private var savedCampaignPlayer: Player? = null
    private var continueCampaignFlag: Boolean = false
    private var removeNonCampaignUnitsFlag: Boolean = false
    private var buildCoreUnitsFlag: Boolean = false
    private var awardPrototype: Boolean = false
    private var nextScenarioData: dynamic = null

    /** Raw optional briefing data and visibility flag for the scenario currently being loaded. */
    private var pendingScenarioBriefing: dynamic = null
    private var pendingScenarioBriefingEnabled: Boolean = true

    companion object {
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
            }
        )
        console.log("[OSADA] Game.init state.restore dispatched")
    }

    fun processTurn() {
        if (!gameStarted || gameEnded) return
        if (nextScenarioData != null && continueCampaignFlag && uiMessageClicked) {
            val intro = nextScenarioData.intro as? String
            val scenarioFile = nextScenarioData.scenario as String
            pendingScenarioBriefing = resolveScenarioBriefing(nextScenarioData, scenarioFile)
            // Consume the pending campaign transition exactly once. Without clearing these,
            // the 1s processTurn interval reloads the same scenario every tick (an infinite
            // loop), which is especially visible when the briefing message is empty and never
            // resets uiMessageClicked back to false.
            continueCampaignFlag = false
            nextScenarioData = null
            console.log("[OSADA] processTurn continueCampaign -> newScenario", scenarioFile)
            newScenario(scenarioFile, intro)
            return
        }
        val current = scenario?.map?.currentPlayer ?: return
        if (current.type == PlayerType.AI_LOCAL || current.type == PlayerType.AI_SCRIPTED) {
            if (!waitUIAnimation && uiMessageClicked) {
                processAIActions()
            }
        }
    }

    fun processAIActions() {
        val handler = scenario?.map?.currentPlayer?.handler ?: return
        val action = handler.getAction()
        if (action == null) {
            UIBuilder.showAIStatus(false)
            waitUIAnimation = true
            endTurn()
            js("setTimeout")(fun() {
                if (!gameEnded) ui?.uiEndTurnInfo()
            }, 2500)
            return
        }
        executeAction(action)
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeAction(action: dynamic) {
        val param = action.param as? Array<dynamic> ?: return
        when (action.type as Int) {
            ActionType.MOVE.value -> {
                val unit = param[0] as org.osada.model.GameUnit
                val cell = param[1] as Cell
                val nm = org.osada.model.Equipment.getEquipment(unit.eqid)?.name ?: ""
                console.log("[OSADA] AI move ${unit.id}($nm) -> ${cell.row},${cell.col}")
                scenario?.map?.setMoveRange(unit)
                waitUIAnimation = true
                ui?.uiUnitMove(unit, cell.row, cell.col)
            }
            ActionType.ATTACK.value -> {
                val attacker = param[0] as org.osada.model.GameUnit
                val defender = param[1] as org.osada.model.GameUnit
                waitUIAnimation = true
                if (GameRules.isInAttackRange(attacker, defender)) {
                    ui?.uiUnitAttack(attacker, defender)
                } else {
                    waitUIAnimation = false
                }
            }
            ActionType.RESUPPLY.value -> {
                val unit = param[0] as org.osada.model.GameUnit
                scenario?.map?.resupplyUnit(unit)
            }
            ActionType.REINFORCE.value -> {
                val unit = param[0] as org.osada.model.GameUnit
                scenario?.map?.reinforceUnit(unit, false)
            }
            ActionType.MOUNT.value -> scenario?.map?.mountUnit(param[0] as org.osada.model.GameUnit)
            ActionType.UMOUNT.value -> scenario?.map?.unmountUnit(param[0] as org.osada.model.GameUnit)
            ActionType.SELECT.value -> {
                val unit = param[0] as org.osada.model.GameUnit
                ui?.uiSetUnitOnViewPort(unit)
                ui?.uiUnitSelect(unit)
            }
            ActionType.MESSAGE.value -> {
                val message = param[0] as String
                val cell = param[1] as Cell
                waitUIAnimation = true
                ui?.showGameToolTip(message, cell.row, cell.col)
                ui?.uiSetCellOnViewPort(cell)
            }
            ActionType.VIEWPORT.value -> {
                ui?.uiSetCellOnViewPort(param[0] as Cell)
            }
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
            org.osada.ui.WeatherModel.advance(scenario)
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

    private fun setupPlayers() {
        val players = scenario?.map?.getPlayers() ?: return
        players.forEach { player ->
            if (campaign != null) {
                // The campaign human core is player id 0 (convention shared by the original PM
                // adlerkorps campaigns and every OG import). Keying on id rather than country is
                // robust to campaigns whose human nation CHANGES between scenarios (e.g. the OG
                // import "A Long Journey to Freedom" runs as country 84 then 38) — country-matching
                // would flag NO player as human in such scenarios and let the AI play both sides.
                if (player.id != 0) {
                    player.type = PlayerType.AI_LOCAL
                    player.handler = createAIHandler(player)
                } else {
                    campaignPlayer = player
                    if (savedCampaignPlayer == null) {
                        savedCampaignPlayer = Player().apply { copy(player) }
                    } else {
                        player.copy(savedCampaignPlayer!!, true)
                    }
                    // After any core carry-over (copy overwrites country with the saved core's),
                    // sync campaign.country to the actual human nation so unit-purchase filtering
                    // (EquipmentWindowController) and the dossier image follow the right country.
                    campaign!!.country = player.country
                }
            } else {
                when {
                    uiSettings.isAI[player.id] == 1 || player.type == PlayerType.AI_LOCAL -> {
                        player.type = PlayerType.AI_LOCAL
                        player.handler = createAIHandler(player)
                    }
                    uiSettings.isAI[player.id] == 2 || player.type == PlayerType.AI_SCRIPTED -> {
                        player.type = PlayerType.AI_SCRIPTED
                        player.handler = createScriptedAIHandler(player)
                    }
                }
            }
        }
    }

    private fun createAIHandler(player: Player): dynamic {
        return AI(player, scenario!!.map)
    }

    private fun createScriptedAIHandler(player: Player): dynamic {
        return AIScripted(player, scenario!!.map)
    }

    private fun countHumanSides(players: List<Player>): Int {
        val humanSides = players
            .filter { it.type == PlayerType.HUMAN_LOCAL || it.type == PlayerType.AI_SCRIPTED }
            .map { it.side }
            .distinct()
        return when (humanSides.size) {
            0 -> 0
            1 -> humanSides[0]
            else -> 2
        }
    }

    fun newScenario(file: String, intro: String?) {
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

    fun onScenarioLoadFinished(intro: String?, fromRestore: Boolean) {
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
            console.log("[OSADA] onScenarioLoadFinished campaign branch")
            if (buildCoreUnitsFlag) {
                campaignPlayer?.prestige = campaign!!.startprestige
                campaignPlayer?.initDossier()
                scenario!!.map.buildCoreUnitList(campaignPlayer!!)
                // Only campaigns that OG starts with a buy/deploy phase (an undeployed reserve pool
                // in scenario 1 — forward, rcampdfr) hand the core back to the player UNDEPLOYED to
                // place; every other imported campaign has its first-scenario units PRE-PLACED, so we
                // must NOT lift them into the tray. (Scenarios 2+ always get the tray via carry-over.)
                if (campaign!!.deployPhase) {
                    campaignPlayer?.let { scenario!!.map.undeployCoreUnits(it) }
                }
            }
            if (removeNonCampaignUnitsFlag) {
                scenario!!.map.removeNonCampaignUnits(campaignPlayer!!)
            }
            if (awardPrototype) {
                val prototype = scenario!!.getRandomPrototype(campaignPlayer!!.country + 1)
                if (prototype > 0) {
                    campaignPlayer?.acquireUnit(prototype, 0)
                    awardPrototype = false
                    UIBuilder.showPrototypeAwardMessage(prototype)
                }
            }
            state?.saveCampaign()
        } else {
            console.log("[OSADA] onScenarioLoadFinished scenario branch")
            scenario!!.showStatistics()
            if (!fromRestore && scenario!!.file != "tutorial.xml") {
                scenario!!.map.getPlayers().forEach { player ->
                    player.prestige = scenario!!.getBalancedPrestige(player.side)
                }
            }
        }
        state?.save()

        val createdNow = ui == null
        if (createdNow) {
            ui = UI(this)
            val uiInstance = ui
            console.log("Game: created UI instance", uiInstance)
            js("window.game.ui = uiInstance")
            js("window.ui = uiInstance")
            console.log("[OSADA] onScenarioLoadFinished hiding start menu")
            UIBuilder.hideStartMenu()
        }
        console.log("[OSADA] onScenarioLoadFinished calling setNewScenario")
        ui?.setNewScenario()
    }

    fun newCampaign(id: Int, difficulty: Int) {
        console.log("[OSADA] newCampaign", id, difficulty)
        pendingScenarioBriefing = null
        pendingScenarioBriefingEnabled = true
        campaign = Campaign(id, difficulty) { onCampaignLoadFinished() }
    }

    private fun onCampaignLoadFinished() {
        console.log("[OSADA] onCampaignLoadFinished")
        savedCampaignPlayer = null
        removeNonCampaignUnitsFlag = false
        buildCoreUnitsFlag = true
        val current = campaign?.getCurrentScenario()
        if (current != null) {
            val scenarioFile = current.scenario as String
            val intro = current.intro as? String
            pendingScenarioBriefing = resolveScenarioBriefing(current, scenarioFile)
            newScenario(scenarioFile, intro)
        }
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

    private fun extractScenarioBriefing(data: dynamic): dynamic {
        if (data == null || data == undefined) return null
        val briefing = data.briefing
        if (briefing != null && briefing != undefined) return briefing
        val dialogue = data.dialogue
        if (dialogue != null && dialogue != undefined) return dialogue
        val dialogues = data.dialogues
        if (dialogues != null && dialogues != undefined) return dialogues
        return null
    }

    private fun resolveScenarioBriefing(data: dynamic, scenarioFile: String): dynamic {
        val embedded = extractScenarioBriefing(data)
        val resolved = if (embedded != null && embedded != undefined) {
            embedded
        } else {
            CampaignBriefingCatalog.forScenario(scenarioFile)
        }
        console.log(
            "[OSADA] campaign briefing resolved",
            scenarioFile,
            resolved != null && resolved != undefined
        )
        return resolved
    }

    fun continueCampaign(outcome: String, reason: EndGameType = EndGameType.MOVE_CAPTURE) {
        console.log("[OSADA] continueCampaign", outcome)
        val player = campaignPlayer ?: return
        player.prestige += campaign!!.getOutcomePrestige(outcome)
        player.addOutcomeToDossier(outcome, scenario!!.name)
        OSGlue.reportScore(player.score)
        player.setPlayerToHQ()
        savedCampaignPlayer = Player().apply { copy(player) }
        removeNonCampaignUnitsFlag = true
        buildCoreUnitsFlag = false
        val text = campaign!!.getOutcomeText(outcome)
        nextScenarioData = campaign!!.loadNextScenario(outcome)
        continueCampaignFlag = true
        if (nextScenarioData == null) {
            val finalText = if (outcome == "lose") endGameLossText[reason] + text else text
            UIBuilder.showCampaignEnd(outcome, finalText) { ui?.mainMenuButton("options") }
            gameEnded = true
            gameStarted = false
            if (outcome != "lose") {
                OSGlue.reportAchievement(campaign!!.file)
            }
        } else {
            UIBuilder.message(outcomeNames[outcome] ?: outcome, text, narrative = true)
            if (outcome == "briliant") awardPrototype = true
        }
    }

    fun getCampaignPlayer(): Player? = campaignPlayer

    fun setCurrentSide() {
        spotSide = if (humanSides == 2) scenario?.map?.currentPlayer?.side ?: 0 else humanSides
        console.log("[OSADA] setCurrentSide humanSides=$humanSides spotSide=$spotSide currentPlayer.side=${scenario?.map?.currentPlayer?.side}")
    }

    /**
     * Called by the UI when an animation (move or attack) finishes.
     * Resumes the AI turn loop.
     */
    fun uiAnimationFinished() {
        waitUIAnimation = false
        processTurn()
    }

    /**
     * Handles the end-of-scenario victory condition triggered by capturing
     * all objectives during a move. Called from the move-animation callback.
     */
    fun handleMoveVictory(winningSide: Int) {
        if (gameEnded) return
        val outcome = scenario?.checkVictory() ?: return
        if (campaign != null) {
            val isHumanWin = campaignPlayer?.side == winningSide
            if (isHumanWin) {
                continueCampaign(outcome, EndGameType.MOVE_CAPTURE)
            } else {
                continueCampaign("lose", EndGameType.MOVE_CAPTURE)
            }
        } else {
            gameEnded = true
            val currentType = scenario?.map?.currentPlayer?.type
            val title = if (currentType == PlayerType.HUMAN_LOCAL) outcomeNames[outcome] ?: outcome else "DEFEAT"
            val message = if (currentType == PlayerType.HUMAN_LOCAL) {
                "You have won this scenario!"
            } else {
                "You have lost! Your enemy wins by capturing all victory hexes."
            }
            UIBuilder.message(title, "<br/><br/><br/><br/>$message") {
                ui?.mainMenuButton("options")
            }
        }
    }

    fun deployReinforcements(turn: Int, playerId: Int) {
        var deployed = false
        val reinforcements = scenario?.getReinforcements(turn, playerId) ?: return
        val side = scenario!!.map.getPlayer(playerId).side
        reinforcements.forEach { reinf ->
            val pos = scenario!!.map.deployReinforcement(reinf.unit, reinf.row, reinf.col)
            if (pos != null) {
                deployed = true
                CombatLog.addReinforcement(reinf.unit)
                scenario!!.removeReinforcement(reinf.turn, reinf.id)
                val hex = scenario!!.map.map!![pos.row][pos.col]
                if (hex.isSpotted(spotSide)) {
                    val isFriendly = spotSide == side
                    ui?.showAlert(pos.row, pos.col, "Reinforced!", isFriendly)
                }
            }
        }
        if (deployed) {
            // Reinforcements can introduce unit/transport eqids whose sprites were not part of the
            // initial cacheImages() pass. Re-cache (idempotent — already-loaded images are skipped)
            // and repaint so the newly deployed units are drawn instead of rendering invisible.
            ui?.render?.cacheImages { ui?.render?.render() }
            ui?.handleReinforcementDeployment()
        }
    }

    fun cleanup() {
        console.log("[OSADA] cleanup")
        org.osada.ui.WeatherRenderer.stop()
        org.osada.ui.WeatherModel.stop()
        state?.clear()
        scenario?.map?.cleanup()
        scenario = null
    }

    val loop = js("setInterval")(fun() { processTurn() }, 1000)
}