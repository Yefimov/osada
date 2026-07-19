package org.osada.scenario

import org.osada.model.GameUnit

/** Reinforcement queue operations for [Scenario], split out to keep its function count in bounds. */
fun Scenario.addReinforcement(
    turn: Int,
    row: Int,
    col: Int,
    unit: GameUnit,
) {
    val list = reinforcements.getOrPut(turn) { mutableListOf() }
    list.add(Scenario.Reinforcement(turn, row, col, unit, list.size + 1))
}

fun Scenario.getReinforcements(
    turn: Int,
    owner: Int,
): List<Scenario.Reinforcement> {
    val result = mutableListOf<Scenario.Reinforcement>()
    reinforcements.entries.filter { it.key <= turn }.forEach { (_, list) ->
        result.addAll(list.filter { it.unit.owner == owner })
    }
    return result
}

fun Scenario.removeReinforcement(
    turn: Int,
    id: Int,
): Boolean {
    val list = reinforcements[turn] ?: return false
    var removed = false
    val iter = list.iterator()
    while (iter.hasNext()) {
        if (iter.next().id == id) {
            iter.remove()
            removed = true
            break
        }
    }
    return removed
}
