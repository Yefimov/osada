package org.osada.rules

import org.osada.MovMethod
import org.osada.RoadType
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.hasRailData
import org.osada.movTable

/**
 * [MovementRules.getReinforcementDeployPositions]'s candidate-hex search. Split out purely to
 * keep [MovementRules] within the project's function-count/class-size limits -- not expected to
 * be called from elsewhere.
 */
internal object ReinforcementDeployment {
    // movTable sentinel cost (see Constants.kt movTableDry doc): 255 = impassable via this table.
    private const val IMPASSABLE_TERRAIN_COST = 255

    /** First free, passable hex at/around (row,col) where a reinforcement may deploy, or null. */
    fun getReinforcementDeployPositions(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Cell? {
        val eqData = Equipment.equipment[unit.eqid] ?: return null
        val isTrain = UnitPredicates.isTrain(unit)
        val enforceRail = isTrain && map.hasRailData()
        // Terrain-based fallback table: movTable[RAIL.value] is intentionally all-255 (see
        // Constants.kt) -- a real train must resolve through WHEELED for any plain-terrain check,
        // whether because the map has no rail data at all, OR because its author-declared
        // reinforcement hex simply isn't within this narrow (hex + 6 neighbours) radius of the
        // rail network despite the map having rail elsewhere. Resolving RAIL's own row here would
        // make deployReinforcement return null -> Game.kt's caller never adds the unit to the map
        // -- the reinforcement is silently and PERMANENTLY lost, not merely stuck.
        val movementTable = movTable[if (isTrain) MovMethod.WHEELED.value else eqData.movmethod]
        val candidates = mutableListOf<Cell>()
        candidates.add(Cell(row, col))
        candidates.addAll(HexGeometry.getAdjacent(row, col))

        // Prefer landing right on the rail network, but fall through to the ordinary terrain
        // check (same as a non-train unit) rather than dropping the reinforcement entirely if no
        // candidate hex is on rail -- the unit will simply need to reach the rail network before
        // it can move.
        val railCandidate = if (enforceRail) findRailDeployCell(map, candidates, unit) else null
        return railCandidate ?: findTerrainDeployCell(map, candidates, unit, movementTable)
    }

    private fun isDeploySlotEmpty(
        hex: Hex,
        unit: GameUnit,
    ): Boolean =
        if (UnitPredicates.isAir(unit)) {
            hex.airunit == null
        } else {
            (UnitPredicates.isGround(unit) || UnitPredicates.isSea(unit)) && hex.unit == null
        }

    private fun findRailDeployCell(
        map: GameMap,
        candidates: List<Cell>,
        unit: GameUnit,
    ): Cell? {
        candidates.forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            val eligible =
                cell.row >= 0 && cell.col >= 0 && isDeploySlotEmpty(hex, unit) && hex.rail > RoadType.NONE.value
            if (eligible) return Cell(cell.row, cell.col)
        }
        return null
    }

    private fun findTerrainDeployCell(
        map: GameMap,
        candidates: List<Cell>,
        unit: GameUnit,
        movementTable: List<Int>,
    ): Cell? {
        candidates.forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            val eligible =
                cell.row >= 0 &&
                    cell.col >= 0 &&
                    isDeploySlotEmpty(hex, unit) &&
                    movementTable[hex.terrain] < IMPASSABLE_TERRAIN_COST
            if (eligible) return Cell(cell.row, cell.col)
        }
        return null
    }
}
