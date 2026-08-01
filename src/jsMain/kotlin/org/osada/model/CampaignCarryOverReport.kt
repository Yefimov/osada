@file:Suppress("MaxLineLength", "LongMethod", "ComplexMethod")

package org.osada.model

import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign

/**
 * Summary of one scenario-end persistent-army reconciliation.
 *
 * [candidates] counts **the player's own** formations considered, not every unit on the map. It
 * used to be the raw candidate list, which included the enemy's units, so the headline read as a
 * survival rate while comparing your army against your army plus theirs: N_Kiel logged
 * `17/26 formations` with `destroyed=0`, and the nine "missing" formations were mostly Germans. A
 * log line whose only job is to tell you whether you lost anything must not be able to invent a
 * loss (reported 2026-08-01).
 *
 * [reMintedFormationIds] is likewise not a loss. Colliding ids used to cause a unit to be dropped;
 * since that was fixed the collision is repaired by minting a fresh id and **keeping both units**,
 * so the number is a note about save hygiene, not casualties.
 */
internal data class CampaignCarryOverReport(
    val candidates: Int,
    val survivors: Int,
    val destroyed: Int,
    val temporary: Int,
    val noDossier: Int,
    val reMintedFormationIds: Int,
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
    // Every other figure in the report is already scoped to the player; `candidates` was not, and
    // it is the one the headline divides by. See [CampaignCarryOverReport].
    val owned = candidates.count { it.owner == player.id }
    val destroyed = candidates.count { it.owner == player.id && it.destroyed }
    val temporary = candidates.count { it.owner == player.id && it.isTemporaryBorrowed }
    val noDossier = candidates.count { it.owner == player.id && it.nodossier }
    val usedIds =
        buildSet {
            candidates.mapNotNullTo(this) { unit -> unit.formationId?.takeIf { id -> id.isNotEmpty() } }
            HeroCampaign.roster().allFormations().mapTo(this) { formation -> formation.id.value }
        }.toMutableSet()
    val survivorsByFormation = linkedMapOf<String, GameUnit>()
    var reMintedFormationIds = 0

    candidates.filter { it.isCampaignPersistentFor(player) }.forEach { unit ->
        var formationId = FormationIdentity.ensure(unit, usedIds).value
        usedIds += formationId

        val previous = survivorsByFormation[formationId]
        if (previous !== unit && previous != null) {
            reMintedFormationIds++
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
        candidates = owned,
        survivors = survivors.size,
        destroyed = destroyed,
        temporary = temporary,
        noDossier = noDossier,
        reMintedFormationIds = reMintedFormationIds,
    )
}
