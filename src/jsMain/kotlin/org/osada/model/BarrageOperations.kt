package org.osada.model

import org.osada.rules.Barrage

/**
 * The map-side half of OG 9.2's barrage: the targeting overlay, and firing one.
 *
 * Split from [Barrage] itself for the reason `MinefieldOperations` is split from `Minefields` — the
 * rule owns what is legal and what the shells do, this owns the hexes and the selection, and the
 * command handlers call this one so the local player, a replayed multiplayer order and a test all
 * take the same path.
 */
internal fun GameMap.openBarrageTargeting(unit: GameUnit): Int {
    clearBarrageTargeting()
    val cells = Barrage.targets(this, unit)
    cells.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isBarrageSel = true
    }
    currentBarrageTargets.addAll(cells)
    return cells.size
}

/** Leaves the targeting mode, clearing the overlay it painted. */
internal fun GameMap.clearBarrageTargeting() {
    currentBarrageTargets.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isBarrageSel = false
        markRepaint(cell)
    }
    currentBarrageTargets.clear()
}

/**
 * Fires [unit]'s barrage at ([row], [col]) and closes the targeting mode.
 *
 * Returns null when the order was not legal — a stale overlay, a hex that became spotted between
 * opening the mode and clicking, a gun that has already fired. Nothing is spent in that case: the
 * refusal costs the player neither ammunition nor their shot.
 */
internal fun GameMap.fireBarrage(
    unit: GameUnit,
    row: Int,
    col: Int,
): Barrage.BarrageResult? {
    val cell = Cell(row, col)
    if (!Barrage.canTarget(this, unit, cell)) {
        clearBarrageTargeting()
        return null
    }
    val result = Barrage.resolve(this, unit, cell)
    clearBarrageTargeting()
    // The shot is gone, so the strip and the ranges have to be rebuilt from the new turn state —
    // and a wrecked bridge or a rubbled hex changes what everything around it costs to enter.
    if (!unit.hasMoved) setMoveRange(unit)
    setAttackRange(unit)
    return result
}
