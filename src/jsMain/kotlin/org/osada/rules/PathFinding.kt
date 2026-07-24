package org.osada.rules

import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.model.PathCell

/**
 * [MovementRules.getShortestPath]'s A* implementation. Split out purely to keep
 * [MovementRules] within the project's function-count/class-size limits -- not expected to be
 * called from elsewhere.
 */
internal object PathFinding {
    /** A* shortest path from [start] to [end] over the precomputed [moveRange] cells. */
    fun getShortestPath(
        start: Cell,
        end: Cell,
        moveRange: List<Cell>,
    ): List<Cell> {
        val result = mutableListOf<Cell>()
        val openList = mutableListOf<PathCell>()
        val closedList = mutableListOf<PathCell>()
        val startNode =
            PathCell(start.row, start.col).apply {
                dist = 0.0
                prev = this
            }
        openList.add(startNode)

        val nodeMap = mutableMapOf<Pair<Int, Int>, PathCell>()
        nodeMap[start.row to start.col] = startNode

        moveRange.forEach { cell ->
            val node = PathCell(cell.row, cell.col)
            node.cost = if (cell is ExtendedCell) cell.cost else 1
            nodeMap[cell.row to cell.col] = node
            openList.add(node)
        }

        while (openList.isNotEmpty()) {
            val current = openList.minByOrNull { it.dist }
            if (current == null || current.dist == Double.POSITIVE_INFINITY) break
            openList.remove(current)
            closedList.add(current)

            if (current.row == end.row && current.col == end.col) {
                return reconstructPath(current, startNode)
            }

            relaxNeighbors(openList, current)
        }
        return result
    }

    /** Walks [current]'s `prev` chain back to [startNode], building the path in forward order. */
    private fun reconstructPath(
        current: PathCell,
        startNode: PathCell,
    ): List<Cell> {
        val result = mutableListOf<Cell>()
        var node: PathCell? = current
        while (node != null) {
            result.add(0, Cell(node.row, node.col))
            if (node === startNode) return result
            node = node.prev
        }
        return result
    }

    /** Relaxes every adjacent open-list node's distance/predecessor through [current]. */
    private fun relaxNeighbors(
        openList: List<PathCell>,
        current: PathCell,
    ) {
        openList.filter { HexGeometry.isAdjacent(current.row, current.col, it.row, it.col) }.forEach { neighbor ->
            val tentative = current.dist + neighbor.cost
            if (tentative < neighbor.dist) {
                neighbor.dist = tentative
                neighbor.prev = current
            }
        }
    }
}
