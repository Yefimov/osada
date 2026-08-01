package org.osada.model

import org.osada.TerrainType
import org.osada.rules.HexGeometry

// Where a player may place reserve units. Split from GameMapGrid.kt (function-count limits) and
// also a distinct concern: the rest of that file answers questions about the grid as authored and
// cached once at load, while a deploy zone changes during the battle.

/**
 * Every hex [side] may place a reserve unit on: the author's own deploy/supply zone, **plus every
 * owned supply hex and the ring around it**.
 *
 * The second half was missing, and it made captured ports useless. [ownsSupplyHex] already lets a
 * player BUY once a port changes hands ([hasPurchaseAnchor]) — but placement read only
 * `hex.isDeployment`, an attribute fixed at scenario load, so N_Kiel (0 authored deploy hexes,
 * 3 enemy-held ports) let the player buy units with nowhere on the map to put them. Reported
 * 2026-07-31: "I've captured hexes (27,12) and (29,10) but no deployment hexes appeared near them."
 *
 * The ring is OG's measured behaviour, recorded on [ownsSupplyHex]: in N_Kiel both Kieler Hafen
 * ports open deployment on their adjacent hexes once genuinely owned — (27,12) →
 * (27,13)/(28,13)/(29,12), and (29,11) → (30,11). Only the *zone* is widened here; which units may
 * actually stand on a given hex in it stays with [canDeployOnTerrain] and the occupancy check,
 * exactly as for an authored zone.
 *
 * Cells are encoded `row * cols + col` so the result is a cheap `Set<Int>` the per-hex renderer can
 * probe without rebuilding anything. Cached per side and dropped by `GameMap.invalidateDeployZones`
 * on every ownership change — capturing a port has to open its zone the same turn.
 */
internal fun GameMap.deployZone(side: Int): Set<Int> =
    deployZoneCache.getOrPut(side) {
        val grid = map ?: return@getOrPut emptySet()
        val zone = mutableSetOf<Int>()
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, hex ->
                if (hex.isDeployment != -1 && getPlayer(hex.isDeployment).side == side) {
                    zone += r * cols + c
                }
                if (isOwnedSupplyHex(hex, side)) {
                    zone += r * cols + c
                    HexGeometry.getAdjacent(r, c).forEach { cell ->
                        if (cell.row in 0 until rows && cell.col in 0 until cols) {
                            zone += cell.row * cols + cell.col
                        }
                    }
                }
            }
        }
        zone
    }

/** `hex.owner` is a PLAYER id, not a side, and `getPlayer(-1)` falls back to player 0 — so an
 *  unowned port must be excluded explicitly or it silently reads as "belongs to player 0". */
private fun GameMap.isOwnedSupplyHex(
    hex: Hex,
    side: Int,
): Boolean = hex.terrain == TerrainType.PORT.value && hex.owner != -1 && getPlayer(hex.owner).side == side

fun GameMap.getDeployHexes(side: Int): Array<Cell> = deployZone(side).map { Cell(it / cols, it % cols) }.toTypedArray()

/** Whether [side] may place a reserve unit on ([row], [col]) — see [deployZone]. Shared by the
 *  deploy click gate, the deploy highlight and the AI's own placement pass, so all three agree on
 *  the zone instead of each testing `hex.isDeployment` for itself. */
fun GameMap.isInDeployZone(
    side: Int,
    row: Int,
    col: Int,
): Boolean = (row * cols + col) in deployZone(side)
