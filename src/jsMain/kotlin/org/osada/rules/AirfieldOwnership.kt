package org.osada.rules

import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.model.getPlayer

/**
 * Whose airfield an airfield hex is -- for a field drawn across several hexes with ONE flag.
 *
 * An OG map draws a large airfield as a run of adjacent airfield hexes and flags one of them:
 * `Forward2.xscn`'s Minsk Airfield is four hexes, `(22,18)` flagged and owned, `(21,17)`, `(22,16)`
 * and `(22,17)` carrying neither byte (`@8`/`@9` both zero -- measured 2026-09-30, so this is the
 * author's map and not an import loss). Read hex by hex, three quarters of the field belonged to
 * nobody: a MiG-3 parked on `(22,16)`, two hexes from the flag, was refused resupply on its own
 * airfield (owner's report, 2026-09-30).
 *
 * **The flag speaks for the whole field.** An unflagged airfield hex takes the flag of the airfield
 * it is part of -- the airfield hexes connected to it through other airfield hexes -- so capturing
 * the flag captures the runway, as the picture on the map says it should. A field that carries no
 * flag anywhere stays what it was: scenery that serves nobody (the old
 * *"an unflagged airfield is scenery"* reading is kept for exactly that case).
 *
 * `INFERENCE`: OG's manual says only *"air units in or adjacent to an airfield"*, and the author's
 * changelog speaks of a *"friend airfield"*; neither addresses a partly flagged field. This reading
 * is the one under which the shipped map means what it draws.
 *
 * Deployment reads the same field through [servesSide]: OG places aircraft on every hex of a
 * friendly field, not only the flagged one -- `forward0.xml` (Khalkhin Gol) flags `(27,3)` and
 * leaves `(27,4)` and `(26,3)` bare, and OG deploys on all three (owner's report, 2026-10-07).
 */
internal object AirfieldOwnership {
    /** True when the airfield hex at ([row], [col]) serves [country]. A flagged hex answers for itself. */
    fun servesCountry(
        map: GameMap,
        row: Int,
        col: Int,
        country: Int?,
    ): Boolean {
        val hex = map.map?.getOrNull(row)?.getOrNull(col)
        return when {
            hex == null || country == null || hex.terrain != TerrainType.AIRFIELD.value -> false
            hex.flag == country -> true
            hex.flag != -1 -> false
            else -> fieldFlags(map, row, col).contains(country)
        }
    }

    /**
     * True when the airfield hex at ([row], [col]) belongs to [side] by its OWNER (a player id) --
     * the deployment twin of [servesCountry]. An owned hex answers for itself; an unowned one takes
     * the owners of its field. -1 is excluded explicitly: `getPlayer(-1)` falls back to player 0.
     */
    fun servesSide(
        map: GameMap,
        row: Int,
        col: Int,
        side: Int?,
    ): Boolean {
        val hex = map.map?.getOrNull(row)?.getOrNull(col)
        return when {
            hex == null || side == null || hex.terrain != TerrainType.AIRFIELD.value -> false
            hex.owner != -1 -> map.getPlayer(hex.owner).side == side
            else -> fieldValues(map, row, col) { it.owner }.any { map.getPlayer(it).side == side }
        }
    }

    /** Every flag on the airfield ([row], [col]) belongs to: a flood fill over adjacent airfield hexes. */
    private fun fieldFlags(
        map: GameMap,
        row: Int,
        col: Int,
    ): Set<Int> = fieldValues(map, row, col) { it.flag }

    /** Every non-(-1) [value] on the airfield ([row], [col]) belongs to, flagged or owned hexes alike. */
    private fun fieldValues(
        map: GameMap,
        row: Int,
        col: Int,
        value: (Hex) -> Int,
    ): Set<Int> {
        val flags = mutableSetOf<Int>()
        val seen = mutableSetOf(row to col)
        val queue = ArrayDeque(listOf(row to col))
        while (queue.isNotEmpty()) {
            val (r, c) = queue.removeFirst()
            HexGeometry.getAdjacent(r, c).forEach { cell ->
                val next = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
                if (next != null && next.terrain == TerrainType.AIRFIELD.value && seen.add(cell.row to cell.col)) {
                    if (value(next) != -1) flags += value(next)
                    queue.addLast(cell.row to cell.col)
                }
            }
        }
        return flags
    }
}
