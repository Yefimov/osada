package org.osada.rules

import org.osada.model.GameMap
import org.osada.model.getPlayer
import org.osada.model.victoryTiersForSide
import org.osada.scenario.Scenario

/**
 * OG manual §3.7.2's **Typed victory hexes** — objectives that count only for certain victory
 * levels.
 *
 * > *"Typed VH allow you to set some VH as needed for a level of victory."*
 *
 * The manual's own worked examples are the specification, and both fall out of a 3-bit mask per
 * hex per side (`1` brilliant, `2` victory, `4` tactical):
 *
 * > *"In the first example, the Axis side needs to take only A in the number of turns set for a
 * > BV. If the turns for a BV pass without taking A, then he just needs to take B and C."* — so A
 * > is `1` and B, C are `6`.
 * >
 * > *"In the second example, the Axis side needs both A and B to achieve a BV, for a V he needs all
 * > three, and for a TV just A and C."* — A is `7`, B is `3`, C is `6`.
 *
 * **Decoded 2026-08-30**: `.xscn` grid `@10` is two nibbles, one per side, each such a mask. This
 * project had read the byte as a plain "is an objective" flag with a side in it, which is why the
 * commonest value by far is `0x77` — both sides, all three levels, the ordinary victory hex.
 *
 * ### What it changes, and what it deliberately does not
 *
 * With `opt_specific_vh` off — or on a scenario whose hexes are all `7` — [completedTier] answers
 * exactly what "hold every objective" answered before, so **nothing moves for the 3,200-odd
 * scenarios that do not author typed hexes**.
 *
 * With it on, a side wins at the BEST level whose hexes it holds in full. That is strictly more
 * generous than the old rule, which required every objective for any result at all: a player who
 * has taken the brilliant-victory hex now gets the brilliant victory without also clearing the
 * tactical ones. The turn limits still apply on top — `Scenario.checkVictory` caps the result by
 * how long it took — so this can raise the level a player is eligible for, never lower it.
 */
object TypedVictoryHexes {
    /** Tier bit for a brilliant victory. */
    const val BRILLIANT = 1

    /** Tier bit for a victory. */
    const val VICTORY = 2

    /** Tier bit for a tactical victory. */
    const val TACTICAL = 4

    private val TIERS = listOf(BRILLIANT, VICTORY, TACTICAL)

    /** Whether the scenario authored per-level objectives at all. */
    fun enabled(scenario: Scenario): Boolean = scenario.typedVictoryHexes == true

    /**
     * The best level [side] has fully taken, as an index into `victoryTurns` (0 = brilliant), or
     * null when no level is complete.
     *
     * A level with no hexes marked for it cannot be "completed" — that would hand a player a
     * victory the author never defined.
     */
    fun completedTier(
        scenario: Scenario,
        map: GameMap,
        side: Int,
    ): Int? {
        if (!enabled(scenario)) return null
        return TIERS.indexOfFirst { tier -> tierComplete(map, side, tier) }.takeIf { it >= 0 }
    }

    /** Whether every objective marked for [tier] is held by [side], and at least one exists. */
    private fun tierComplete(
        map: GameMap,
        side: Int,
        tier: Int,
    ): Boolean {
        var required = 0
        var held = 0
        for (row in 0 until map.rows) {
            for (col in 0 until map.cols) {
                val hex =
                    map.map
                        ?.getOrNull(row)
                        ?.getOrNull(col)
                        ?.takeIf { it.victorySide != -1 }
                        ?.takeIf { it.victoryTiersForSide(side) and tier != 0 }
                        ?: continue
                required++
                if (hex.owner != -1 && map.getPlayer(hex.owner).side == side) held++
            }
        }
        return required > 0 && held == required
    }
}
