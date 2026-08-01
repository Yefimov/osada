package org.osada.model

/**
 * The one-time setup window at the start of a scenario. It closes permanently after the first
 * player unit moves or fires; ordinary reserve deployment remains governed by the existing deploy
 * rules and can still happen later in the scenario.
 */
internal fun GameMap.isInitialDeploymentWindow(player: Player): Boolean =
    turn == 1 &&
        currentPlayer?.id == player.id &&
        units.none { unit -> unit.owner == player.id && (unit.hasMoved || unit.hasFired) }
