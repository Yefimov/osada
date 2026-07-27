package org.osada.rules

import org.osada.MovMethod
import org.osada.RoadType
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.hasRailData
import org.osada.movTable

/**
 * [MovementRules.getMoveRange]'s BFS-style cost expansion. Split out purely to keep
 * [MovementRules] within the project's function-count/class-size limits -- not expected to be
 * called from elsewhere.
 */
internal object MoveRangeCalculation {
    // movTable sentinel costs (see Constants.kt movTableDry doc): 255 = impassable via this
    // table; 254 = a fixed high cost, notably enemy-ZOC. 17 is the road/bridge column index.
    private const val IMPASSABLE_TERRAIN_COST = 255
    private const val ZOC_MOVE_COST = 254
    private const val ROAD_MOVE_TABLE_INDEX = 17
    private const val AIR_MOVE_COST = 1

    private class MoveContext(
        val gameMap: Array<Array<Hex>>,
        val pos: org.osada.model.Cell,
        val maxRange: Int,
        val unitSide: Int,
        val cells: MutableList<ExtendedCell>,
        val enforceRail: Boolean,
        val movementTable: List<Int>,
    )

    fun getMoveRange(
        map: GameMap,
        unit: GameUnit,
    ): Array<ExtendedCell> {
        val context = resolveMoveContext(map, unit) ?: return emptyArray()
        return if (UnitPredicates.isAir(unit)) {
            airMoveRange(context).toTypedArray()
        } else {
            groundMoveRange(context, unit).toTypedArray()
        }
    }

    private fun resolveMoveContext(
        map: GameMap,
        unit: GameUnit,
    ): MoveContext? {
        val gameMap = if (unit.hasMoved) null else map.map
        val pos = if (unit.hasMoved) null else unit.getPos()
        val unitSide = unit.player?.side
        if (gameMap == null || pos == null || unitSide == null) return null

        val maxRange = MovementRules.getUnitMoveRange(unit)
        val isTrain = UnitPredicates.isTrain(unit)
        // Strict "trains only move on rail" only applies once the MAP actually carries rail data
        // (tools/og-import/add_rails.py) -- most scenarios haven't been re-patched yet. Only
        // RAIL(12)-flagged units need the WHEELED fallback below to reproduce their pre-fix
        // behaviour (fix_rail_units.py switched them from WHEELED to RAIL): the rarer legacy
        // "repurpose DEEP_NAVAL for trains" convention (e.g. eqp-adlerkorps's Armoured Train) was
        // already completely immobile on land before this fix and is left exactly as it was
        // unless the map has rail data to actually move it on.
        val enforceRail = isTrain && map.hasRailData()
        val unitData = unit.unitData()
        val method =
            if (unitData.movmethod == MovMethod.RAIL.value && !enforceRail) {
                MovMethod.WHEELED.value
            } else {
                unitData.movmethod
            }
        val movementTable = movTable[method]
        val ring =
            HexGeometry
                .getRing(pos.row, pos.col, maxRange, map.rows, map.cols, true)
                .map { it as ExtendedCell }
        val cells = ring.filter { cell -> isValidEdgeCell(cell, map) }.toMutableList()

        return MoveContext(gameMap, pos, maxRange, unitSide, cells, enforceRail, movementTable)
    }

    private fun isValidEdgeCell(
        cell: ExtendedCell,
        map: GameMap,
    ): Boolean {
        val isTopEdgeGap = cell.row == 0 && cell.col % 2 == 0
        val isPartialLastCol = map.isLastColPartial && cell.col == map.cols - 1
        val isPartialLastRow = map.isLastRowPartial && cell.row == map.rows - 1 && cell.col % 2 == 1
        return !isTopEdgeGap && !isPartialLastCol && !isPartialLastRow
    }

    private fun airMoveRange(context: MoveContext): List<ExtendedCell> {
        val result = mutableListOf<ExtendedCell>()
        context.cells.forEach { cell ->
            val hex = context.gameMap[cell.row][cell.col]
            val enemyAirVisible = hex.isSpotted(context.unitSide) || hex.airunit?.tempSpotted == true
            if (!enemyAirVisible || hex.airunit == null) {
                cell.canMove = true
                cell.cost = AIR_MOVE_COST
                result.add(cell)
            }
        }
        return result
    }

