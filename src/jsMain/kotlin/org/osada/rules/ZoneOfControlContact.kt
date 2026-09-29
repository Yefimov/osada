package org.osada.rules

import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Hex

/**
 * The surface zone of control a moving unit can run into -- one definition for the move overlay,
 * the route and the walk (`docs/design/combat-movement-heroes-weather-audit.md` §6).
 *
 * **Known versus discovered.** The overlay and the route are built only from enemies the mover's
 * side can actually SEE (their hex is spotted, or they revealed themselves by firing), so a hidden
 * unit's position is never read off the highlighting. The walk then stops on the first hex where
 * any enemy's zone applies -- a newly discovered contact is a legitimate interruption. Without
 * combat when the enemy is beside the route; when it stands ON the route further on, the stop is
 * a surprise and the existing surprise combat follows
 * (`MoveReactions.stoppedByZoneOfControl`). OG's manual (6.13, 6.13.1) states exactly this: *"Movement could end before
 * reaching the destination if the unit moves adjacent to or is surprised by a previously hidden
 * enemy unit."*
 *
 * Restored on 2026-09-27 after a one-day removal, on the owner's decision, with three corrections to
 * the check that had been there before: it tests the enemy UNIT's visibility rather than whether the
 * entered terrain was spotted (which leaked hidden units into the overlay); it counts only SURFACE
 * projectors, so an aircraft's air zone of control never stops a tank; and it runs after the leader
 * reactions of the same step, which therefore still fire (`MoveExecutor.reactionStoppingMove`).
 *
 * Who is exempt: aircraft, and the three sources OG calls "not stopped by adjacent enemies" --
 * Superior Maneuver, Battlefield Intelligence and Partizan
 * ([UnitCapabilities.notStoppedByAdjacentEnemies]). The exemption lets a unit pass; it never lets it
 * enter an occupied hex.
 */
internal object ZoneOfControlContact {
    /** Whether surface zones of control stop [mover] at all. */
    fun appliesTo(mover: GameUnit): Boolean =
        !UnitPredicates.isAir(mover) && !UnitCapabilities.notStoppedByAdjacentEnemies(mover)

    /** Enemies of [moverSide] whose surface zone of control covers [cell]. */
    fun projectors(
        grid: Array<Array<Hex>>,
        cell: Cell,
        moverSide: Int,
    ): List<GameUnit> =
        HexGeometry.getAdjacent(cell.row, cell.col).mapNotNull { adjacent ->
            grid
                .getOrNull(adjacent.row)
                ?.getOrNull(adjacent.col)
                ?.unit
                ?.takeIf { enemy ->
                    val side = enemy.player?.side
                    side != null &&
                        side != moverSide &&
                        !enemy.destroyed &&
                        !UnitPredicates.isAir(enemy) &&
                        UnitCapabilities.projectsZoneOfControl(enemy)
                }
        }

    /** Whether [observerSide] can see [enemy] -- the unit, not merely the terrain around it, and not
     *  a concealed one (`UnitConcealment.isVisibleTo`, the game's single visibility answer). */
    fun isKnownTo(
        enemy: GameUnit,
        observerSide: Int,
    ): Boolean = UnitConcealment.isVisibleTo(enemy, observerSide)

    /** A zone of control on [cell] that [moverSide] already knows about: what the overlay and route may use. */
    fun knownZoc(
        grid: Array<Array<Hex>>,
        cell: Cell,
        moverSide: Int,
    ): Boolean = projectors(grid, cell, moverSide).any { isKnownTo(it, moverSide) }
}
