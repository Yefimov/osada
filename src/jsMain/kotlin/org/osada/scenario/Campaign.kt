package org.osada.scenario

import org.osada.difficultyModifiers
import org.osada.ui.CampaignContentLocalization
import org.w3c.xhr.XMLHttpRequest

@JsExport
@JsName("Campaign")
class Campaign(
    val id: Int,
    val difficulty: Int,
    val onLoad: () -> Unit,
) {
    private val campaignData: dynamic = findCampaignById(id)
    var startprestige: Int = 0
    var name: String = ""
    var country: Int = 0
    var file: String = ""

    // true only for imported campaigns whose first scenario gives the human an undeployed reserve
    // pool (forward, rcampdfr): those start with a buy/deploy phase. Others have units pre-placed.
    var deployPhase: Boolean = false
    var isLoaded: Boolean = false

    private var scenarios: Array<dynamic> = emptyArray()

    /** Index of the scenario currently being played. Read by campaign-narrative extensions. */
    var currentScenarioIndex: Int = 0
        private set

    init {
        if (campaignData != null) {
            val data = campaignData
            val basePrestige = data.prestige as Int
            startprestige = computeStartPrestige(basePrestige, difficulty)
            name = data.title as String
            country = data.flag as Int
            deployPhase = data.deployphase as? Boolean ?: false
            file = data.file as String
            loadCampaignData(file)
        } else {
            onLoad()
        }
    }

    private fun loadCampaignData(file: String) {
        val request = XMLHttpRequest()
        request.onload = {
            if (request.readyState == 4.toShort() &&
                (request.status == 200.toShort() || request.status == 0.toShort())
            ) {
                scenarios = JSON.parse(request.responseText)
                // Logged, not thrown: the run stays playable up to a broken transition, which then
                // refuses to commit (`Game.continueCampaign`).
                CampaignTransitions.validate(scenarios).forEach {
                    console.error("[OSADA] campaign '$file' has an invalid transition: ${it.describe()}")
                }
                isLoaded = true
                CampaignContentLocalization.ensure(file, onLoad)
            } else {
                onLoad()
            }
        }
        request.open("GET", "resources/campaigns/data/$file", true)
        request.send(null)
    }

    /** A save's scenario index; one this campaign does not have (negative, or past the list) is ignored. */
    fun setScenarioById(id: Int) {
        if (id in scenarios.indices) currentScenarioIndex = id
    }

    fun setScenarioByName(name: String): Boolean {
        val fileName = name.substringAfterLast("/")
        for (i in scenarios.indices) {
            if (scenarios[i].scenario == fileName) {
                currentScenarioIndex = i
                return true
            }
        }
        return false
    }

    fun getCurrentScenario(): dynamic = scenarios.getOrNull(currentScenarioIndex)

    /** Tests only: a campaign record list without the XHR load, standing at [index]. */
    internal fun useScenariosForTests(
        records: Array<dynamic>,
        index: Int,
    ) {
        scenarios = records
        currentScenarioIndex = index
    }

    /**
     * [routeOverride], when present, replaces the winning outcome-branch `goto` -- it is how an
     * authored player CHOICE (as opposed to a victory grade) redirects the campaign. See
     * `CampaignEffect.Route` and `CampaignNarrative.takeCommittedRoute`. Resolved by
     * [CampaignTransitions]; null when the campaign does not continue, and then nothing moves.
     */
    fun loadNextScenario(
        outcome: String,
        routeOverride: Int? = null,
    ): dynamic? = advance(CampaignTransitions.resolve(scenarios, currentScenarioIndex, outcome, routeOverride))

    /** Moves to the scenario [transition] continues with and returns its record; null for any other transition. */
    internal fun advance(transition: CampaignTransition): dynamic? {
        val next = transition as? CampaignTransition.NextScenario ?: return null
        currentScenarioIndex = next.index
        return scenarios[next.index]
    }

    fun getOutcomePrestige(outcome: String): Int = scenarios[currentScenarioIndex].outcome[outcome].prestige as Int

    fun getOutcomeText(outcome: String): String {
        val current = scenarios[currentScenarioIndex]
        val authored = current.outcome[outcome].text as? String ?: "Continue to the next phase."
        return CampaignContentLocalization.outcome(file, current.scenario as? String, outcome, authored)
    }

    fun getScenarioNameFromId(id: Int): String =
        when (id) {
            CampaignTransitions.DEFEAT_END -> "Defeat (End Campaign)"
            CampaignTransitions.VICTORY_END -> "Victory (End Campaign)"
            else ->
                (scenarios.getOrNull(id)?.scenario as? String)
                    ?.let { ScenarioLoader.getScenarioDataByFileName(it)?.get(1) as? String }
                    ?: "Unknown"
        }

    fun getCampaignData(): Array<dynamic> = scenarios

    companion object {
        fun findCampaignByFile(file: String): Int {
            val list = js("campaignlist").unsafeCast<Array<dynamic>>()
            for (i in list.indices) {
                if (list[i].file == file) return i
            }
            return -1
        }

        fun findCampaignById(id: Int): dynamic? {
            val list = js("campaignlist").unsafeCast<Array<dynamic>>()
            return list.getOrNull(id)
        }

        /** Difficulty-adjusted starting prestige. Single source of truth: used when a campaign
         *  actually starts AND by the campaign-select screen display (0b). */
        fun computeStartPrestige(
            basePrestige: Int,
            difficulty: Int,
        ): Int = basePrestige + (basePrestige * (difficultyModifiers[difficulty]?.startPrestige ?: 0.0)).toInt()
    }
}
