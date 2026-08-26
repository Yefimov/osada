package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.TerrainType
import org.osada.model.GameUnit
import org.osada.model.Leaders

/**
 * Whether a unit standing in a spotted hex is nevertheless not visible.
 *
 * Everything about OSADA's fog is a per-hex, per-side reference count ([org.osada.model.Hex]'s
 * `spotted` array, invariant documented at `model/GameMapGrid`), which answers "can this side see
 * this GROUND?" — a question with no room in it for a unit that is hidden while its hex is not. OG's
 * `Forest Camouflage` needs exactly that room: *"In a forest hex the unit cannot be spotted unless
 * enemy moves adjacent."*
 *
 * So concealment is a **second, unit-level layer over the hex counters**, deliberately not folded
 * into them. Folding it in would mean adding and removing spotting by a rule that changes as enemies
 * walk past, and `setSpotRange`'s add/remove symmetry is the only thing keeping those counters
 * correct — a range that changes out from under them leaves fog permanently lifted (that failure is
 * recorded on `Hex.clearSpotted`). Nothing here writes anything.
 *
 * The trait was advertised to the player and read by nothing until 2026-08-18
 * (`docs/og-fidelity-plan.md` A.4).
 */
internal object UnitConcealment {
    /**
     * True when [unit] is hidden from [observerSide] despite its hex being spotted.
     *
     * Three conditions, all of them from the sentence: the commander has the trait, the unit is
     * standing in FOREST, and no enemy of the unit stands adjacent. "Enemy moves adjacent" is read
     * as a state rather than as an event — a unit that walked adjacent and stopped there has still
     * moved adjacent, and storing a one-shot "was revealed" bit would need serialization and an
     * answer for what un-reveals it.
     *
     * Aircraft are never concealed: they are on the air layer and are not standing in the forest.
     */
    fun isConcealed(
        unit: GameUnit,
        observerSide: Int,
    ): Boolean {
        if (unit.player?.side == observerSide) return false
        return concealedByCommander(unit, observerSide) ||
            ExtendedLos.isConcealedByTerrain(unit, observerSide)
    }

    /** The `Forest Camouflage` half, unchanged since 2026-08-18. Split out when OG's Extended LOS
     *  optional rule (9.5 bullets 2 and 3) became a second source of the same layer: two rules,
     *  two predicates, one answer, so neither surface can honour one and miss the other. */
    private fun concealedByCommander(
        unit: GameUnit,
        observerSide: Int,
    ): Boolean {
        val hex = unit.getHex() ?: return false
        return !UnitPredicates.isAir(unit) &&
            hex.terrain == TerrainType.FOREST.value &&
            Leaders.unitHasLeader(unit, LeaderType.FOREST_CAMOUFLAGE) &&
            !hasAdjacentEnemy(unit, observerSide)
    }

    /**
     * The complete "can [observerSide] see this formation?" question: its own units always, an
     * enemy only when its hex is spotted (or it revealed itself by firing, `tempSpotted`) AND it is
     * not concealed.
     *
     * Call sites that already spell out `hex.isSpotted(side) || unit.tempSpotted` should route
     * through here rather than adding a third clause of their own, so concealment cannot end up
     * honoured by targeting and ignored by the renderer.
     */
    fun isVisibleTo(
        unit: GameUnit,
        observerSide: Int,
    ): Boolean {
        if (unit.player?.side == observerSide) return true
        val spotted = unit.getHex()?.isSpotted(observerSide) == true || unit.tempSpotted
        return spotted && !isConcealed(unit, observerSide)
    }

    /** Whether any unit of [observerSide] stands on one of [unit]'s six neighbours. Reads the live
     *  map through [GameHolder] for the same reason `WeatherCombatRules` reads the scenario there:
     *  the predicate is asked from renderers and click handlers that hold no map reference. */
    private fun hasAdjacentEnemy(
        unit: GameUnit,
        observerSide: Int,
    ): Boolean {
        val pos = unit.getPos()
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map
        if (pos == null || grid == null) return false
        return HexGeometry.getAdjacent(pos.row, pos.col).any { cell ->
            val hex = grid.getOrNull(cell.row)?.getOrNull(cell.col)
            hex?.unit?.player?.side == observerSide || hex?.airunit?.player?.side == observerSide
        }
    }
}