    private fun groundMoveRange(
        context: MoveContext,
        unit: GameUnit,
    ): List<ExtendedCell> {
        val result = mutableListOf<ExtendedCell>()
        val cells = context.cells
        cells.add(ExtendedCell(context.pos.row, context.pos.col))
        val enemySide = 1 - context.unitSide
        var k = 0
        while (k <= context.maxRange) {
            cells.filter { it.range == k }.forEach { current ->
                cells
                    .filter { neighbor ->
                        HexGeometry.isAdjacent(current.row, current.col, neighbor.row, neighbor.col) &&
                            neighbor.range >= k
                    }.forEach { neighbor -> expandNeighbor(neighbor, current, context, unit, enemySide) }
            }
            k++
        }
        cells.filter { it.canPass || it.canMove }.forEach { result.add(it) }
        return result
    }

    /** Resolves [neighbor]'s move cost from [current], applies the enemy-ZOC cost floor, then
     *  marks it canPass/canMove when reachable within [MoveContext.maxRange]. */
    private fun expandNeighbor(
        neighbor: ExtendedCell,
        current: ExtendedCell,
        context: MoveContext,
        unit: GameUnit,
        enemySide: Int,
    ) {
        val hex = context.gameMap[neighbor.row][neighbor.col]
        resolveNeighborCost(neighbor, hex, context, enemySide)
        updateReachDistance(neighbor, current)
        markPassableAndVisible(neighbor, hex, context, unit)
    }

    /** Sets [neighbor].cost from the movement table (or rail cost), floored to [ZOC_MOVE_COST]
     *  when the hex is a spotted enemy zone of control. */
    private fun resolveNeighborCost(
        neighbor: ExtendedCell,
        hex: Hex,
        context: MoveContext,
        enemySide: Int,
    ) {
        // The road column is a BONUS, so it may only ever be taken when this movement method can
        // actually use a road. PM applies it unconditionally (`openpanzer.js:2157`), and because
        // the road entry is 255 for all three naval rows, a river hex carrying a road -- i.e. a
        // BRIDGE -- became impassable to ships: in `Falciu 1` the Shtorm TB could run the river
        // freely but could not pass (19,23), `river/road9`. OG has no such rule and cannot have
        // one: its `TerrainEx.txt` `[terrain-cost]` table is 19 terrain columns with NO road
        // column at all, and BASEKORP's Coastal row gives river cost 1 outright. Falling back to
        // the terrain column changes nothing for land units (their road entry is 1, passable) and
        // does not float a deep-naval ship up a river (its river entry is 255 either way).
        val roadCost = context.movementTable[ROAD_MOVE_TABLE_INDEX]
        val onRoad = hex.road > RoadType.NONE.value || MovementRules.isBridgeForSide(hex, context.unitSide)
        neighbor.cost =
            when {
                context.enforceRail -> if (hex.rail > RoadType.NONE.value) 1 else IMPASSABLE_TERRAIN_COST
                onRoad && roadCost != IMPASSABLE_TERRAIN_COST -> roadCost
                else -> context.movementTable[hex.terrain]
            }
        val inEnemyZoc = (hex.isSpotted(context.unitSide) || hex.unit?.tempSpotted == true) && hex.isZOC(enemySide)
        if (inEnemyZoc && neighbor.cost < ZOC_MOVE_COST) {
            neighbor.cost = ZOC_MOVE_COST
        }
    }

    /** Propagates [current]'s cumulative cost (cout) into [neighbor]'s entry cost (cin/cout),
     *  taking the cheaper of any two paths already found to reach it. */
    private fun updateReachDistance(
        neighbor: ExtendedCell,
        current: ExtendedCell,
    ) {
        if (neighbor.cin == 0) neighbor.cin = current.cout
        if (neighbor.cout == 0) neighbor.cout = neighbor.cin + neighbor.cost
        if (neighbor.cin > current.cout && neighbor.cost <= ZOC_MOVE_COST) {
            neighbor.cin = current.cout
            neighbor.cout = neighbor.cin + neighbor.cost
        }
    }

    /** Marks [neighbor] canPass/canMove once it's within [MoveContext.maxRange]. */
    private fun markPassableAndVisible(
        neighbor: ExtendedCell,
        hex: Hex,
        context: MoveContext,
        unit: GameUnit,
    ) {
        val reachable =
            neighbor.cout <= context.maxRange || (neighbor.cost <= ZOC_MOVE_COST && neighbor.cin <= context.maxRange)
        if (!reachable) return
        if (MovementRules.canPassInto(context.gameMap, unit, neighbor)) neighbor.canPass = true
        val enemyVisible = hex.isSpotted(context.unitSide) || hex.unit?.tempSpotted == true
        if (!enemyVisible || hex.unit == null) {
            neighbor.canMove = true
        }
    }
}
