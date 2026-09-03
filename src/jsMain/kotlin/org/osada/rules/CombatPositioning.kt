package org.osada.rules

import org.osada.MovMethod
import org.osada.RoadType
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getAttackableUnit
import org.osada.movTable
import org.osada.rules.CombatPositioning.getUnitAttackCells
import kotlin.math.abs

/**
 * Hex-position lookups for [CombatResolver]: which cells a unit can attack into, and where a
 * defeated defender may retreat to. Split out purely to keep [CombatResolver] within the
 * project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object CombatPositioning {
    private const val ONE_HEX_ATTACK_RANGE = 1
    private const val TRAIN_FACING_MIRROR_BASE = 8
    private const val IMPASSABLE_TERRAIN_COST = 255

    /** Every cell [unit] could attack into this turn (ground and/or air targets, respecting its
     *  attack range, ammo, weather-grounding and the own-hex-only rule for short-range air units
     *  attacking ground while still able to also strike an air target elsewhere). */
    fun getUnitAttackCells(
        map: Array<Array<Hex>>?,
        unit: GameUnit,
        rows: Int,
        cols: Int,
    ): Array<Cell> {
        val result = mutableListOf<Cell>()
        // `blockedByMoveThenFire` is checked here as well as inside `canInitiateAttack` so the
        // overlay never paints a target the rule would then refuse -- the same reason
        // `airGroundedByWeather` is duplicated here. It is a no-op unless `heavy_move_fire` is on.
        val cannotAttack =
            unit.hasFired ||
                unit.getAmmo() <= 0 ||
                AttackEligibility.airGroundedByWeather(unit) ||
                AttackEligibility.blockedByMoveThenFire(unit)
        val pos = if (cannotAttack) null else unit.getPos()
        if (pos == null) return result.toTypedArray()
        val range = AttackEligibility.getUnitAttackRange(unit)
        val cells = HexGeometry.getRing(pos.row, pos.col, range, rows, cols, false)
        cells.add(Cell(pos.row, pos.col))
        cells.forEach { cell ->
            val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            addAttackableCell(hex, cell, pos, unit, range, result)
        }
        return result.toTypedArray()
    }

    /** Appends [cell] to [result] when [unit] can attack a ground and/or air target on [hex],
     *  per [getUnitAttackCells]'s own-hex-only rule for short-range air units. */
    private fun addAttackableCell(
        hex: Hex,
        cell: Cell,
        pos: Cell,
        unit: GameUnit,
        range: Int,
        result: MutableList<Cell>,
    ) {
        if (hex.getAttackableUnit(unit, false) != null) {
            if (UnitPredicates.isAir(unit) && range <= ONE_HEX_ATTACK_RANGE) {
                // Air unit with range <= 1: can only ground-attack from its own hex,
                // then fall through to the air-target check below (matches JS).
                if (cell.row == pos.row && cell.col == pos.col) result.add(cell)
            } else {
                result.add(cell)
                return
            }
        }
        val airAttackable = hex.getAttackableUnit(unit, true)
        if (airAttackable != null) {
            if (UnitPredicates.isAir(unit)) {
                if (UnitPredicates.isAir(airAttackable)) result.add(cell)
            } else {
                result.add(cell)
            }
        }
    }

    /**
     * First passable, empty hex [unit] can retreat into, preferring its rear facing, or null when
     * it has nowhere to go.
     *
     * **A train retreats along the track or not at all** — the same rule
     * `MoveRangeCalculation` applies to a chosen move, and for the same reason: a forced retreat
     * over open ground is still an armoured train leaving its rails. The plain-terrain fallback
     * that used to run for trains is gone.
     *
     * Returning null here does NOT destroy the unit. `CombatResolver.shouldDefenderSurrender`
     * exempts trains explicitly, so one that cannot pull back simply stays where it is and keeps
     * firing — see that function for why "no retreat" and "encircled" are different questions for
     * a formation whose retreat set is bounded by track rather than by the enemy.
     */
    fun getRetreatPosition(
        map: Array<Array<Hex>>?,
        unit: GameUnit,
        rows: Int,
    ): Cell? {
        val data = unit.unitData()
        val pos = if (data.movpoints == 0) null else unit.getPos()
        if (pos == null) return null

        val ordered = retreatCellsByFacing(unit, pos)
        val isTrain = UnitPredicates.isTrain(unit)
        // movTable[RAIL.value] is intentionally all-255 (Constants.kt) -- a real train must
        // resolve through WHEELED for the terrain half of the check, same reasoning as
        // MovementRules.getReinforcementDeployPositions. The rail requirement is layered on top.
        val movementTable = movTable[if (isTrain) MovMethod.WHEELED.value else data.movmethod]

        return firstPassableRetreatCell(map, ordered, rows, movementTable, requireRail = isTrain)
    }

    /**
     * True when [unit] has no retreat hex ONLY because friendly units are standing in the ones it
     * could otherwise have used — i.e. some adjacent cell is on-map and terrain-passable, and the
     * unit occupying it is on the same side.
     *
     * Surrender is meant to punish being *cut off*: pinned against the map edge, water, mountains
     * or enemies. Being crowded out by your own stack is a traffic-jam, not an encirclement, and
     * must not kill the unit — so the caller skips surrender when this is true.
     */
    fun isRetreatBlockedByOwnUnitsOnly(
        map: Array<Array<Hex>>?,
        unit: GameUnit,
        rows: Int,
    ): Boolean {
        val data = unit.unitData()
        val pos = if (data.movpoints == 0) null else unit.getPos()
        return if (pos == null) {
            false
        } else {
            val movementTable = movTable[if (UnitPredicates.isTrain(unit)) MovMethod.WHEELED.value else data.movmethod]
            val side = unit.player?.side
            HexGeometry.getAdjacent(pos.row, pos.col).any { cell ->
                isFriendlyOccupiedPassableCell(map, cell, rows, movementTable, side)
            }
        }
    }

    /** On-map, terrain-passable, and occupied by a unit on [side] — the "my own stack is in the
     *  way" case that must NOT count as encirclement. */
    private fun isFriendlyOccupiedPassableCell(
        map: Array<Array<Hex>>?,
        cell: Cell,
        rows: Int,
        movementTable: List<Int>,
        side: Int?,
    ): Boolean {
        val offMap = (cell.row == 0 && cell.col % 2 == 0) || (cell.row == rows - 1 && cell.col % 2 == 1)
        val hex = if (offMap) null else map?.getOrNull(cell.row)?.getOrNull(cell.col)
        val occupant = hex?.unit
        return hex != null &&
            movementTable[hex.terrain] < IMPASSABLE_TERRAIN_COST &&
            occupant != null &&
            occupant.player?.side == side
    }

    /** [unit]'s adjacent cells ordered rear-facing-first (the preferred retreat direction). */
    private fun retreatCellsByFacing(
        unit: GameUnit,
        pos: Cell,
    ): List<Cell> {
        val facingIndex = abs(unit.facing - TRAIN_FACING_MIRROR_BASE)
        val adjacent = HexGeometry.getAdjacent(pos.row, pos.col)
        val preferredIndex = HexGeometry.facingToAdjacentIndex(facingIndex)
        val ordered = mutableListOf<Cell>()
        ordered.add(adjacent[preferredIndex])
        adjacent.forEachIndexed { index, cell ->
            if (index != preferredIndex) ordered.add(cell)
        }
        return ordered
    }

    /** First cell in [ordered] that is on-map, unoccupied and terrain-passable (or rail, when
     *  [requireRail]), or null if none qualify. */
    private fun firstPassableRetreatCell(
        map: Array<Array<Hex>>?,
        ordered: List<Cell>,
        rows: Int,
        movementTable: List<Int>,
        requireRail: Boolean,
    ): Cell? {
        ordered.forEach { cell ->
            if (cell.row == 0 && cell.col % 2 == 0) return@forEach
            if (cell.row == rows - 1 && cell.col % 2 == 1) return@forEach
            val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            val passable =
                if (requireRail) {
                    hex.rail > RoadType.NONE.value
                } else {
                    movementTable[hex.terrain] <
                        IMPASSABLE_TERRAIN_COST
                }
            if (hex.unit == null && passable) return Cell(cell.row, cell.col)
        }
        return null
    }
}
