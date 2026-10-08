package org.osada.model

import org.osada.GameHolder

/**
 * The one-time setup window at the start of a scenario. It closes permanently after the first
 * player unit moves or fires; ordinary reserve deployment remains governed by the existing deploy
 * rules and can still happen later in the scenario.
 *
 * It opens on the player's FIRST PLAYABLE turn, which is turn 2 for the first player of a
 * `skip_first_turn` scenario: OG's own briefing (`fr11i.txt`) has the core deployed *"at the start
 * of the second turn"* there.
 */
internal fun GameMap.isInitialDeploymentWindow(player: Player): Boolean =
    turn == firstPlayableTurn(player) &&
        currentPlayer?.id == player.id &&
        units.none { unit -> unit.owner == player.id && (unit.hasMoved || unit.hasFired) }

/** 2 for the first player of a scenario that authors `skip_first_turn` (`Scenario.skipFirstTurn`),
 *  1 for everybody else. */
internal fun GameMap.firstPlayableTurn(player: Player): Int {
    val scenario = GameHolder.instance?.scenario?.takeIf { it.map === this }
    val skipped = scenario?.skipFirstTurn == true && getPlayers().firstOrNull()?.id == player.id
    return if (skipped) 2 else 1
}
