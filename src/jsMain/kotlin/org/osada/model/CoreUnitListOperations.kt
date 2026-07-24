package org.osada.model

import org.osada.hero.FormationIdentity
import org.osada.rules.GameRules
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/**
 * Campaign core-unit list management: build, undeploy, restore (save-game load) and prune.
 * Split from [UnitOperations] (SRP / function-count limits).
 */
internal class CoreUnitListOperations(
    private val gameMap: GameMap,
) {
    companion object {
        private const val FULL_STRENGTH = 10
    }

    fun buildCoreUnitList(player: Player) {
        gameMap.units
            .filter {
                it.player?.id == player.id && it.getHex()?.isDeployment == player.id
            }.forEach { player.addCoreUnit(it) }
    }

    /**
     * Gives every one of [player]'s on-map units a formation id, so the WHOLE campaign force is
     * hero-eligible (§9.1) — not only the units deployed onto deployment hexes. Without this a
     * pre-placed campaign (units not sitting on deploy hexes at scenario start) never enters the
     * hero system: those units fall back to the legacy integer leader, which has no dossier.
     *
     * Idempotent: a unit restored from a save keeps the id it already had; only missing ids are
     * minted, seeded past every id already present so two units never collide.
     */
    fun ensureFormationIds(player: Player) {
        val existing = gameMap.units.mapNotNull { it.formationId?.takeIf { id -> id.isNotEmpty() } }.toMutableSet()
        gameMap.units
            .filter { it.player?.id == player.id }
            .forEach { unit -> existing.add(FormationIdentity.ensure(unit, existing).value) }
    }

    /**
     * Lift the player's core units OFF the map back into the (undeployed) tray, so the player
     * deploys them by hand — the Open General campaign start behaviour. On the FIRST campaign
     * scenario [buildCoreUnitList] makes on-deploy-hex units core but leaves them DEPLOYED, unlike
     * later scenarios where the core arrives undeployed (setPlayerToHQ at the previous scenario's
     * end + restoreCoreUnitList). This makes the first scenario consistent: same tray + buy phase.
     * Safe when there are no core units (e.g. no deploy hexes) — it simply does nothing.
     */
    fun undeployCoreUnits(player: Player) {
        player.getCoreUnitList().toList().forEach { unit ->
            val pos = unit.getPos()
            if (pos != null) {
                GameRules.setZOCRange(gameMap, unit, false)
                GameRules.setSpotRange(gameMap, unit, false)
                gameMap.map
                    ?.getOrNull(pos.row)
                    ?.getOrNull(pos.col)
                    ?.delUnit(unit)
            }
            unit.isDeployed = false
            gameMap.units.remove(unit)
        }
        gameMap.updateUnitList()
    }

    fun restoreCoreUnitList(
        player: Player,
        saved: List<dynamic>,
    ) {
        gameMap.units.filter { it.isCore && it.isDeployed }.forEach { player.addCoreUnit(it) }
        saved.filter { !(it.isDeployed as? Boolean ?: false) }.forEach { savedUnit ->
            player.addCoreUnit(buildRestoredUnit(savedUnit, player))
        }
    }

    private fun buildRestoredUnit(
        savedUnit: dynamic,
        player: Player,
    ): GameUnit {
        val unit = GameUnit((savedUnit.eqid as? Int) ?: 0)
        unit.id = savedUnit.id as? Int ?: -1
        unit.owner = savedUnit.owner as? Int ?: player.id
        unit.flag = savedUnit.flag as? Int ?: (player.country + 1)
        unit.strength = savedUnit.strength as? Int ?: FULL_STRENGTH
        unit.experience = savedUnit.experience as? Int ?: 0
        unit.leader = savedUnit.leader as? Int ?: -1
        unit.carrier = savedUnit.carrier as? Int ?: 0
        unit.isMounted = savedUnit.isMounted as? Boolean ?: false
        unit.isCore = true
        unit.isDeployed = false
        unit.hasOverstrength = savedUnit.hasOverstrength as? Boolean ?: false
        unit.customName = savedUnit.customName as? String // optional key (rename feature)
        // Carried, never re-minted: this is the scenario transition the formation id exists to
        // survive. A pre-hero save has no key here and gets one on addCoreUnit below.
        unit.formationId = savedUnit.formationId as? String
        unit.player = player
        applyRestoredTransport(savedUnit, unit)
        return unit
    }

    private fun applyRestoredTransport(
        savedUnit: dynamic,
        unit: GameUnit,
    ) {
        savedUnit.transport?.let { t ->
            val teqid = t.eqid as? Int ?: 0
            if (teqid > 0) {
                unit.setTransport(teqid)
                unit.transport?.ammo = t.ammo as? Int ?: 0
                unit.transport?.fuel = t.fuel as? Int ?: 0
            }
        }
    }

    fun removeNonCampaignUnits(player: Player) {
        var removed = false
        gameMap.units.filter { it.player?.id != player.id || !it.isCore }.forEach { unit ->
            val hex = unit.getHex()
            if (hex?.isDeployment == player.id) {
                hex.delUnit(unit)
                unit.destroyed = true
                unit.nodossier = true
                removed = true
            }
        }
        if (removed) gameMap.updateUnitList()
    }
}
