@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.model

import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign

/** Summary of one scenario-end persistent-army reconciliation. */
internal data class CampaignCarryOverReport(
    val candidates: Int,
    val survivors: Int,
    val destroyed: Int,
    val temporary: Int,
    val noDossier: Int,
    val duplicateFormationIds: Int,
)

/**
 * The campaign-persistence policy is deliberately independent of the legacy [GameUnit.isCore]
 * flag. A player-controlled formation persists unless it was destroyed or explicitly opted out.
 */
internal fun GameUnit.isCampaignPersistentFor(player: Player): Boolean =
    owner == player.id && !destroyed && !nodossier && !isTemporaryBorrowed

/**
 * Rebuilds the campaign reserve roster from every surviving player formation: deployed ground and
 * air units plus units already in the reserve/deployment tray. Stable formation ids are retained;
 * a unit that shares an id with one already collected is **re-minted a fresh id and kept**.
 *
 * It used to be dropped instead ("collapsed deterministically instead of spawning two copies next
 * scenario"), which quietly deleted real units: `Player.addCoreUnit` seeded id minting from the
 * core roster alone, so purchases collided with pre-placed scenario units and N_Kiel carried 14 of
 * 26 formations forward (`duplicateIds=12`) — the player's bought artillery among them. The minting
 * bug is fixed at source in [org.osada.model.Player.addCoreUnit]; re-minting here is what repairs a
 * save that already holds colliding ids, and is the safer failure mode either way. Two objects that
 * reach this point are two distinct units — identical references are already removed by `distinct()`
 * and by the `previous === unit` check — so keeping both can at worst preserve a unit the old code
 * would have destroyed.
 */
internal fun GameMap.collectPersistentCampaignUnits(player: Player): CampaignCarryOverReport {
    ensureFormationIds(player)

    val candidates = (player.getCoreUnitList() + getUnits().toList()).distinct()
    val destroyed = candidates.count { it.owner == player.id && it.destroyed }
    val temporary = candidates.count { it.owner == player.id && it.isTemporaryBorrowed }
    val noDossier = candidates.count { it.owner == player.id && it.nodossier }
    val usedIds =
        buildSet {
            candidates.mapNotNullTo(this) { unit -> unit.formationId?.takeIf { id -> id.isNotEmpty() } }
            HeroCampaign.roster().allFormations().mapTo(this) { formation -> formation.id.value }
        }.toMutableSet()
    val survivorsByFormation = linkedMapOf<String, GameUnit>()
    var duplicateFormationIds = 0

    candidates.filter { it.isCampaignPersistentFor(player) }.forEach { unit ->
        var formationId = FormationIdentity.ensure(unit, usedIds).value
        usedIds += formationId

        val previous = survivorsByFormation[formationId]
        if (previous !== unit && previous != null) {
            duplicateFormationIds++
            // Re-mint past every id already spoken for, so the two formations separate instead of
            // one of them being dropped. HeroCampaign.synchronizeFormation below then registers the
            // new id, and any commander bound to the OLD id stays with `previous` — the unit that
            // held it first, which is also the one the roster already knows.
            formationId = FormationIdentity.nextFor(unit.owner, usedIds).value
            unit.formationId = formationId
            usedIds += formationId
        }
        unit.isCore = true
        unit.player = player
        survivorsByFormation[formationId] = unit
    }

    val survivors = survivorsByFormation.values.toList()
    survivors.forEach { HeroCampaign.synchronizeFormation(it) }
    player.setCoreUnitList(survivors)

    return CampaignCarryOverReport(
        candidates = candidates.size,
        survivors = survivors.size,
        destroyed = destroyed,
        temporary = temporary,
        noDossier = noDossier,
        duplicateFormationIds = duplicateFormationIds,
    )
}
