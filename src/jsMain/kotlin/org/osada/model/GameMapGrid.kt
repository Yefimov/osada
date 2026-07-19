package org.osada.model

import org.osada.RoadType
import org.osada.TerrainType

/** Grid allocation & hex access for [GameMap], split out to keep its function count in bounds. */
fun GameMap.allocMap() {
    map = Array(rows) { r -> Array(cols) { c -> Hex(r, c) } }
    hasRailDataCache = null
    hasWaterAccessCache = null
    hasOpenWaterAccessCache = null
}

/** Whether this map's grid carries ANY rail data. Computed once and cached on first access
 *  (the grid's rail is only ever populated at load time, never mutated mid-game) — gates
 *  MovementRules' strict "trains only move on rail" enforcement: a scenario never re-patched
 *  with rail= attributes (see tools/og-import/add_rails.py) has none, so trains there fall
 *  back to today's (pre-existing, unrestricted) behaviour rather than becoming immobile. */
fun GameMap.hasRailData(): Boolean {
    hasRailDataCache?.let { return it }
    val found = map?.any { row -> row.any { it.rail > RoadType.NONE.value } } ?: false
    hasRailDataCache = found
    return found
}

/** Whether this map has any Ocean/River/Port hex — the terrain COASTAL movement needs to ever
 *  move at all (per movTableDry row 7: OCEAN/RIVER/PORT are all passable). NOT sufficient for
 *  DEEP_NAVAL/NAVAL — see [hasOpenWaterAccess]. Same cached-once shape as [hasRailData]; used
 *  by EquipmentWindowController to hide ships that could never be deployed anywhere on a
 *  land-locked map's Purchase list. */
fun GameMap.hasWaterAccess(): Boolean {
    hasWaterAccessCache?.let { return it }
    val naval = setOf(TerrainType.OCEAN.value, TerrainType.RIVER.value, TerrainType.PORT.value)
    val found = map?.any { row -> row.any { it.terrain in naval } } ?: false
    hasWaterAccessCache = found
    return found
}

/** Whether this map has any Ocean/Port hex. Per movTableDry rows 6 (DEEP_NAVAL) and 10
 *  (NAVAL), RIVER is 255 (impassable) for both — only COASTAL can actually cross a river (row
 *  7). A river-only map (e.g. Operation Uranus, all Don-river hexes, zero Ocean/Port) must NOT
 *  count as "water access" for submarines/destroyers/battleships, or the Purchase list offers
 *  ships that could never move a single hex (2026-07-15 bug report). */
fun GameMap.hasOpenWaterAccess(): Boolean {
    hasOpenWaterAccessCache?.let { return it }
    val openWater = setOf(TerrainType.OCEAN.value, TerrainType.PORT.value)
    val found = map?.any { row -> row.any { it.terrain in openWater } } ?: false
    hasOpenWaterAccessCache = found
    return found
}

fun GameMap.setHex(
    row: Int,
    col: Int,
    hex: Hex? = null,
) {
    // Store a provided hex INTO the grid — this function silently dropped it before, which
    // is why a RESTORED game rendered terrain-only: GameStateRestore built fresh Hex objects
    // and passed them here, units got registered (so their images even preloaded), but the
    // grid kept allocMap()'s blank hexes and the renderer walks the grid. ScenarioLoader
    // never noticed — it mutates the grid's own hexes in place and calls the no-arg form.
    if (hex != null) map?.getOrNull(row)?.let { if (col in it.indices) it[col] = hex }
    val target = hex ?: map?.getOrNull(row)?.getOrNull(col) ?: return
    val vs = target.victorySide
    if (vs != -1) {
        val ownerSide = if (target.owner != -1) getPlayer(target.owner).side else -1
        val enemySide = if (ownerSide != -1) 1 - ownerSide else vs
        val pos = target.getPos()
        if (sidesVictoryHexes.getOrNull(enemySide)?.none { it.row == pos.row && it.col == pos.col } != false) {
            if (sidesVictoryHexes.size <= enemySide) sidesVictoryHexes.add(mutableListOf())
            sidesVictoryHexes[enemySide].add(pos)
        }
    }
    target.unit?.let { addUnit(it) }
    target.airunit?.let { addUnit(it) }
}

// sidesVictoryHexes[s] = the objectives side s still needs to capture. When side [side] takes
// [pos], remove it from *its own* remaining list and hand it to the enemy (who must now retake
// it); [side] wins once its own list is empty. This mirrors PM's updateVictorySides — the
// sides were previously swapped, which fabricated victories and never fired the "you lost your
// last objective -> defeat" case (an enemy capture of the player's final hex is this same call
// with side = enemy).
fun GameMap.updateVictorySides(
    side: Int,
    pos: Cell,
): Boolean {
    val enemySide = 1 - side
    val ownList = sidesVictoryHexes.getOrNull(side) ?: return false
    val removed = ownList.removeAll { it.row == pos.row && it.col == pos.col }
    if (removed) {
        if (sidesVictoryHexes.size <= enemySide) sidesVictoryHexes.add(mutableListOf())
        sidesVictoryHexes[enemySide].add(pos)
    }
    return sidesVictoryHexes.getOrNull(side)?.isEmpty() ?: false
}

fun GameMap.getDeployHexes(side: Int): Array<Cell> {
    val result = mutableListOf<Cell>()
    map?.forEachIndexed { r, row ->
        row.forEachIndexed { c, hex ->
            if (hex.isDeployment != -1 && getPlayer(hex.isDeployment).side == side) result.add(Cell(r, c))
        }
    }
    return result.toTypedArray()
}
