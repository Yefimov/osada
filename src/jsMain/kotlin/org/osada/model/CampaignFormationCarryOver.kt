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
 * a duplicate id is collapsed deterministically instead of spawning two copies next scenario.
 */
internal fun GameMap.collectPersistentCampaignUnits(player: Player): CampaignCarryOverReport {
    ensureFormationIds(player)

    val candidates = (player.getCoreUnitList() + getUnits().toList()).distinct()
    val destroyed = candidates.count { it.owner == player.id && it.destroyed }
    val temporary = candidates.count { it.owner == player.id && it.isTemporaryBorrowed }
    val noDossier = candidates.count { it.owner == player.id && it.nodossier }
    val usedIds =
        candidates
            .mapNotNull { unit -> unit.formationId?.takeIf { id -> id.isNotEmpty() } }
            .toMutableSet()
    val survivorsByFormation = linkedMapOf<String, GameUnit>()
    var duplicateFormationIds = 0

    candidates.filter { it.isCampaignPersistentFor(player) }.forEach { unit ->
        val formationId = FormationIdentity.ensure(unit, usedIds).value
        usedIds += formationId
        unit.isCore = true
        unit.player = player

        val previous = survivorsByFormation[formationId]
        if (previous === unit) return@forEach
        when {
            previous == null -> survivorsByFormation[formationId] = unit
            unit.isDeployed && !previous.isDeployed -> {
                duplicateFormationIds++
                survivorsByFormation[formationId] = unit
            }
            else -> duplicateFormationIds++
        }
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
