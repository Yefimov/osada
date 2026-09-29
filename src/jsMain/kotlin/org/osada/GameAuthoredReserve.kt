package org.osada

import org.osada.model.getPlayers
import org.osada.model.synchronizeStalinRegime
import org.osada.model.usesStalinRegime

/**
 * Hands OG's undeployed pool (`Scenario.authoredReserve`) to each owner's deployment tray -- the
 * same undeployed list a purchase lands in, which the player places by hand and the AI deploys in
 * `purchaseAndDeployPhase`. A NEW battle only: a restored one carries its tray in the save.
 *
 * Runs after `setupPlayers`, because that is where a campaign's carried core is copied over the
 * scenario's player; adding earlier would be overwritten.
 *
 * In a campaign battle after the first, the campaign player's pool is the battle's own force,
 * lent for this battle and never carried on -- the rule `keepScenarioUnitsAsAuxiliary` applies to
 * the same force when it stands on the map -- unless the author ticked Make Core on it. In a
 * campaign's first battle (and after *"Restart (lose) Core units"*) it joins the core, as the
 * deploy-hex sweep's units do. Outside a campaign every pool is simply that player's tray.
 */
internal fun Game.enlistAuthoredReserve() {
    val loaded = scenario ?: return
    val pool = loaded.authoredReserve.toList()
    loaded.authoredReserve.clear()
    val players = loaded.map.getPlayers()
    pool.forEach { unit ->
        val player = players.firstOrNull { it.id == unit.owner } ?: return@forEach
        val lent = campaign != null && player === campaignPlayer && auxiliaryScenarioUnitsFlag && !unit.isCore
        if (lent) unit.isTemporaryBorrowed = true
        unit.player = player
        unit.isDeployed = false
        unit.synchronizeStalinRegime(player.usesStalinRegime())
        player.addCoreUnit(unit)
    }
}
