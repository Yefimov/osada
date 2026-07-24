package org.osada

import org.osada.ai.AI
import org.osada.ai.AIScripted
import org.osada.model.Player
import org.osada.model.getPlayers

internal fun Game.setupPlayers() {
    val players = scenario?.map?.getPlayers() ?: return
    players.forEach { player -> assignPlayerType(player) }
}

private fun Game.assignPlayerType(player: Player) {
    if (campaign != null) {
        assignCampaignPlayerType(player)
    } else {
        assignScenarioPlayerType(player)
    }
}

private fun Game.assignCampaignPlayerType(player: Player) {
    // The campaign human core is player id 0 (convention shared by the original PM
    // adlerkorps campaigns and every OG import). Keying on id rather than country is
    // robust to campaigns whose human nation CHANGES between scenarios (e.g. the OG
    // import "A Long Journey to Freedom" runs as country 84 then 38) — country-matching
    // would flag NO player as human in such scenarios and let the AI play both sides.
    if (player.id != 0) {
        player.type = PlayerType.AI_LOCAL
        player.handler = createAIHandler(player)
        return
    }
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

private fun Game.assignScenarioPlayerType(player: Player) {
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

private fun Game.createAIHandler(player: Player): dynamic = AI(player, scenario!!.map)

private fun Game.createScriptedAIHandler(player: Player): dynamic = AIScripted(player, scenario!!.map)

internal fun Game.countHumanSides(players: List<Player>): Int {
    val humanSides =
        players
            .filter { it.type == PlayerType.HUMAN_LOCAL || it.type == PlayerType.AI_SCRIPTED }
            .map { it.side }
            .distinct()
    return when (humanSides.size) {
        0 -> 0
        1 -> humanSides[0]
        else -> 2
    }
}
