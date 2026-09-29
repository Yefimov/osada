package org.osada.rules

import org.osada.LeaderType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.getUnits

/**
 * The two leaders that AMBUSH a moving enemy, as OG runs them (2026-09-26, replacing a one-sided
 * "opportunity fire" that neither stopped the move nor let the mover answer).
 *
 * OG's combat page lists it as one of the ways a Normal Combat starts -- *"if "surprised" while
 * moving by unspotted/overwatch enemy unit having at least 1 strength point (sp) unsuppressed"* --
 * and its changelog names the two sources: *"Units surprised by overwatch/cammo leaders or surprise
 * contact"* (0.92.0.2). The manual states each trigger:
 *
 *  - **Overwatch** -- *"each turn the unit surprises the first enemy unit that enters its attack
 *    range"*;
 *  - **Camouflage Expert** (OSADA's `FOREST_CAMOUFLAGE` leader slot; Panzer Marshal called it Forest
 *    Camouflage) -- *"in forest, city, desert, bocage or mountain, unit can only be spotted by
 *    adjacent units, and fires at the first unit that enters its ZOC"*.
 *
 * What an ambush DOES is the ordinary surprise: the move stops where it was sprung, the mover is
 * surprised, and it fights the ambusher as a surprised attacker -- OG's Rugged mode, the defender
 * firing first, the mover answering with what it has left and only if it can reach
 * (`GameMap.surpriseTarget`, `RuggedCombat`). This object only decides WHO springs it.
 *
 * Once a turn per leader ([GameUnit.hasAmbushedThisTurn]), which is "the first enemy unit". A mover
 * that "cannot be surprised" is never ambushed ([canBeSurprised]).
 */
internal object MoveAmbush {
    private const val ADJACENT = 1

    /** The enemy that springs an ambush on [mover] entering [cell], or null. First in map order,
     *  so every multiplayer peer picks the same one. */
    fun ambusherFor(
        map: GameMap,
        mover: GameUnit,
        cell: Cell,
    ): GameUnit? {
        val moverSide = mover.player?.side
        return if (moverSide == null || !canBeSurprised(mover)) {
            null
        } else {
            map.getUnits().firstOrNull { springs(it, mover, moverSide, cell) }
        }
    }

    /**
     * The negation of [UnitCapabilities.cannotBeSurprised]: the one test for every way a move can
     * be surprised -- walking into a hidden unit as much as an ambush.
     */
    fun canBeSurprised(unit: GameUnit): Boolean = !UnitCapabilities.cannotBeSurprised(unit)

    private fun springs(
        ambusher: GameUnit,
        mover: GameUnit,
        moverSide: Int,
        cell: Cell,
    ): Boolean {
        val pos = ambusher.getPos() ?: return false
        val distance = HexGeometry.distance(pos.row, pos.col, cell.row, cell.col)
        val overwatch =
            Leaders.unitHasLeader(ambusher, LeaderType.OVERWATCH) &&
                distance <= AttackEligibility.getUnitAttackRange(ambusher)
        // "Enters its ZOC": aircraft neither project nor feel a zone of control
        // (`MovementRules.setZOCRange`), so only ground formations spring or suffer this one.
        val camouflage =
            Leaders.unitHasLeader(ambusher, LeaderType.FOREST_CAMOUFLAGE) &&
                distance <= ADJACENT &&
                !UnitPredicates.isAir(ambusher) &&
                !UnitPredicates.isAir(mover) &&
                UnitConcealment.isCamouflageTerrain(ambusher.getHex()?.terrain ?: -1)
        return ambusher.player?.side != moverSide &&
            !ambusher.destroyed &&
            !ambusher.hasAmbushedThisTurn &&
            (overwatch || camouflage) &&
            // "having at least 1 strength point unsuppressed" -- and ammunition and a weapon that
            // can reach this target at all, which is the same question.
            ambusher.strength > UnitCapabilities.effectiveHits(ambusher) &&
            AttackEligibility.canFire(ambusher, mover)
    }
}
