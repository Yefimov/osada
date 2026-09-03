package org.osada.model

import org.osada.rules.RailTransport

/**
 * The map-side half of OG's railway transport: the destination overlay, and making the journey.
 *
 * Split from [RailTransport] itself exactly as `BarrageOperations` is split from `Barrage` — the
 * rule owns what is legal and what the move does, this owns the hexes and the selection, and every
 * caller (the local player, a replayed multiplayer order, a test) takes the same path.
 */
internal fun GameMap.openRailDestinations(unit: GameUnit): Int {
    clearRailTargeting()
    val cells = RailTransport.destinations(this, unit)
    cells.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isRailSel = true
    }
    currentRailTargets.addAll(cells)
    return cells.size
}

/** Leaves the destination mode, clearing the overlay it painted. */
internal fun GameMap.clearRailTargeting() {
    currentRailTargets.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isRailSel = false
        markRepaint(cell)
    }
    currentRailTargets.clear()
}

/**
 * Rails [unit] to ([row], [col]) and closes the mode.
 *
 * Returns false when the order was not legal — a stale overlay, a hex somebody moved into between
 * opening the mode and clicking, a formation that has since acted. Nothing is spent in that case:
 * a refusal costs the player neither a rail transport point nor their move.
 */
internal fun GameMap.moveByRail(
    unit: GameUnit,
    row: Int,
    col: Int,
): Boolean {
    val moved = RailTransport.entrain(this, unit, row, col)
    clearRailTargeting()
    if (moved) {
        // The move is gone and the formation is somewhere else, so both ranges have to be rebuilt
        // from the new position rather than the old one.
        delMoveSel()
        setAttackRange(unit)
    }
    return moved
}
