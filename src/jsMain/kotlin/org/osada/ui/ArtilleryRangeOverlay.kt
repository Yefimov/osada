package org.osada.ui

import org.osada.UnitClass
import org.osada.model.GameMap
import org.osada.rules.GameRules
import org.osada.rules.HexGeometry
import org.osada.rules.UnitConcealment
import org.osada.rules.getUnitAttackRange

/**
 * Which hexes each side's guns can reach, for the sidebar's Artillery toggle.
 *
 * The overlay answers one question — *where do the guns reach?* — so its membership test is the
 * formation's EFFECTIVE record ([org.osada.model.GameUnit.unitData] with `useReal = false`) rather
 * than the real one: a howitzer riding its tractor fights with the tractor's stats, and painting
 * its firing envelope while it is mounted would promise a shot the map would refuse.
 *
 * For the same reason the class test is `uclass == ARTILLERY` and **not**
 * [org.osada.rules.UnitCapabilities.hasSupportFire], which is OG's `classDefault xor attr bit 12`
 * and is carried by 18% of infantry records in some efiles. That predicate answers "who may answer
 * a neighbour's attack", which is a different question: under it every rifle squad on the map would
 * grow a one-hex hatch of its own and the shading would stop meaning "artillery".
 *
 * Enemy batteries are filtered through [UnitConcealment.isVisibleTo] exactly as the unit sprites
 * are — an overlay that outlined a hidden battery's reach would be a free reveal, the same rule
 * hidden AA is drawn by (`DEFERRED.md` §1.1).
 */
internal object ArtilleryRangeOverlay {
    /** Covered hexes as `row * cols + col` keys, split by whose guns cover them. */
    internal class Coverage(
        val own: Set<Int>,
        val enemy: Set<Int>,
    ) {
        fun isEmpty(): Boolean = own.isEmpty() && enemy.isEmpty()

        fun key(
            row: Int,
            col: Int,
            cols: Int,
        ): Int = row * cols + col
    }

    private val empty = Coverage(emptySet(), emptySet())

    /**
     * [map]'s artillery envelopes as seen by [spotSide]. Own batteries are always included; enemy
     * ones only while they are visible to that side.
     */
    fun compute(
        map: GameMap,
        spotSide: Int,
    ): Coverage {
        if (map.rows <= 0 || map.cols <= 0) return empty
        val own = mutableSetOf<Int>()
        val enemy = mutableSetOf<Int>()
        map.units.forEach { unit ->
            if (unit.destroyed) return@forEach
            if (unit.unitData().uclass != UnitClass.ARTILLERY.value) return@forEach
            val pos = unit.getPos() ?: return@forEach
            val friendly = unit.player?.side == spotSide
            if (!friendly && !UnitConcealment.isVisibleTo(unit, spotSide)) return@forEach
            // Range 0 is a real authored value under OG's `True range 0` -- such a battery cannot
            // fire at anything, so it gets no envelope rather than a single own-hex patch.
            val range = GameRules.getUnitAttackRange(unit)
            if (range <= 0) return@forEach
            val target = if (friendly) own else enemy
            target.add(pos.row * map.cols + pos.col)
            HexGeometry.getRing(pos.row, pos.col, range, map.rows, map.cols, false).forEach { cell ->
                if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                    target.add(cell.row * map.cols + cell.col)
                }
            }
        }
        return Coverage(own, enemy)
    }
}
