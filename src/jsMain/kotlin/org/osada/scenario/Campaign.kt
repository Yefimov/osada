package org.osada.scenario

import org.osada.difficultyModifiers
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
    private var currentScenarioIndex: Int = 0

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
                isLoaded = true
                onLoad()
            } else {
                onLoad()
            }
        }
        request.open("GET", "resources/campaigns/data/$file", true)
        request.send(null)
    }

    fun setScenarioById(id: Int) {
        if (id < scenarios.size) currentScenarioIndex = id
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

    fun loadNextScenario(outcome: String): dynamic? {
        val next = scenarios[currentScenarioIndex].outcome[outcome].goto as Int
        if (next < scenarios.size) {
            currentScenarioIndex = next
            return scenarios[next]
        }
        return null
    }

    fun getOutcomePrestige(outcome: String): Int = scenarios[currentScenarioIndex].outcome[outcome].prestige as Int

    fun getOutcomeText(outcome: String): String =
        scenarios[currentScenarioIndex].outcome[outcome].text as? String ?: "Continue to the next phase."

    fun getCampaignFlow(): String {
        val sb = StringBuilder()
        for (i in scenarios.indices) {
            val scenarioName = getScenarioNameFromId(i)
            val lose = getScenarioNameFromId(scenarios[i].outcome.lose.goto as Int)
            val tactical = getScenarioNameFromId(scenarios[i].outcome.tactical.goto as Int)
            val victory = getScenarioNameFromId(scenarios[i].outcome.victory.goto as Int)
            val briliant = getScenarioNameFromId(scenarios[i].outcome.briliant.goto as Int)
            if (tactical == victory && victory == briliant) {
                sb.append(
                    "- <b>$scenarioName</b><br/>&nbsp;&nbsp;&nbsp;&nbsp;Lose: $lose<br/>" +
                        "&nbsp;&nbsp;&nbsp;&nbsp;Victory: $victory<br/><br/>",
                )
            } else {
                sb.append(
                    "- <b>$scenarioName</b><br/>&nbsp;&nbsp;&nbsp;&nbsp;Lose: $lose<br/>" +
                        "&nbsp;&nbsp;&nbsp;&nbsp;Tactical: $tactical<br/>" +
                        "&nbsp;&nbsp;&nbsp;&nbsp;Victory: $victory<br/>" +
                        "&nbsp;&nbsp;&nbsp;&nbsp;Brilliant: $briliant<br/><br/>",
                )
            }
        }
        return sb.toString()
    }

    fun getScenarioNameFromId(id: Int): String =
        when (id) {
            CAMPAIGN_DEFEAT_SCENARIO_ID -> "Defeat (End Campaign)"
            CAMPAIGN_VICTORY_SCENARIO_ID -> "Victory (End Campaign)"
            else ->
                ScenarioLoader.getScenarioDataByFileName(scenarios[id].scenario as String)?.get(1) as? String
                    ?: "Unknown"
        }

    fun getCampaignData(): Array<dynamic> = scenarios

    companion object {
        // Sentinel scenario ids marking a campaign-ending branch rather than a real scenario.
        private const val CAMPAIGN_VICTORY_SCENARIO_ID = 254
        private const val CAMPAIGN_DEFEAT_SCENARIO_ID = 255

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
