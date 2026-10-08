package org.osada.scenario

import org.osada.GameHolder
import org.osada.model.GameMap
import org.osada.rules.TypedVictoryHexes

/*
 * OG's end-of-scenario holding conditions (manual §3.7.3, `opt_hold_vhs`) and what they do to the
 * ordinary capture victory.
 *
 * The OG author's feature page states it outright: a side given a "hold N victory hexes at the end"
 * condition does NOT win by capturing every victory hex -- its grade is decided only when the turn
 * limit runs out (https://www.luis-guzman.com/OpenGen_Features.html). OSADA graded such a side by
 * hold counts at the limit (`Scenario.checkTimedOutcome`) but still let `updateVictorySides` and the
 * typed-objective check end the battle the moment it took the last objective, by a turn-deadline
 * grade the tooltip never showed. In `forward15` the +20% campaign allowance only moves the last
 * deadline, so a capture on turns 14-16 ended the battle as a mere tactical victory.
 *
 * The rule is PER SIDE, like the thresholds themselves: a side with no hold triple keeps the
 * immediate capture victory even when its opponent is graded by holding. Captures, ownership and the
 * objective lists are untouched -- only the "this capture ends the battle" verdict is withheld.
 * Elimination, retreat, kill and MSU conditions are separate mechanisms and are not affected.
 *
 * Derived from the scenario's hold counts every time, never stored: those counts travel in saves
 * (`GameStateSerializer`), so a restored battle gets the same answer as a fresh load, and a save's
 * persisted `captureGoalSides` needs no migration.
 */

/** The three authored grades (brilliant / victory / tactical) a hold triple must carry. */
private const val HOLD_TIERS = 3

/** [side]'s authored hold thresholds, brilliant first; empty when that side has none. */
internal fun Scenario.holdCountsFor(side: Int): List<Int> = if (side == 0) victoryHoldCounts else victoryHoldCountsSide1

/** Whether [side] is graded by the objectives it holds when the turn limit runs out. */
internal fun Scenario.gradedByHolding(side: Int): Boolean = holdCountsFor(side).size >= HOLD_TIERS

/**
 * Whether completing a capture goal (every objective, or a typed-objective tier) ends the battle
 * for [side] at once. False exactly when [side] is graded by holding.
 */
internal fun Scenario.captureEndsBattle(side: Int): Boolean = !gradedByHolding(side)

/**
 * [captureEndsBattle] for the scenario that owns this map, as the model layer asks it.
 *
 * A map that is not the live scenario's (a test fixture, a detached copy) keeps the plain capture
 * rule, as it did before hold conditions were consulted here.
 */
internal fun GameMap.captureEndsBattleFor(side: Int): Boolean =
    GameHolder.instance
        ?.scenario
        ?.takeIf { it.map === this }
        ?.captureEndsBattle(side) ?: true

/**
 * Whether the capture [side] just made ends the battle: [allCaptured] is `updateVictorySides`'
 * verdict (which already withholds it from a hold-graded side), and a completed typed-objective
 * tier (`TypedVictoryHexes`) counts only for a side whose captures can end the battle at all.
 */
internal fun GameMap.captureEndsBattleNow(
    side: Int,
    allCaptured: Boolean,
): Boolean {
    val scenario = GameHolder.instance?.scenario?.takeIf { it.map === this }
    return allCaptured ||
        (
            scenario != null &&
                scenario.captureEndsBattle(side) &&
                TypedVictoryHexes.completedTier(scenario, this, side) != null
        )
}
