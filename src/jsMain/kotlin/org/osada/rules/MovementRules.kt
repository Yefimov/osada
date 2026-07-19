package org.osada.rules

import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.isBridge

/**
 * Movement, pathfinding, spotting, zone-of-control and embark/deploy rules.
 *
 * Owns everything about where a unit may go and what it can see, extracted from the
 * former `GameRules` god-object. Depends on [HexGeometry] for spatial primitives and
 * [UnitPredicates] for unit classification. Faithful port of the corresponding
 * `osada.js` rules.
 */
object MovementRules {
    /** Reachable cells for [unit] this turn, annotated with movement cost/path metadata. */
    fun getMoveRange(
        map: GameMap,
        unit: GameUnit,
    ): Array<ExtendedCell> = MoveRangeCalculation.getMoveRange(map, unit)

    /** Effective movement points for [unit] this turn (capped by fuel, boosted by leaders). */
    fun getUnitMoveRange(unit: GameUnit): Int {
        var range = unit.getMovesLeft()
        val data = unit.unitData()
        if (UnitPredicates.unitUsesFuel(unit) && unit.getFuel() < range) range = unit.getFuel()
        if (Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_TANK_MANEUVER)) range += 1
        if (Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_MANEUVER)) range += 1
        val isStrandedTowedGun =
            data.movmethod == MovMethod.TOWED.value &&
                unit.transport == null &&
                range == 0 &&
                data.uclass != UnitClass.FORTIFICATION.value
        if (isStrandedTowedGun) {
            range = 1
        }
        return range
    }

    /** A* shortest path from [start] to [end] over the precomputed [moveRange] cells. */
    fun getShortestPath(
        start: Cell,
        end: Cell,
        moveRange: List<Cell>,
    ): List<Cell> = PathFinding.getShortestPath(start, end, moveRange)

    /** True when [unit] may enter [cell] (empty, or occupied only by a friendly unit). */
    fun canPassInto(
        map: Array<Array<Hex>>?,
        unit: GameUnit,
        cell: Cell,
    ): Boolean {
        val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return false
        return when {
            UnitPredicates.isAir(unit) -> hex.airunit == null || hex.airunit!!.player?.side == unit.player?.side
            UnitPredicates.isGround(unit) || UnitPredicates.isSea(unit) ->
                hex.unit == null ||
                    hex.unit!!.player?.side == unit.player?.side
            else -> false
        }
    }

    /** A friendly non-mounted bridge unit on a river/stream hex acts as a road for [side]. */
    fun isBridgeForSide(
        hex: Hex?,
        side: Int,
    ): Boolean {
        val bridgeUnit = hex?.unit
        val onRiverOrStream = hex?.terrain == TerrainType.RIVER.value || hex?.terrain == TerrainType.STREAM.value
        val isFriendlyDismounted = bridgeUnit != null && !bridgeUnit.isMounted && bridgeUnit.player?.side == side
        if (bridgeUnit == null || !onRiverOrStream || !isFriendlyDismounted) return false
        return Equipment.isBridge(bridgeUnit.eqid)
    }

    /** Adds or removes [unit]'s zone of control on its neighbouring hexes. */
    fun setZOCRange(
        map: GameMap,
        unit: GameUnit,
        add: Boolean,
    ) {
        val skip = unit == null || UnitPredicates.isAir(unit)
        val pos = if (skip) null else unit.getPos()
        val side = if (skip) null else unit.player?.side
        if (pos == null || side == null) return
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                map.map
                    ?.getOrNull(cell.row)
                    ?.getOrNull(cell.col)
                    ?.setZOC(side, add)
            }
        }
    }

    /** Adds or removes [unit]'s spotting; returns how many enemy units became newly spotted. */
    fun setSpotRange(
        map: GameMap,
        unit: GameUnit,
        add: Boolean,
    ): Int {
        val pos = if (unit == null) null else unit.getPos()
        val side = if (unit == null) null else unit.player?.side
        if (pos == null || side == null) return 0
        val range = getUnitSpotRange(unit)
        val cells = HexGeometry.getRing(pos.row, pos.col, range, map.rows, map.cols, false)
        cells.add(Cell(pos.row, pos.col))
        var newlySpotted = 0
        cells.forEach { cell ->
            if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
                if (add && !hex.isSpotted(side)) {
                    val enemy = hex.getUnit(false)
                    if (enemy != null && enemy.player?.side != side) {
                        enemy.tempSpotted = true
                        newlySpotted++
                    }
                }
                hex.setSpotted(side, add)
            }
        }
        return newlySpotted
    }

    /** Spotting range for [unit] including recon leader bonuses. */
    fun getUnitSpotRange(unit: GameUnit): Int {
        if (unit == null) return 0
        var range = unit.unitData().spotrange
        if (Leaders.unitHasLeader(unit, LeaderType.ELITE_RECON_VETERAN)) range += 2
        if (Leaders.unitHasLeader(unit, LeaderType.SKILLED_RECONNAISSANCE)) range += 1
        return range
    }

    /** First free, passable hex at/around (row,col) where a reinforcement may deploy, or null. */
    fun getReinforcementDeployPositions(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Cell? = ReinforcementDeployment.getReinforcementDeployPositions(map, unit, row, col)

    /**
     * True when [unit] is on or beside a friendly airfield/carrier. Used by air resupply
     * and reinforcement rules ([SupplyRules]).
     */
    internal fun hasAirfield(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val pos = unit.getPos()
        val hex = if (pos == null) null else map.map?.getOrNull(pos.row)?.getOrNull(pos.col)
        if (pos == null || hex == null) return false
        val onOwnAirfield = hex.terrain == TerrainType.AIRFIELD.value && hex.flag == unit.player?.country
        val onOwnCarrier = hex.unit?.unitData()?.uclass == UnitClass.CARRIER.value && hex.unit?.owner == unit.player?.id
        val adjacentToAirfield =
            HexGeometry.getAdjacent(pos.row, pos.col).any { cell ->
                val neighbor = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
                neighbor?.terrain == TerrainType.AIRFIELD.value && neighbor.flag == unit.player?.country
            }
        return onOwnAirfield || onOwnCarrier || adjacentToAirfield
    }
}
