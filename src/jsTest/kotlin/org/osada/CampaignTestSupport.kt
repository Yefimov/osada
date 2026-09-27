package org.osada

import org.osada.model.Player
import org.osada.scenario.Campaign

/**
 * Puts the test inside a campaign run, which the hero system requires since 2026-09-27
 * (`hero/CampaignHeroScope`): outside a campaign, or for any player but the campaign player, no
 * formation is enrolled and every unit takes the legacy leader path.
 *
 * Reuses the current [GameHolder] game when a test has already built one (its scenario and players
 * stay), otherwise creates a bare one. The campaign is an empty record -- id -1 matches no list
 * entry, so it loads nothing and calls back at once. [player], when given, becomes the campaign
 * player; the scope otherwise assumes player id 0, the campaign convention.
 */
internal fun enterCampaignForTests(player: Player? = null): Game {
    js("if (typeof window.campaignlist === 'undefined') { window.campaignlist = []; }")
    val existing = GameHolder.instance
    createdForCampaignTest = existing == null
    val game = existing ?: Game()
    GameHolder.instance = game
    game.campaign = Campaign(-1, 0) {}
    if (player != null) game.campaignPlayer = player
    return game
}

/** Whether [enterCampaignForTests] had to create the game, so [leaveCampaignForTests] removes it. */
private var createdForCampaignTest = false

/**
 * Leaves the campaign run [enterCampaignForTests] started. A game it created is removed again, so
 * no later test class inherits a campaign; one a test built itself is kept, minus the campaign.
 */
internal fun leaveCampaignForTests() {
    GameHolder.instance?.let {
        it.campaign = null
        it.campaignPlayer = null
    }
    if (createdForCampaignTest) GameHolder.instance = null
    createdForCampaignTest = false
}
