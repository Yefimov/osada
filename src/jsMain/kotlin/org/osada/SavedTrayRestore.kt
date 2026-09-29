package org.osada

import org.osada.hero.CampaignHeroScope
import org.osada.model.GameMap
import org.osada.model.getPlayers
import org.osada.model.restoreCoreUnitList

/**
 * Each player's deployment tray, from the `coreUnits` its own player block in the save has always
 * carried.
 *
 * Only the campaign player's roster used to come back (from the campaign block, via
 * `pendingCoreUnitRestore`), so every other tray was written to the save and never read: a
 * standalone battle reloaded with OG's undeployed pool -- or anything bought and not yet placed --
 * gone, and so did an AI's. The campaign player is still left to the campaign block, which is
 * richer and would otherwise enrol the same units twice.
 *
 * Runs after the map is rebuilt, because `restoreCoreUnitList` also re-enrols the player's
 * deployed core units from it.
 */
internal fun GameMap.restoreUndeployedTrays(
    rawPlayers: Array<dynamic>,
    campaignData: dynamic?,
) {
    getPlayers().forEach { player ->
        val fromCampaignBlock = campaignData != null && player.id == CampaignHeroScope.CAMPAIGN_PLAYER_ID
        val saved: dynamic = rawPlayers.firstOrNull { (it.id as? Int) == player.id }?.coreUnits
        if (fromCampaignBlock || saved == null || saved == undefined) return@forEach
        restoreCoreUnitList(player, saved.unsafeCast<Array<dynamic>>().toList())
    }
}
