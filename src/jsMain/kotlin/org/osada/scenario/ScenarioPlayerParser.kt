package org.osada.scenario

import org.osada.GameHolder
import org.osada.difficultyModifiers
import org.osada.model.Equipment
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addPlayersEquipment
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * [ScenarioLoader]'s `<player>` element parser and the equipment/reinforcement/hex parsing it
 * kicks off once player equipment is loaded. Split out purely to keep [ScenarioLoader] within
 * the project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object ScenarioPlayerParser {
    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        val playerElements = doc.getElementsByTagName("player")
        val minTurnPrestige = resolveMinTurnPrestige()
        val players = mutableListOf<Player>()
        for (i in 0 until playerElements.length) {
            val el = playerElements.item(i) ?: continue
            players.add(parsePlayerElement(el, minTurnPrestige))
        }

        Equipment.addPlayersEquipment(players) {
            players.forEach { scenario.map.addPlayer(it) }
            ScenarioReinforcementParser.parse(scenario, doc)
            ScenarioHexParser.parse(scenario, doc)
            scenario.isLoaded = true
            scenario.onLoadFinished()
        }
    }

    private fun resolveMinTurnPrestige(): Int {
        val campaign = GameHolder.instance?.campaign ?: return 0
        val turnPrestigeRatio = difficultyModifiers[campaign.difficulty]?.turnPrestige ?: 0.0
        return kotlin.math.round(campaign.startprestige * turnPrestigeRatio).toInt()
    }

    private fun parsePlayerElement(
        el: Element,
        minTurnPrestige: Int,
    ): Player {
        val player = Player()
        player.id = el.getAttribute("id")?.toIntOrNull() ?: 0
        player.side = el.getAttribute("side")?.toIntOrNull() ?: 0
        player.country = el.getAttribute("country")?.toIntOrNull() ?: 0
        player.airTransports = el.getAttribute("airtrans")?.toIntOrNull() ?: 0
        player.navalTransports = el.getAttribute("navaltrans")?.toIntOrNull() ?: 0
        player.prestigePerTurn = el
            .getAttribute("turnprestige")
            ?.split(", ")
            ?.map { value ->
                val v = value.toIntOrNull() ?: 0
                if (v < minTurnPrestige) minTurnPrestige else v
            }?.toMutableList() ?: mutableListOf()
        player.prestige = player.prestigePerTurn.getOrElse(0) { 0 }
        player.supportCountries =
            el
                .getAttribute("support")
                ?.split(", ")
                ?.map {
                    it.toIntOrNull() ?: 0
                }?.filter { it > 0 }
                ?.toMutableList()
                ?: mutableListOf()
        return player
    }
}
